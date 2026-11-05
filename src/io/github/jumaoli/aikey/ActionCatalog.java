package io.github.jumaoli.aikey;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 动作表。
 *
 * 三类：
 *  1. 内置动作：手电筒 / 截图 / 录屏 / 桌面，由 root shell（aikey_lib.sh）执行。
 *  2. native:<function>：由 system_server 里的钩子直接调用
 *     com.miui.server.input.util.ShortCutActionsUtils#triggerFunction 执行。
 *     这是 MIUI 自己按键分发用的万能入口，支持付款码、乘车码、扫码、防窥、静音、
 *     勿扰、小爱记忆、背屏截图等几十个功能（已从 OS4 的 miui-services.jar 里核对过函数名）。
 *  3. 普通命令（cmd 非空）：App 写 commands.conf，root shell 执行；带 %D% 的是背屏版。
 *
 * 直启类命令里的 URL/组件都在小米 18 Pro（OS4.0.27）上实测过。
 */
public final class ActionCatalog {

    private ActionCatalog() {
    }

    public static final class Item {
        public final String id;
        public final String label;
        public final String group;
        public final String cmd;

        Item(String id, String label, String group, String cmd) {
            this.id = id;
            this.label = label;
            this.group = group;
            this.cmd = cmd;
        }
    }

    public static final String G_BASIC = "常用";
    public static final String G_AI = "小爱 / 语音";
    public static final String G_PAY = "支付 / 乘车 / 扫码";
    public static final String G_SYS = "系统功能";
    public static final String G_APP = "应用 / 自定义";

    private static final List<Item> ITEMS = new ArrayList<>();

    private static void add(String id, String label, String group, String cmd) {
        ITEMS.add(new Item(id, label, group, cmd));
    }

    private static void nat(String fn, String label) {
        add("native:" + fn, label, G_SYS, null);
    }

    private static void nat(String fn, String label, String group) {
        add("native:" + fn, label, group, null);
    }

    /** 直启 + 背屏版（实测：am start --display 1 可用）。 */
    private static void pay(String id, String label, String view, String pkg) {
        String base = "am start -a android.intent.action.VIEW -d '" + view + "'";
        if (pkg != null) {
            base += " -p " + pkg;
        }
        add(id, label, G_PAY, base);
        add(id + "_back", label + "（背屏）", G_PAY, base + " --display %D%");
    }

    static {
        // ---------------- 常用 ----------------
        add("none", "无（禁用这个手势）", G_BASIC, null);
        add("flashlight", "手电筒开关", G_BASIC, null);
        add("torch_on", "只开手电筒", G_BASIC, null);
        add("torch_off", "只关手电筒", G_BASIC, null);
        add("screenshot", "系统截图（组合键）", G_BASIC, null);
        add("record", "录屏开关（开始 / 停止）", G_BASIC, null);
        add("record_start", "只开始录屏", G_BASIC, null);
        add("record_stop", "只停止录屏", G_BASIC, null);
        add("home", "回到桌面", G_BASIC, null);
        add("game_mode", "游戏加速", G_BASIC, null);

        // ---------------- 小爱 / 语音 ----------------
        nat("launch_voice_assistant", "唤醒超级小爱", G_AI);
        nat("launch_ai_memory", "超级小爱记忆", G_AI);
        nat("launch_xiaoai_memorize", "小爱记一下", G_AI);
        nat("launch_ai_shortcut", "小爱捷径", G_AI);
        nat("launch_xiaoai_screen_identification", "小爱识屏", G_AI);
        nat("live_subtitles", "实时字幕", G_AI);
        nat("live_subtitles_full_screen", "全屏实时字幕", G_AI);

        // ---------------- 支付 / 乘车 / 扫码 ----------------
        nat("launch_alipay_payment_code", "支付宝付款码", G_PAY);
        nat("launch_wechat_payment_code", "微信付款码", G_PAY);
        nat("launch_alipay_bus_code", "支付宝乘车码", G_PAY);
        nat("launch_wechat_bus_code", "微信乘车码", G_PAY);
        nat("launch_alipay_scanner", "支付宝扫一扫", G_PAY);
        nat("launch_wechat_scanner", "微信扫一扫", G_PAY);
        nat("launch_alipay_health_code", "支付宝健康码", G_PAY);
        add("hellobike_scan", "哈啰扫一扫", G_PAY,
                "am start -n com.jingyao.easybike/com.hellobike.platform.scan.v2.NewPlatformScanActivity");

        nat("mi_pay", "Mi Pay", G_PAY);
        nat("launch_QAW_wallet", "钱包（公交/门卡）", G_PAY);

        // 直启兜底（原生不可用时用；背屏版实测可用）
        // 系统参数版：支付宝自己的背屏参数 displayScreen=rear（同 MIUI 原生实现的 URL）
        add("alipay_code_rear", "支付宝付款码（系统背屏参数）", G_PAY,
                "am start -a android.intent.action.VIEW -d "
                + "'alipays://platformapi/startapp?appId=20000056&source=shortcut&displayScreen=rear'"
                + " -p com.eg.android.AlipayGphone");
        add("alipay_bus_rear", "支付宝乘车码（系统背屏参数）", G_PAY,
                "am start -a android.intent.action.VIEW -d "
                + "'alipays://platformapi/startapp?appId=20002191&chInfo=xiaomi_wallet_kjzm"
                + "&pikshemo=YES&skipSplash=YES&scene=busMetro&displayScreen=rear'"
                + " -p com.eg.android.AlipayGphone");
        add("alipay_code", "支付宝付款码（正屏）", G_PAY,
                "am start -a android.intent.action.VIEW -d "
                + "'alipays://platformapi/startapp?appId=20000056&source=shortcut'"
                + " -p com.eg.android.AlipayGphone");
        add("alipay_bus", "支付宝乘车码（正屏）", G_PAY,
                "am start -a android.intent.action.VIEW -d "
                + "'alipays://platformapi/startapp?appId=20002047&chInfo=xiaomi_wallet_kjzm&scene=busMetro'"
                + " -p com.eg.android.AlipayGphone");
        add("alipay_scan", "支付宝扫一扫", G_PAY,
                "am start -a android.intent.action.VIEW -d "
                + "'alipays://platformapi/startapp?appId=10000007' -p com.eg.android.AlipayGphone");
        add("wechat_code", "微信付款码", G_PAY,
                "am start -a android.intent.action.VIEW -n "
                + "com.tencent.mm/com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI"
                + " --ei key_entry_scene 2");
        add("wechat_scan", "微信扫一扫（直启）", G_PAY,
                "am start -n com.tencent.mm/.plugin.scanner.ui.BaseScanUI");
        add("xiaomi_scan", "小米扫一扫", G_PAY,
                "am start -n com.xiaomi.scanner/.app.ScanActivity");

        // ---------------- 系统功能 ----------------
        nat("screen_shot", "截图（原生）");
        nat("screenshot_rear", "背屏截图");
        add("backhome", "背屏：回到背屏桌面", G_SYS, null);
        add("widget", "背屏：显示小部件", G_SYS, null);
        add("widget_off", "背屏：关闭小部件", G_SYS, null);
        nat("partial_screen_shot", "局部截图");
        nat("launch_screen_recorder", "系统录屏");
        nat("screen_record_start", "开始录屏（游戏加速）");
        nat("screen_record_stop", "停止录屏（游戏加速）");
        nat("turn_on_torch", "手电筒（原生）");
        nat("mute", "静音");
        nat("zen_mode", "勿扰");
        nat("launch_peep_protection", "防窥");
        nat("launch_control_center", "控制中心");
        nat("launch_notification_center", "通知中心");
        nat("launch_smarthome", "智能家居");
        nat("find_device_locate", "查找设备");
        nat("launch_sound_recorder", "录音机");
        nat("launch_calculator", "计算器");
        nat("launch_google_search", "搜索");
        nat("launch_home", "桌面（原生）");
        nat("switch_eye_protection_mode", "护眼模式");
        nat("switch_berserk_mode", "性能模式");
        nat("launch_mi_creation", "小米创作");
        nat("show_menu", "菜单");
        nat("go_to_sleep", "息屏");
        nat("environment_speech_recognition", "环境音识别");
        nat("miui_talkback", "屏幕朗读（TalkBack）");
        nat("launch_global_search", "全局搜索");
        nat("launch_voice_note", "语音便签");
        nat("launch_sos", "紧急求助");
        nat("screenshot_without_anim", "截图（无动画）");
        nat("stylus_partial_screenshot", "手写笔局部截图");
        nat("vibrate", "震动一下");
        nat("gamebooster_one_key_clean", "一键清理");
        nat("launch_split_screen_to_left", "分屏·放到左边");
        nat("launch_split_screen_to_right", "分屏·放到右边");
        nat("split_screen", "分屏");
        nat("launch_camera_and_take_photo", "相机（拍照）");
        nat("launch_polaroid_and_take_photo", "拍立得拍照");
        nat("hear_sound_subtitle", "听声字幕");

        // ---------------- 相机 ----------------
        nat("launch_camera", "相机", G_BASIC);
        nat("launch_camera_capture", "相机·拍照", G_BASIC);
        nat("launch_camera_video", "相机·录像", G_BASIC);

        // ---------------- 应用 / 自定义 ----------------
        add("app:", "选择应用…", G_APP, null);
        add("appback:", "背屏显示应用…", G_APP, null);
        add("shell:", "自定义命令…", G_APP, null);
    }

    public static List<String> groups() {
        List<String> g = new ArrayList<>();
        g.add(G_BASIC);
        g.add(G_AI);
        g.add(G_PAY);
        g.add(G_SYS);
        g.add(G_APP);
        return g;
    }

    public static List<Item> items(String group) {
        List<Item> out = new ArrayList<>();
        for (Item i : ITEMS) {
            if (group == null || group.equals(i.group)) {
                out.add(i);
            }
        }
        return out;
    }

    public static Item find(String id) {
        if (id == null) {
            return null;
        }
        for (Item i : ITEMS) {
            if (i.id.equals(id)) {
                return i;
            }
        }
        return null;
    }

    public static String command(String id, int backDisplay) {
        Item i = find(id);
        if (i == null || i.cmd == null) {
            return null;
        }
        return i.cmd.replace("%D%", String.valueOf(backDisplay));
    }

    public static String label(Context c, String action) {
        if (action == null || action.isEmpty()) {
            return "无（禁用这个手势）";
        }
        Item i = find(action);
        if (i != null) {
            return i.label;
        }
        if (action.startsWith("app:")) {
            String pkg = action.substring(4);
            String name = appLabel(c, pkg);
            return "应用：" + (name != null ? name : pkg);
        }
        if (action.startsWith("shell:")) {
            String cmd = action.substring(6);
            return "命令：" + (cmd.length() > 26 ? cmd.substring(0, 26) + "…" : cmd);
        }
        if (action.startsWith("native:")) {
            return "原生：" + action.substring(7);
        }
        return action;
    }

    public static String appLabel(Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static List<String[]> launchableApps(Context c) {
        List<String[]> out = new ArrayList<>();
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> list = pm.queryIntentActivities(i, 0);
            for (android.content.pm.ResolveInfo ri : list) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName())) {
                    continue;
                }
                String label;
                try {
                    label = ri.loadLabel(pm).toString();
                } catch (Throwable t) {
                    label = pkg;
                }
                out.add(new String[]{label, pkg});
            }
        } catch (Throwable ignored) {
        }
        java.util.Collections.sort(out, new java.util.Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[0].compareToIgnoreCase(b[0]);
            }
        });
        return out;
    }
}
