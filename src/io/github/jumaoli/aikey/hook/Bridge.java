package io.github.jumaoli.aikey.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import de.robv.android.xposed.XposedBridge;

/**
 * system_server（钩子）→ App 的桥。全部走 ContentProvider.call，
 * 这样 system_server 不需要读 App 的私有目录，也不用 XSharedPreferences。
 */
final class Bridge {

    static final Uri URI = Uri.parse("content://io.github.jumaoli.aikey.config");

    private static long lastErrLog;

    private Bridge() {
    }

    /** 优先读 Settings.System 里的配置（App 用 root 写进去）。 */
    static HookConfig fetchConfig(Context c) {
        HookConfig fromSettings = readSettings(c);
        if (fromSettings != null) {
            return fromSettings;
        }
        return fetchFromProvider(c);
    }

    private static HookConfig readSettings(Context c) {
        try {
            if (c == null) {
                return null;
            }
            android.content.ContentResolver cr = c.getContentResolver();
            String single = android.provider.Settings.System.getString(cr, "aikey_single");
            String triple = android.provider.Settings.System.getString(cr, "aikey_triple");
            if (single == null && triple == null) {
                return null;
            }
            String dbl = android.provider.Settings.System.getString(cr, "aikey_double");
            String lng = android.provider.Settings.System.getString(cr, "aikey_long");
            long longMs = parseLong(android.provider.Settings.System.getString(cr, "aikey_long_ms"), 600);
            long doubleMs = parseLong(android.provider.Settings.System.getString(cr, "aikey_double_ms"), 350);
            long tripleMs = parseLong(android.provider.Settings.System.getString(cr, "aikey_triple_ms"), 400);
            boolean haptic = !"0".equals(android.provider.Settings.System.getString(cr, "aikey_haptic"));
            String codes = android.provider.Settings.System.getString(cr, "aikey_codes");
            // 系统「设置 → AI 键」页面里选的值优先（等于原值或 none 时忽略）
            String origClick = android.provider.Settings.System
                    .getString(cr, "aikey_orig_click");
            String origLong = android.provider.Settings.System
                    .getString(cr, "aikey_orig_long");
            String sysClick = systemAction(
                    android.provider.Settings.System.getString(cr, "click_mi_ai_key"), origClick);
            if (sysClick != null) {
                single = sysClick;
            }
            String sysLong = systemAction(
                    android.provider.Settings.System.getString(cr, "long_press_mi_ai_key"), origLong);
            if (sysLong != null) {
                lng = sysLong;
            }
            XposedBridge.log("AIKey: 从 Settings 读配置 single=" + single + " triple=" + triple);
            return new HookConfig(true, true, false, haptic,
                    single == null ? "none" : single,
                    dbl == null ? "none" : dbl,
                    triple == null ? "none" : triple,
                    lng == null ? "none" : lng,
                    longMs, doubleMs, tripleMs, parseCodes(codes));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 系统设置值 → 动作 id：aikey:xxx 直接取，原生 function 加 native: 前缀。 */
    private static String systemAction(String value, String original) {
        try {
            if (value == null || value.isEmpty() || "none".equals(value)) {
                return null;
            }
            if (original != null && original.equals(value)) {
                return null;
            }
            if (value.startsWith("aikey:")) {
                String id = value.substring(6);
                return id.isEmpty() ? null : id;
            }
            return "native:" + value;
        } catch (Throwable t) {
            return null;
        }
    }

    private static long parseLong(String v, long def) {
        try {
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static int[] parseCodes(String v) {
        try {
            String[] parts = v.split("[,，\\s]+");
            java.util.ArrayList<Integer> list = new java.util.ArrayList<>();
            for (String p : parts) {
                int n = Integer.parseInt(p.trim());
                if (n > 0) {
                    list.add(n);
                }
            }
            int[] out = new int[list.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = list.get(i);
            }
            return out.length == 0 ? new int[]{690} : out;
        } catch (Throwable t) {
            return new int[]{690};
        }
    }

    static HookConfig fetchFromProvider(Context c) {
        if (c == null) {
            return HookConfig.defaults();
        }
        try {
            Bundle b = c.getContentResolver().call(URI, "config", null, null);
            if (b == null) {
                return HookConfig.defaults();
            }
            return HookConfig.from(b);
        } catch (Throwable t) {
            logError("读配置失败: " + t);
            return HookConfig.defaults();
        }
    }

    static void dispatch(Context c, String gesture) {
        dispatch(c, gesture, null);
    }

    /** action 是钩子已经解析好的动作（可能来自系统页面选择），App 直接执行它。 */
    static void dispatch(Context c, String gesture, String action) {
        if (c == null) {
            return;
        }
        try {
            Bundle extra = new Bundle();
            if (action != null) {
                extra.putString("action", action);
            }
            c.getContentResolver().call(URI, "gesture", gesture, extra);
        } catch (Throwable t) {
            logError("派发手势失败: " + t);
        }
    }

    /** 返回 true 表示 App 收到了心跳（说明 provider 已经可用）。 */
    static boolean hello(Context c, String info) {
        if (c == null) {
            return false;
        }
        try {
            Bundle r = c.getContentResolver().call(URI, "hello", info, null);
            return r != null;
        } catch (Throwable t) {
            logError("上报注入失败: " + t);
            return false;
        }
    }

    static void log(Context c, String msg) {
        if (c == null) {
            XposedBridge.log("AIKey: " + msg);
            return;
        }
        try {
            c.getContentResolver().call(URI, "log", msg, null);
        } catch (Throwable t) {
            XposedBridge.log("AIKey: " + msg);
        }
    }

    private static void logError(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastErrLog < 3000) {
            return;
        }
        lastErrLog = now;
        XposedBridge.log("AIKey: " + msg);
    }
}
