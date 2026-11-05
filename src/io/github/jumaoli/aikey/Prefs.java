package io.github.jumaoli.aikey;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置读写。统一放在「设备保护存储」里：开机后、解锁前也能读到，
 * 这样开机自启的守护进程和 LSPosed 钩子都能拿到配置。
 */
public final class Prefs {

    public static final String NAME = "aikey";

    public static final String G_SINGLE = "single";
    public static final String G_DOUBLE = "double";
    public static final String G_LONG = "long";
    public static final String G_TRIPLE = "triple";

    public static final String K_SINGLE = "action_single";
    public static final String K_DOUBLE = "action_double";
    public static final String K_LONG = "action_long";
    public static final String K_TRIPLE = "action_triple";
    public static final String K_TRIPLE_MS = "triple_ms";
    public static final String K_LONG_MS = "long_ms";
    public static final String K_DOUBLE_MS = "double_ms";
    public static final String K_TORCH = "torch_level";
    public static final String K_CODES = "key_codes";
    public static final String K_MODE = "mode";
    public static final String K_CONSUME = "consume";
    public static final String K_PROBE = "probe";
    public static final String K_KEEP_ALIVE = "keep_alive";
    public static final String K_AUTO_START = "auto_start";
    public static final String K_HOOK_BOOT = "hook_boot";
    public static final String K_HOOK_INFO = "hook_info";
    public static final String K_DISPLAY = "back_display";
    public static final String K_HAPTIC = "haptic";
    public static final String K_WIDGET = "widget_id";
    public static final String K_WIDGET_PROVIDER = "widget_provider";
    public static final String K_EVENT = "event_device";
    public static final String K_NATIVE_CLICK = "native_click";
    public static final String K_NATIVE_LONG = "native_long";

    public static final String MODE_HOOK = "hook";
    public static final String MODE_DAEMON = "daemon";

    private Prefs() {
    }

    public static SharedPreferences sp(Context c) {
        Context ctx = c;
        try {
            Context dp = c.createDeviceProtectedStorageContext();
            if (dp != null) {
                ctx = dp;
            }
        } catch (Throwable ignored) {
        }
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String action(Context c, String gesture) {
        String def;
        if (G_DOUBLE.equals(gesture)) {
            def = "record";
        } else if (G_LONG.equals(gesture)) {
            def = "screenshot";
        } else {
            def = "flashlight";
        }
        String v = sp(c).getString(key(gesture), null);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        String sys = sysStr(c, "aikey_" + gesture);
        if (sys != null && !sys.isEmpty() && !"null".equals(sys)) {
            return sys;
        }
        return def;
    }

    /** 读 Settings.System 里的值（钩子用的同一份配置）。 */
    private static String sysStr(Context c, String key) {
        try {
            return android.provider.Settings.System.getString(c.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** App 数据被系统清掉时，从 Settings.System 把配置认回来。只补缺失项。 */
    public static void hydrateFromSystem(Context c) {
        try {
            String[] gestures = {G_SINGLE, G_DOUBLE, G_TRIPLE, G_LONG};
            String[] keys = {K_SINGLE, K_DOUBLE, K_TRIPLE, K_LONG};
            android.content.SharedPreferences.Editor e = sp(c).edit();
            boolean dirty = false;
            for (int i = 0; i < gestures.length; i++) {
                if (sp(c).getString(keys[i], null) == null) {
                    String v = sysStr(c, "aikey_" + gestures[i]);
                    if (v != null && !v.isEmpty() && !"null".equals(v)) {
                        e.putString(keys[i], v);
                        dirty = true;
                    }
                }
            }
            if (sp(c).getInt(K_LONG_MS, -1) < 0) {
                String v = sysStr(c, "aikey_long_ms");
                if (v != null) {
                    e.putInt(K_LONG_MS, (int) parseLong(v, 900));
                    dirty = true;
                }
            }
            if (sp(c).getInt(K_DOUBLE_MS, -1) < 0) {
                String v = sysStr(c, "aikey_double_ms");
                if (v != null) {
                    e.putInt(K_DOUBLE_MS, (int) parseLong(v, 300));
                    dirty = true;
                }
            }
            if (sp(c).getInt(K_TRIPLE_MS, -1) < 0) {
                String v = sysStr(c, "aikey_triple_ms");
                if (v != null) {
                    e.putInt(K_TRIPLE_MS, (int) parseLong(v, 400));
                    dirty = true;
                }
            }
            if (!sp(c).contains(K_HAPTIC)) {
                String v = sysStr(c, "aikey_haptic");
                if (v != null) {
                    e.putBoolean(K_HAPTIC, !"0".equals(v));
                    dirty = true;
                }
            }
            if (dirty) {
                e.apply();
                Logs.append(c, "已从 Settings 认回配置");
            }
        } catch (Throwable t) {
            Logs.append(c, "认回配置失败: " + t);
        }
    }

    private static long parseLong(String v, long def) {
        try {
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    public static void setAction(Context c, String gesture, String action) {
        String v = action == null ? "none" : action.replace("\r", " ").replace("\n", " ").trim();
        if (v.isEmpty()) {
            v = "none";
        }
        sp(c).edit().putString(key(gesture), v).apply();
    }

    public static String key(String gesture) {
        if (G_TRIPLE.equals(gesture)) {
            return K_TRIPLE;
        }
        if (G_DOUBLE.equals(gesture)) {
            return K_DOUBLE;
        }
        if (G_LONG.equals(gesture)) {
            return K_LONG;
        }
        return K_SINGLE;
    }

    public static String mode(Context c) {
        return sp(c).getString(K_MODE, MODE_DAEMON);
    }

    public static void setMode(Context c, String mode) {
        sp(c).edit().putString(K_MODE, mode).apply();
    }

    public static int intVal(Context c, String key, int def) {
        return sp(c).getInt(key, def);
    }

    public static void setInt(Context c, String key, int v) {
        sp(c).edit().putInt(key, v).apply();
    }

    public static boolean boolVal(Context c, String key, boolean def) {
        return sp(c).getBoolean(key, def);
    }

    public static void setBool(Context c, String key, boolean v) {
        sp(c).edit().putBoolean(key, v).apply();
    }

    public static String strVal(Context c, String key, String def) {
        String s = sp(c).getString(key, def);
        return s == null ? def : s;
    }

    public static void setStr(Context c, String key, String v) {
        sp(c).edit().putString(key, v).apply();
    }

    public static int longMs(Context c) {
        return clamp(intVal(c, K_LONG_MS, 600), 100, 5000);
    }

    public static int tripleMs(Context c) {
        int v = intVal(c, K_TRIPLE_MS, -1);
        if (v < 0) {
            v = (int) parseLong(sysStr(c, "aikey_triple_ms"), 400);
        }
        return clamp(v, 120, 1000);
    }

    public static int doubleMs(Context c) {
        int v = intVal(c, K_DOUBLE_MS, -1);
        if (v < 0) {
            v = (int) parseLong(sysStr(c, "aikey_double_ms"), 300);
        }
        return clamp(v, 100, 800);
    }

    public static int backDisplay(Context c) {
        return clamp(intVal(c, K_DISPLAY, 1), 1, 16);
    }

    public static int torchLevel(Context c) {
        return clamp(intVal(c, K_TORCH, 150), 1, 255);
    }

    public static String keyCodes(Context c) {
        return strVal(c, K_CODES, "690");
    }

    /** 解析 "690,691" 之类的键码列表，非法项忽略。 */
    public static int[] keyCodeArray(Context c) {
        String raw = keyCodes(c);
        String[] parts = raw.split("[,;\\s]+");
        int n = 0;
        int[] tmp = new int[parts.length];
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            try {
                int v = Integer.parseInt(p.trim());
                if (v >= 3 && v <= 1000) {
                    tmp[n++] = v;
                }
            } catch (Throwable ignored) {
            }
        }
        if (n == 0) {
            return new int[]{690};
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /** 写进 root 动作引擎的 config.conf。值一律加单引号，避免空格/特殊字符破坏 sourcing。 */
    public static String configText(Context c) {
        return "# 由 App 自动生成：改设置请用 App 界面\n"
                + "SINGLE=" + confQuote(action(c, G_SINGLE)) + "\n"
                + "DOUBLE=" + confQuote(action(c, G_DOUBLE)) + "\n"
                + "TRIPLE=" + confQuote(action(c, G_TRIPLE)) + "\n"
                + "TRIPLE_MS=" + tripleMs(c) + "\n"
                + "LONG=" + confQuote(action(c, G_LONG)) + "\n"
                + "LONG_MS=" + longMs(c) + "\n"
                + "DOUBLE_MS=" + doubleMs(c) + "\n"
                + "TORCH_LEVEL=" + torchLevel(c) + "\n"
                + "BACK_DISPLAY=" + backDisplay(c) + "\n"
                + "EV=" + strVal(c, K_EVENT, "") + "\n";
    }

    private static String confQuote(String v) {
        String s = v == null ? "none" : v.replace("\r", " ").replace("\n", " ");
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static void resetDefaults(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        e.putString(K_SINGLE, "flashlight");
        e.putString(K_DOUBLE, "record");
        e.putString(K_LONG, "screenshot");
        e.putString(K_TRIPLE, "none");
        e.putInt(K_LONG_MS, 600);
        e.putInt(K_DOUBLE_MS, 350);
        e.putInt(K_TORCH, 150);
        e.putString(K_CODES, "690");
        e.putBoolean(K_CONSUME, true);
        e.putBoolean(K_PROBE, false);
        e.putBoolean(K_HAPTIC, true);
        e.apply();
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 当前开机计数：用来判断钩子信息是不是本次开机留下的。 */
    public static int bootCount(Context c) {
        try {
            return android.provider.Settings.Global.getInt(
                    c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable t) {
            return -1;
        }
    }
}
