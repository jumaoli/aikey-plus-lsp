package io.github.jumaoli.aikey;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App 侧的动作引擎：
 *  - 把 shell 动作脚本释放到应用私有目录
 *  - 通过常驻 root shell 执行动作（和原 KernelSU 模块完全同一套实现）
 *  - 管理"守护进程模式"的 getevent 守护进程
 */
public final class Engine {

    public static final class Status {
        public int root;          // 0 未知 / 1 已授权 / -1 未授权
        public boolean daemon;
        public boolean hookThisBoot;
        public String hookInfo = "";
        public String mode = Prefs.MODE_HOOK;
    }

    private static volatile Engine inst;

    private final Context app;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private volatile boolean installed;
    private volatile boolean daemonState;

    private Engine(Context c) {
        this.app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
    }

    public static Engine get(Context c) {
        if (inst == null) {
            synchronized (Engine.class) {
                if (inst == null) {
                    inst = new Engine(c);
                }
            }
        }
        return inst;
    }

    public static Context dp(Context c) {
        try {
            Context d = c.createDeviceProtectedStorageContext();
            if (d != null) {
                return d;
            }
        } catch (Throwable ignored) {
        }
        return c;
    }

    public static File dir(Context c) {
        return new File(dp(c).getFilesDir(), "aikey");
    }

    public ExecutorService bg() {
        return bg;
    }

    public Context ctx() {
        return app;
    }

    // ---------------- 安装 / 配置 ----------------

    public synchronized void ensureInstalled() {
        writeHookConfigSync();
        if (installed) {
            return;
        }
        File d = dir(app);
        if (!d.exists()) {
            d.mkdirs();
        }
        try {
            AssetManager am = app.getAssets();
            // 放在 assets 根目录：aapt2 在 Windows 上会给嵌套 assets 写出反斜杠路径
            String[] names = {"aikey_lib.sh", "dispatch.sh", "aikey_daemon.sh"};
            for (String n : names) {
                copyAsset(am, n, new File(d, n));
            }
        } catch (Throwable t) {
            Logs.append(app, "释放脚本失败: " + t);
        }
        writeConfig();
        installed = true;
    }

    private static void copyAsset(AssetManager am, String name, File out) {
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = am.open(name);
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Throwable ignored) {
            }
            out.setReadable(true, false);
        }
    }

    /** 生成动作命令表：id=shell 命令，dispatch 时按 id 查表执行。 */
    public void writeCommands() {
        StringBuilder sb = new StringBuilder("# 由 App 生成，勿手改\n");
        int display = Prefs.backDisplay(app);
        for (ActionCatalog.Item it : ActionCatalog.items(null)) {
            String cmd = ActionCatalog.command(it.id, display);
            if (cmd != null) {
                sb.append(it.id).append('=').append(cmd).append('\n');
            }
        }
        File f = new File(dir(app), "commands.conf");
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            f.setReadable(true, false);
        } catch (Throwable t) {
            Logs.append(app, "写命令表失败: " + t);
        }
    }

    /** 给钩子读的配置：写进 Settings.System（system_server 可直接读）。 */
    public void writeHookConfigSync() {
        try {
            StringBuilder cmd = new StringBuilder();
            cmd.append("settings put system aikey_single ").append(q(Prefs.action(app, Prefs.G_SINGLE))).append("; ");
            cmd.append("settings put system aikey_double ").append(q(Prefs.action(app, Prefs.G_DOUBLE))).append("; ");
            cmd.append("settings put system aikey_triple ").append(q(Prefs.action(app, Prefs.G_TRIPLE))).append("; ");
            cmd.append("settings put system aikey_long ").append(q(Prefs.action(app, Prefs.G_LONG))).append("; ");
            cmd.append("settings put system aikey_long_ms ").append(Prefs.longMs(app)).append("; ");
            cmd.append("settings put system aikey_double_ms ").append(Prefs.doubleMs(app)).append("; ");
            cmd.append("settings put system aikey_triple_ms ").append(Prefs.tripleMs(app)).append("; ");
            cmd.append("settings put system aikey_haptic ")
                    .append(Prefs.boolVal(app, Prefs.K_HAPTIC, true) ? 1 : 0).append("; ");
            cmd.append("settings put system aikey_codes ").append(q(Prefs.keyCodes(app))).append("; ");
            cmd.append("settings put system aikey_mode ").append(q(Prefs.mode(app))).append("; true");
            Shell.run(cmd.toString(), 12000);
        } catch (Throwable t) {
            Logs.append(app, "写钩子配置失败: " + t);
        }
    }

    public void writeConfig() {
        File f = new File(dir(app), "config.conf");
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(Prefs.configText(app).getBytes("UTF-8"));
            fos.close();
            f.setReadable(true, false);
            writeCommands();
        } catch (Throwable t) {
            Logs.append(app, "写配置失败: " + t);
        }
        writeHookConfigSync();
    }

    public void readConfigFromShell() {
        // 目前配置只由 App 写，这里留作扩展位
    }

    // ---------------- 动作 ----------------

    /** 同步执行一个动作（会阻塞，调用方要在后台线程）。 */
    public boolean runActionSync(String action) {
        ensureInstalled();
        writeConfig();
        android.util.Log.i("AIKeyApp", "runAction " + action);
        if (action == null || action.isEmpty() || "none".equals(action)) {
            return true;
        }
        if ("torch".equals(action)) {
            return Torch.toggle(app);
        }
        if ("torch_on".equals(action)) {
            return Torch.set(app, true);
        }
        if ("torch_off".equals(action)) {
            return Torch.set(app, false);
        }
        if ("widget".equals(action)) {
            String provider = Prefs.strVal(app, Prefs.K_WIDGET_PROVIDER, "");
            if (provider.isEmpty()) {
                // 没选过就用第一个可用的小部件
                java.util.List<android.appwidget.AppWidgetProviderInfo> list =
                        android.appwidget.AppWidgetManager.getInstance(app).getInstalledProviders();
                if (list != null) {
                    for (android.appwidget.AppWidgetProviderInfo info : list) {
                        if (info != null && info.provider != null) {
                            provider = info.provider.flattenToString();
                            Prefs.setStr(app, Prefs.K_WIDGET_PROVIDER, provider);
                            break;
                        }
                    }
                }
            }
            if (provider.isEmpty()) {
                Logs.append(app, "没有可用的小部件");
                return false;
            }
            if (Shell.hasRoot()) {
                Shell.run("appwidget grantbind --package com.xiaomi.subscreencenter --user 0", 8000);
            }
            try {
                android.content.Intent b = new android.content.Intent("io.github.jumaoli.aikey.SHOW_WIDGET");
                b.setPackage("com.xiaomi.subscreencenter");
                b.putExtra("provider", provider);
                app.sendBroadcast(b);
                Logs.append(app, "请求背屏显示小部件: " + provider);
                return true;
            } catch (Throwable t) {
                Logs.append(app, "广播失败: " + t);
                return false;
            }
        }        if ("widget_off".equals(action)) {
            WidgetBoard.hide(app);
            try {
                android.content.Intent b = new android.content.Intent("io.github.jumaoli.aikey.HIDE_WIDGET");
                b.setPackage("com.xiaomi.subscreencenter");
                app.sendBroadcast(b);
            } catch (Throwable ignored) {
            }
            return true;
        }
        if (!Shell.hasRoot()) {
            Logs.append(app, "执行失败：没有 root 权限");
            return false;
        }
        String cmd = "sh " + q(new File(dir(app), "dispatch.sh").getAbsolutePath())
                + " " + q(action);
        Shell.Result r = Shell.run(cmd, 20000);
        Logs.append(app, "动作 " + action + " -> " + (r.ok ? "ok" : "失败(code=" + r.code + ")")
                + trimOut(r.out));
        return r.ok;
    }

    private static String trimOut(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.length() > 300) {
            s = s.substring(0, 300) + "…";
        }
        return " | " + s;
    }

    // ---------------- 守护进程模式 ----------------

    public boolean startDaemonSync() {
        ensureInstalled();
        writeConfig();
        if (!Shell.hasRoot()) {
            Logs.append(app, "没有 root，守护进程起不来");
            return false;
        }
        stopDaemonSync();
        final String base = "/data/local/tmp/aikey";
        StringBuilder sb = new StringBuilder("mkdir -p " + base + "/state; ");
        sb.append(pushAsset("aikey_lib.sh", base + "/aikey_lib.sh"));
        sb.append(pushAsset("aikey_daemon.sh", base + "/aikey_daemon.sh"));
        sb.append(pushAsset("dispatch.sh", base + "/dispatch.sh"));
        sb.append(pushText(Prefs.configText(app), base + "/config.conf"));
        sb.append(pushText(readCommands(), base + "/commands.conf"));
        sb.append("chmod 755 " + base + "/*.sh; ");
        Shell.Result push = Shell.run(sb.toString(), 30000);
        if (!push.ok) {
            Logs.append(app, "推送脚本失败: " + push.out.trim());
            return false;
        }
        Shell.run("p=$(cat " + base + "/daemon.pid 2>/dev/null); [ -n \"$p\" ] && kill -9 $p 2>/dev/null; "
                + "rm -f " + base + "/daemon.pid; rm -rf " + base + "/daemon.lock; true", 8000);
        Shell.run("cd " + base + " && setsid sh ./aikey_daemon.sh >/dev/null 2>&1 &", 10000);
        try {
            Thread.sleep(700);
        } catch (Throwable ignored) {
        }
        daemonState = daemonAliveSync();
        Logs.append(app, "守护进程(base=" + base + ") -> " + (daemonState ? "已运行" : "未起来"));
        return daemonState;
    }

    /** 把 assets 里的脚本推到 /data/local/tmp（App 私有目录 root 读不了）。 */
    private String pushAsset(String asset, String dest) {
        try {
            java.io.InputStream in = app.getAssets().open(asset);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return pushText(new String(bos.toByteArray(), "UTF-8"), dest);
        } catch (Throwable t) {
            Logs.append(app, "读取 " + asset + " 失败: " + t);
            return "";
        }
    }

    /** base64 传内容，避免引号/换行问题。 */
    private String pushText(String content, String dest) {
        try {
            String b64 = android.util.Base64.encodeToString(
                    content.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            return "echo " + b64 + " | base64 -d > " + dest + "; ";
        } catch (Throwable t) {
            return "";
        }
    }

    private String readCommands() {
        try {
            File f = new File(dir(app), "commands.conf");
            if (!f.exists()) {
                return "";
            }
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            in.close();
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 把 assets 里的脚本推到 shell 可读目录。 */
    private String pushFile(String asset, String dest) {
        try {
            java.io.InputStream in = app.getAssets().open(asset);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return pushText(asset, new String(bos.toByteArray(), "UTF-8"), dest);
        } catch (Throwable t) {
            Logs.append(app, "读取 asset 失败 " + asset + ": " + t);
            return "";
        }
    }

    /** 用 base64 传内容，避开引号 / 换行问题。 */
    private String pushText(String name, String content, String dest) {
        try {
            String b64 = android.util.Base64.encodeToString(
                    content.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            return "echo " + b64 + " | base64 -d > " + dest + "; ";
        } catch (Throwable t) {
            return "";
        }
    }

    private void shiz(String unused) {
    }

    public void stopDaemonSync() {
        if (Shell.rootState != 1 && !Shell.hasRoot()) {
            return;
        }
        final String base = "/data/local/tmp/aikey";
        Shell.run("p=$(cat " + base + "/daemon.pid 2>/dev/null); [ -n \"$p\" ] && kill -9 $p 2>/dev/null; "
                + "rm -f " + base + "/daemon.pid; rm -rf " + base + "/daemon.lock; "
                + "pkill -9 -f aikey_daemon.sh 2>/dev/null; true", 8000);
        daemonState = false;
    }

    public boolean daemonAliveSync() {
        if (!Shell.hasRoot()) {
            return false;
        }
        final String base = "/data/local/tmp/aikey";
        String cmd = "if [ -f " + base + "/daemon.pid ]; then p=$(cat " + base
                + "/daemon.pid); kill -0 $p 2>/dev/null && echo 1 || echo 0; else echo 0; fi";
        Shell.Result r = Shell.run(cmd, 8000);
        daemonState = r.ok && r.out.trim().endsWith("1");
        return daemonState;
    }

    // ---------------- 系统设置 ----------------

    /** com.miui.securitycore 里的原生「AI 键」设置页（exported=true，可以直接拉起来）。 */
    public static final String AI_KEY_SETTINGS = "com.miui.securitycore/"
            + "com.miui.miinput.gesture.aikey.GestureShortcutAiKeySettingsActivity";

    /** 改值之前先把系统原来的设置记下来，以后可以一键还原（只记第一次）。 */
    public void captureNativeKeysSync() {
        if (!Shell.hasRoot() || Prefs.strVal(app, Prefs.K_NATIVE_CLICK, null) != null) {
            return;
        }
        String click = readSetting("click_mi_ai_key");
        String lng = readSetting("long_press_mi_ai_key");
        Prefs.setStr(app, Prefs.K_NATIVE_CLICK, click);
        Prefs.setStr(app, Prefs.K_NATIVE_LONG, lng);
        Logs.append(app, "记录系统原值: click=" + click + " long=" + lng);
    }

    private String readSetting(String key) {
        Shell.Result r = Shell.run("settings get system " + key, 8000);
        String v = r.ok ? r.out.trim() : "";
        if ("null".equals(v)) {
            v = "";
        }
        return v;
    }

    /** 把系统自带的 AI 键动作关掉，避免系统和我们抢这个键。 */
    public void applySystemKeysSync() {
        if (!Shell.hasRoot()) {
            return;
        }
        captureNativeKeysSync();
        // 允许绑定小部件（root 才能用 appwidget 命令）
        Shell.run("appwidget grantbind --package " + app.getPackageName() + " --user 0", 8000);
        Shell.run("pm grant " + app.getPackageName() + " android.permission.CAMERA", 8000);
        Shell.run("settings put system click_mi_ai_key none; "
                + "settings put system long_press_mi_ai_key none", 10000);
        Logs.append(app, "已关闭系统自带 AI 键动作（原值已记录）");
    }

    /** 还原成模块接管之前的值；没有记录时退回小米默认值。 */
    public void restoreSystemKeysSync() {
        if (!Shell.hasRoot()) {
            return;
        }
        String click = Prefs.strVal(app, Prefs.K_NATIVE_CLICK, "");
        String lng = Prefs.strVal(app, Prefs.K_NATIVE_LONG, "");
        if (click == null || click.isEmpty()) {
            click = "none";
        }
        if (lng == null || lng.isEmpty()) {
            lng = "none";
        }
        Shell.run("settings put system click_mi_ai_key " + q(click)
                + "; settings put system long_press_mi_ai_key " + q(lng), 10000);
        Logs.append(app, "已还原系统 AI 键: click=" + click + " long=" + lng);
    }

    /** 猜一下背屏是哪个 display：从 dumpsys display 里找名字像副屏/背屏的。 */
    public String detectAiKeyEventSync() {
        Shell.Result r = Shell.run("for d in /dev/input/event*; do "
                + "n=$(cat /sys/class/input/$(basename $d)/device/name 2>/dev/null); "
                + "[ \"$n\" = \"gpio-keys\" ] && echo $d; done", 8000);
        if (r.ok && r.out.trim().length() > 0) {
            String dev = r.out.trim().split("\\s+")[0];
            Prefs.setStr(app, Prefs.K_EVENT, dev);
            return dev;
        }
        return null;
    }

    public String detectBackDisplaySync() {
        if (!Shell.hasRoot()) {
            return null;
        }
        Shell.Result r = Shell.run("dumpsys display | grep -E 'DisplayDeviceInfo|mDisplayId=' | head -n 60", 20000);
        String best = null;
        for (String line : r.out.split("\n")) {
            String low = line.toLowerCase();
            boolean looksBack = low.contains("rear") || low.contains("sub") || low.contains("back")
                    || line.contains("副屏") || line.contains("背屏");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("local:(\\d+)").matcher(line);
            if (m.find()) {
                int id;
                try {
                    id = Integer.parseInt(m.group(1));
                } catch (Throwable t) {
                    continue;
                }
                if (id != 0 && (looksBack || best == null)) {
                    best = String.valueOf(id);
                }
            }
        }
        Logs.append(app, "背屏探测: " + (best == null ? "没找到，保持 " + Prefs.backDisplay(app) : best)
                + " | " + r.out.trim().replace("\n", " / "));
        return best;
    }

    /** 打开系统自带的「AI 键」设置页，方便对照原生动作。 */
    public boolean openNativeAiKeySettingsSync() {
        try {
            android.content.ComponentName cn =
                    android.content.ComponentName.unflattenFromString(AI_KEY_SETTINGS);
            if (cn == null) {
                return false;
            }
            android.content.Intent i = new android.content.Intent();
            i.setComponent(cn);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            Logs.append(app, "打开系统 AI 键设置页");
            return true;
        } catch (Throwable t) {
            Logs.append(app, "直接打开系统设置失败: " + t + "，改用 root am start");
        }
        if (!Shell.hasRoot()) {
            return false;
        }
        Shell.Result r = Shell.run("am start -n " + q(AI_KEY_SETTINGS), 12000);
        Logs.append(app, "am start -> " + r.out.trim());
        return r.ok && !r.out.contains("Error");
    }

    /** 一次性把配置写盘、关掉系统 AI 键、按模式启停守护进程。 */
    public String applyAllSync() {
        ensureInstalled();
        writeConfig();
        if (!Shell.hasRoot(true)) {
            return "未获得 root 权限：动作无法执行，请在 KernelSU / Magisk 里给本应用授权。";
        }
        applySystemKeysSync();
        if (Prefs.MODE_DAEMON.equals(Prefs.mode(app))) {
            boolean ok = startDaemonSync();
            return ok ? "已保存：守护进程模式运行中" : "已保存，但守护进程没有起来（看日志）";
        }
        stopDaemonSync();
        return "已保存：LSPosed 钩子模式";
    }

    // ---------------- 状态 / 日志 ----------------

    public Status statusSync() {
        Status s = new Status();
        s.root = Shell.rootState;
        if (s.root == 0) {
            s.root = Shell.hasRoot() ? 1 : -1;
        }
        s.mode = Prefs.mode(app);
        s.hookThisBoot = Prefs.bootCount(app) >= 0
                && Prefs.intVal(app, Prefs.K_HOOK_BOOT, -2) == Prefs.bootCount(app);
        s.hookInfo = Prefs.strVal(app, Prefs.K_HOOK_INFO, "");
        if (Shell.hasRoot()) {
            s.daemon = daemonAliveSync();
        }
        return s;
    }

    public String readLogsSync(int lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== App / 钩子日志 =====\n");
        sb.append(Logs.tail(app, lines));
        sb.append("\n===== 动作引擎日志 =====\n");
        File f = new File(dir(app), "aikey.log");
        String shellLog = null;
        try {
            if (f.exists() && f.canRead()) {
                shellLog = readTail(f, 64 * 1024);
            }
        } catch (Throwable ignored) {
        }
        if (shellLog == null && Shell.hasRoot()) {
            Shell.Result r = Shell.run("tail -n " + lines + " " + q(f.getAbsolutePath()), 8000);
            shellLog = r.out;
        }
        if (shellLog == null || shellLog.trim().isEmpty()) {
            shellLog = "(暂无，动作执行后会出现)";
        }
        String[] arr = shellLog.split("\n");
        int from = Math.max(0, arr.length - lines);
        for (int i = from; i < arr.length; i++) {
            sb.append(arr[i]).append('\n');
        }
        return sb.toString();
    }

    private static String readTail(File f, int max) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            long skip = Math.max(0, f.length() - max);
            long skipped = 0;
            while (skipped < skip) {
                long n = in.skip(skip - skipped);
                if (n <= 0) {
                    break;
                }
                skipped += n;
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 钩子回调 ----------------

    /** LSPosed 钩子派发过来的手势（provider 线程调用，立刻返回）。 */
    public void dispatchGesture(final String gesture) {
        if (gesture == null) {
            return;
        }
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String mode = Prefs.mode(app);
                boolean hookLike = Prefs.MODE_HOOK.equals(mode);
                if (!hookLike) {
                    Logs.append(app, "收到手势 " + gesture + "，但当前不是钩子模式，忽略");
                    return;
                }
                String act = Prefs.action(app, gesture);
                android.util.Log.i("AIKeyApp", "gesture " + gesture + " -> " + act);
                Logs.append(app, "钩子手势 " + gesture + " -> " + act);
                runActionSync(act);
            }
        });
    }

    // ---------------- 工具 ----------------

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
