package io.github.jumaoli.aikey.hook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 接管系统的「设置 → AI 键」页。
 *
 * MIUI 那个页面只能选它自己支持的几个 function，我们把入口重定向到本应用的
 * 动作选择界面，这样从系统设置点进来看到的就是全部 80+ 个动作。
 *
 * 作用域需要包含 com.miui.securitycore。
 */
public final class SettingsPageHook {

    private SettingsPageHook() {
    }

    private static final String SYSTEM_ACTIVITY =
            "com.miui.miinput.gesture.aikey.GestureShortcutAiKeySettingsActivity";
    private static final String OUR_PKG = "io.github.jumaoli.aikey";
    private static final String OUR_ACTIVITY = "io.github.jumaoli.aikey.MainActivity";

    /** 用反射找类，找不到就退化成「钩 Activity#onCreate 里按类名匹配」。 */
    public static void install(final ClassLoader cl) {
        try {
            Class<?> target = null;
            try {
                target = Class.forName(SYSTEM_ACTIVITY, false, cl);
            } catch (Throwable ignored) {
            }
            if (target != null) {
                XposedBridge.hookAllMethods(target, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        redirect(param.thisObject);
                    }
                });
                XposedBridge.log("AIKey: 已接管系统 AI 键设置页（" + SYSTEM_ACTIVITY + "）");
                return;
            }
            // 退路：钩所有 Activity 的 onCreate，按类名判断
            Class<?> activity = Class.forName("android.app.Activity", false, cl);
            XposedBridge.hookAllMethods(activity, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object o = param.thisObject;
                    if (o == null) {
                        return;
                    }
                    String name = o.getClass().getName();
                    if (name.contains("GestureShortcutAiKey") || name.endsWith("AiKeySettingsActivity")) {
                        redirect(o);
                    }
                }
            });
            XposedBridge.log("AIKey: 系统 AI 键设置页类没找到，改用 Activity 兜底匹配");
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 接管系统 AI 键设置页失败: " + t);
        }
    }

    private static void redirect(Object activityObj) {
        if (!(activityObj instanceof Activity)) {
            return;
        }
        Activity a = (Activity) activityObj;
        try {
            Context c = a.getApplicationContext();
            String v = Settings.System.getString(c.getContentResolver(), "aikey_takeover_page");
            if ("0".equals(v)) {
                return;
            }
            Intent i = new Intent();
            i.setComponent(new ComponentName(OUR_PKG, OUR_ACTIVITY));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("from_system_ai_key", true);
            a.startActivity(i);
            a.finish();
            XposedBridge.log("AIKey: 已把系统 AI 键设置页重定向到本应用");
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 重定向失败（保留系统页面）: " + t);
        }
    }
}
