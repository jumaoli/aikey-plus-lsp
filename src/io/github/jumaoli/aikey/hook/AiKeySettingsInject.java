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
            XposedBridge.hookAllMethods(fragment, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        notifySelection(param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log("AIKey: 广播选择失败: " + t);
                    }
                }
            });
            XposedBridge.log("AIKey: 系统 AI 键设置页注入器已安装");
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 找不到系统 AI 键设置页 fragment: " + t);
        }
    }

    /** 页面离开时把两个下拉框的当前值广播给 App（不依赖 MIUI 是否保存）。 */
    private static void notifySelection(Object fragment) throws Exception {
        if (fragment == null) {
            return;
        }
        String click = null;
        String longPress = null;
        for (String name : new String[]{"c", "d"}) {
            try {
                Object preference = fragment.getClass().getField(name).get(fragment);
                if (preference == null) {
                    continue;
                }
                Method getValue = preference.getClass().getMethod("getValue");
                Object v = getValue.invoke(preference);
                if (!(v instanceof String)) {
                    continue;
                }
                if ("c".equals(name)) {
                    click = (String) v;
                } else {
                    longPress = (String) v;
                }
            } catch (Throwable ignored) {
            }
        }
        if (click == null && longPress == null) {
            return;
        }
        android.content.Context ctx = (android.content.Context) fragment.getClass()
                .getMethod("getActivity").invoke(fragment);
        if (ctx == null) {
            return;
        }
        android.content.Intent i = new android.content.Intent("io.github.jumaoli.aikey.SYNC_SELECTION");
        i.setPackage("io.github.jumaoli.aikey");
        i.putExtra("click", click);
        i.putExtra("long", longPress);
        ctx.sendBroadcast(i);
        XposedBridge.log("AIKey: 已广播系统页面选择 click=" + click + " long=" + longPress);
    }

    private static void inject(Object fragment) throws Exception {
        if (fragment == null) {
            return;
        }
        java.util.LinkedHashSet<Object> targets = new java.util.LinkedHashSet<>();

        // 正路：遍历 PreferenceScreen 里的所有 preference
        try {
            Object screen = fragment.getClass().getMethod("getPreferenceScreen").invoke(fragment);
            if (screen != null) {
                Object nObj = callAny(screen, "getPreferenceCount", new Class<?>[0]);
                int n = nObj instanceof Integer ? (Integer) nObj : 0;
                for (int i = 0; i < n; i++) {
                    Object pref = callAny(screen, "getPreference",
                            new Class<?>[]{int.class}, i);
                    if (pref != null && isListLike(pref)) {
                        targets.add(pref);
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 遍历 PreferenceScreen 失败: " + t);
        }

        // 兜底：扫 fragment 的字段
        if (targets.isEmpty()) {
            Class<?> c = fragment.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!f.getType().getName().contains("Preference")) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object pref = f.get(fragment);
                        if (pref != null && isListLike(pref)) {
                            targets.add(pref);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                c = c.getSuperclass();
            }
        }

        int total = 0;
        for (Object pref : targets) {
            try {
                total += appendEntries(pref);
            } catch (Throwable t) {
                XposedBridge.log("AIKey: 注入某个下拉框失败: " + t);
            }
        }
        try {
            updateSummaries(fragment);
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 更新摘要失败: " + t);
        }
        XposedBridge.log("AIKey: 已向系统 AI 键设置页注入 " + total + " 个选项（下拉框 "
                + targets.size() + " 个）");
    }

    /** 页面显示的"当前选择"文字由它自己的映射决定，这里按我们的配置改回来。 */
    private static void updateSummaries(Object fragment) throws Exception {
        Object screen = fragment.getClass().getMethod("getPreferenceScreen").invoke(fragment);
        if (screen == null) {
            return;
        }
        Object nObj = callAny(screen, "getPreferenceCount", new Class<?>[0]);
        int n = nObj instanceof Integer ? (Integer) nObj : 0;

        android.content.Context ctx = (android.content.Context) fragment.getClass()
                .getMethod("getActivity").invoke(fragment);
        if (ctx == null) {
            return;
        }
        android.content.ContentResolver cr = ctx.getContentResolver();
        String click = android.provider.Settings.System.getString(cr, "click_mi_ai_key");
        String lng = android.provider.Settings.System.getString(cr, "long_press_mi_ai_key");

        for (int i = 0; i < n; i++) {
            Object pref = callAny(screen, "getPreference", new Class<?>[]{int.class}, i);
            if (pref == null) {
                continue;
            }
            Object tObj = callAny(pref, "getTitle", new Class<?>[0]);
            String title = tObj == null ? "" : tObj.toString();
            String label = null;
            if (title.contains("短按") || title.contains("单击")) {
                label = labelOf(click);
            } else if (title.contains("长按")) {
                label = labelOf(lng);
            }
            if (label == null) {
                continue;
            }
            try {
                callAny(pref, "setSummary", new Class<?>[]{CharSequence.class}, label);
                XposedBridge.log("AIKey: 摘要已更新 " + title + " -> " + label);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把系统设置里的值翻成动作名。 */
    private static String labelOf(String value) {
        if (value == null || value.isEmpty() || "none".equals(value)) {
            return "无";
        }
        String id = value.startsWith(PREFIX) ? value.substring(PREFIX.length()) : "native:" + value;
        String label = ActionCatalog.label(null, id);
        return label == null ? null : label;
    }

    /** 沿父类链找方法并调用（androidx 的 PreferenceGroup 方法不一定在子类上）。 */
    private static Object callAny(Object obj, String name, Class<?>[] types, Object... args) {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Method m = c.getMethod(name, types);
                return m.invoke(obj, args);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static boolean isListLike(Object pref) {
        String n = pref.getClass().getName();
        return n.contains("ListPreference") || n.contains("DropDown") || n.contains("Dropdown");
    }

    @SuppressWarnings("unchecked")
    private static int appendEntries(Object preference) throws Exception {
        CharSequence[] entries = null;
        CharSequence[] values = null;
        try {
            entries = (CharSequence[]) preference.getClass().getMethod("getEntries").invoke(preference);
            values = (CharSequence[]) preference.getClass().getMethod("getEntryValues").invoke(preference);
        } catch (Throwable ignored) {
        }
        if (entries == null || values == null) {
            try {
                entries = (CharSequence[]) preference.getClass().getField("o").get(preference);
                values = (CharSequence[]) preference.getClass().getField("p").get(preference);
            } catch (Throwable ignored) {
            }
        }
        if (entries == null || values == null) {
            return 0;
        }

        List<CharSequence> newEntries = new ArrayList<>(Arrays.asList(entries));
        List<CharSequence> newValues = new ArrayList<>(Arrays.asList(values));

        int added = 0;
        for (ActionCatalog.Item item : ActionCatalog.items(null)) {
            // 原生动作直接用 MIUI 的 function 名（turn_on_torch 等），
            // 系统页面认得它，才会显示正确的名称；其余用 aikey: 前缀区分。
            String value = item.id.startsWith("native:")
                    ? item.id.substring("native:".length())
                    : PREFIX + item.id;
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

        CharSequence[] eArr = newEntries.toArray(new CharSequence[0]);
        CharSequence[] vArr = newValues.toArray(new CharSequence[0]);
        boolean ok = false;
        try {
            preference.getClass().getMethod("setEntries", CharSequence[].class).invoke(preference, (Object) eArr);
            preference.getClass().getMethod("setEntryValues", CharSequence[].class).invoke(preference, (Object) vArr);
            ok = true;
        } catch (Throwable ignored) {
        }
        if (!ok) {
            Method setEntries = preference.getClass().getMethod("h", CharSequence[].class);
            Method setEntryValues = preference.getClass().getMethod("i", CharSequence[].class);
            setEntries.invoke(preference, (Object) eArr);
            setEntryValues.invoke(preference, (Object) vArr);
        }

        // 注入后重新 setValue 一次：让页面按新的选项表重新解析并刷新显示的文字，
        // 否则摘要/当前值还停在注入前的默认项（表现为"再进来又变回超级小爱"）。
        try {
            Object cur = preference.getClass().getMethod("getValue").invoke(preference);
            if (cur instanceof String) {
                preference.getClass().getMethod("setValue", Object.class)
                        .invoke(preference, cur);
            }
        } catch (Throwable ignored) {
        }
        try {
            preference.getClass().getMethod("notifyChanged").invoke(preference);
        } catch (Throwable ignored) {
        }
        // 页面用的是卡片式显示：摘要文字来自 getSummary()，这里直接按当前值把显示改对
        try {
            Object cur = preference.getClass().getMethod("getValue").invoke(preference);
            if (cur instanceof String) {
                CharSequence label = null;
                for (int i = 0; i < newValues.size(); i++) {
                    if (cur.equals(newValues.get(i).toString())) {
                        label = newEntries.get(i);
                        break;
                    }
                }
                if (label != null) {
                    preference.getClass().getMethod("setSummary", CharSequence.class)
                            .invoke(preference, label);
                }
            }
        } catch (Throwable ignored) {
        }
        return added;
    }
}
