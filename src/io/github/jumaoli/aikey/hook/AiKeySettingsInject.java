package io.github.jumaoli.aikey.hook;

import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import io.github.jumaoli.aikey.ActionCatalog;

/**
 * 往系统「设置 → AI 键」页面的两个下拉框里注入本模块的动作。
 *
 * 系统页面是 com.miui.securitycore 里的
 * com.miui.miinput.gesture.aikey.GestureShortcutAiKeySettingsActivity$a，
 * 两个下拉框是 PreferenceFragment 的字段 c / d，
 * 选项数组在 DropdownPreference 的混淆字段 o（entries）/ p（entryValues）里，
 * 通过混淆方法 h/i 写回。
 *
 * 注入的值统一用 aikey:<动作 id> 前缀，system_server 侧的 MiuiRuleGuard 负责
 * 放行这些值并把它们同步进本模块的配置。
 */
public final class AiKeySettingsInject {

    private AiKeySettingsInject() {
    }

    private static final String FRAGMENT =
            "com.miui.miinput.gesture.aikey.GestureShortcutAiKeySettingsActivity$a";
    public static final String PREFIX = "aikey:";

    public static void install(ClassLoader cl) {
        try {
            Class<?> fragment = Class.forName(FRAGMENT, false, cl);
            XposedBridge.hookAllMethods(fragment, "onCreatePreferences", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        inject(param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log("AIKey: 注入系统 AI 键选项失败: " + t);
                    }
                }
            });
            XposedBridge.log("AIKey: 系统 AI 键设置页注入器已安装");
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 找不到系统 AI 键设置页 fragment: " + t);
        }
    }

    private static void inject(Object fragment) throws Exception {
        if (fragment == null) {
            return;
        }
        int total = 0;
        java.util.LinkedHashSet<Object> targets = new java.util.LinkedHashSet<>();

        // 首选：参考实现里已知的字段名 c / d
        for (String name : new String[]{"c", "d"}) {
            try {
                Field f = fragment.getClass().getField(name);
                Object preference = f.get(fragment);
                if (preference != null) {
                    targets.add(preference);
                }
            } catch (Throwable ignored) {
            }
        }

        // 兜底：扫一遍 fragment 的所有字段，把 DropdownPreference 类的对象都找出来
        if (targets.isEmpty()) {
            Class<?> c = fragment.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    String type = f.getType().getName();
                    if (!type.contains("DropdownPreference") && !type.contains("ListPreference")) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object preference = f.get(fragment);
                        if (preference != null) {
                            targets.add(preference);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                c = c.getSuperclass();
            }
        }

        for (Object preference : targets) {
            try {
                total += appendEntries(preference);
            } catch (Throwable t) {
                XposedBridge.log("AIKey: 注入某个下拉框失败: " + t);
            }
        }
        XposedBridge.log("AIKey: 已向系统 AI 键设置页注入 " + total + " 个选项（下拉框 "
                + targets.size() + " 个）");
    }

    @SuppressWarnings("unchecked")
    private static int appendEntries(Object preference) throws Exception {
        Field entriesField = preference.getClass().getField("o");
        Field valuesField = preference.getClass().getField("p");
        CharSequence[] entries = (CharSequence[]) entriesField.get(preference);
        CharSequence[] values = (CharSequence[]) valuesField.get(preference);
        if (entries == null || values == null) {
            return 0;
        }

        List<CharSequence> newEntries = new ArrayList<>(Arrays.asList(entries));
        List<CharSequence> newValues = new ArrayList<>(Arrays.asList(values));

        int added = 0;
        for (ActionCatalog.Item item : ActionCatalog.items(null)) {
            String value = PREFIX + item.id;
            if (newValues.contains(value)) {
                continue;
            }
            newValues.add(value);
            newEntries.add(item.label);
            added++;
        }
        if (added == 0) {
            return 0;
        }

        Method setEntries = preference.getClass().getMethod("h", CharSequence[].class);
        Method setEntryValues = preference.getClass().getMethod("i", CharSequence[].class);
        setEntries.invoke(preference, (Object) newEntries.toArray(new CharSequence[0]));
        setEntryValues.invoke(preference, (Object) newValues.toArray(new CharSequence[0]));
        return added;
    }
}
