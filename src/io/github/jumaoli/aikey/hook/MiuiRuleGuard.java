package io.github.jumaoli.aikey.hook;

import android.provider.Settings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 让系统接受并保留我们注入的 aikey:<id> 选项。
 *
 * MIUI 在 MiAiKeyRule$HandleMiAiKey 里做两件事：
 *  1. AppStatusCheck.needFallbackToNone(function, action) —— 白名单外的值会被重置成 none
 *  2. getClickFunction / getLongPressFunction —— 读设置时把不支持的值也改成 none
 * 这里放行 aikey: 开头的值，否则用户在下拉框里选完，下次进设置页就变回「无」。
 */
public final class MiuiRuleGuard {

    private MiuiRuleGuard() {
    }

    private static final String RULE = "com.android.server.input.shortcut.singlekeyrule.MiAiKeyRule";
    private static final String HANDLE = RULE + "$HandleMiAiKey";
    private static final String STATUS = HANDLE + "$AppStatusCheck";

    public static void install(ClassLoader cl) {
        hookFallbackBlocker(cl);
        hookGetters(cl);
    }

    private static void hookFallbackBlocker(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(STATUS, false, cl);
            XposedBridge.hookAllMethods(c, "needFallbackToNone", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isManaged(param.args)) {
                        param.setResult(false);
                    }
                }
            });
            XposedBridge.log("AIKey: 已放行自定义值（needFallbackToNone）");
        } catch (Throwable t) {
            XposedBridge.log("AIKey: hook needFallbackToNone 失败: " + t);
        }
    }

    private static void hookGetters(ClassLoader cl) {
        try {
            Class<?> handle = Class.forName(HANDLE, false, cl);
            Field click = field(handle, "mClickMiAiKeyFunction");
            Field longPress = field(handle, "mLongPressMiAiKeyFunction");
            hookGetter(handle, "getClickFunction", click);
            hookGetter(handle, "getLongPressFunction", longPress);
        } catch (Throwable t) {
            XposedBridge.log("AIKey: hook AI 键取值失败: " + t);
        }
    }

    private static void hookGetter(Class<?> handle, String method, final Field field) {
        if (field == null) {
            return;
        }
        try {
            Method m = handle.getDeclaredMethod(method);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object raw = field.get(param.thisObject);
                        if (raw instanceof String && isManaged((String) raw)) {
                            param.setResult(raw);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("AIKey: hook " + method + " 失败: " + t);
        }
    }

    private static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isManaged(Object[] args) {
        if (args == null) {
            return false;
        }
        for (Object a : args) {
            if (a instanceof String && isManaged((String) a)) {
                return true;
            }
        }
        return false;
    }

    static boolean isManaged(String v) {
        return v != null && v.startsWith(AiKeySettingsInject.PREFIX);
    }

    /** 把 aikey:<id> 解析成动作 id，不是我们的值就返回 null。 */
    public static String actionOf(String v) {
        if (!isManaged(v)) {
            return null;
        }
        String id = v.substring(AiKeySettingsInject.PREFIX.length());
        return id.isEmpty() ? null : id;
    }

    /** 供 Bridge 读取：系统设置里是否由用户在系统页面选了一个本模块的动作。 */
    public static String readSelection(android.content.Context c, String key) {
        try {
            String v = Settings.System.getString(c.getContentResolver(), key);
            return actionOf(v);
        } catch (Throwable t) {
            return null;
        }
    }
}
