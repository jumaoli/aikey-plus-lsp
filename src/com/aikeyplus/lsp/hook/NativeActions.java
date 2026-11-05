package com.aikeyplus.lsp.hook;

import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;

/**
 * 直接调用 MIUI 的万能动作入口：
 *   com.miui.server.input.util.ShortCutActionsUtils
 *       .getInstance(Context)
 *       .triggerFunction(String function, String action, Bundle, boolean)
 *
 * 这个类在 system_server 里本来就在用（电源键、AI 键、肩键、背部轻敲都走它），
 * 支持的 function 有 60+：付款码、乘车码、扫码、防窥、静音、勿扰、小爱记忆、背屏截图……
 * 而 AI 键自己的 MiAiKeyRule 只白名单了 9 个，所以从钩子里直接调它才能全部解锁。
 *
 * 两个坑（真机验证过）：
 *  1. 类在 /system_ext/framework/miui-services.jar，必须用 system_server 的 ClassLoader
 *     （也就是 LSPosed 给的 lpparam.classLoader），默认 ClassLoader 会 ClassNotFound。
 *  2. 第二个参数是「触发来源」，AI 键这条路径系统自己传的是 "ai_key"，布尔传 true
 *     （见 BaseMiuiPhoneWindowManager 里 launch_voice_assistant 的调用点）。
 */
final class NativeActions {

    private static final String CLASS = "com.miui.server.input.util.ShortCutActionsUtils";
    private static final String SOURCE = "ai_key";

    private static ClassLoader sLoader;
    private static Object sInstance;
    private static Method sTrigger;
    private static boolean sBroken;

    /** 小爱识屏 / 记忆：直接起 VoiceService（MIUI 自己也是这么干的）。 */
    private static boolean xiaoai(Context ctx, String functionKey) {
        try {
            android.content.Intent i = new android.content.Intent("android.intent.action.ASSIST");
            i.putExtra("voice_assist_start_from_key", SOURCE);
            i.putExtra("voice_assist_function_key", functionKey);
            i.setClassName("com.miui.voiceassist", "com.xiaomi.voiceassistant.VoiceService");
            try {
                Method m = Context.class.getMethod("startForegroundServiceAsUser",
                        android.content.Intent.class, android.os.UserHandle.class);
                m.invoke(ctx, i, android.os.Process.myUserHandle());
            } catch (Throwable t) {
                ctx.startForegroundService(i);
            }
            XposedBridge.log("AIKey: 小爱功能键 " + functionKey);
            return true;
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 小爱 " + functionKey + " 失败: " + t);
            return false;
        }
    }

    private NativeActions() {
    }

    static void init(ClassLoader cl) {
        sLoader = cl;
    }

    /** 预热：把首次反射的代价挪到启动线程，别在按键时做。 */
    static void warmup(Context ctx) {
        try {
            prepare(ctx);
            XposedBridge.log("AIKey: ShortCutActionsUtils 预热完成");
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 预热失败: " + t);
        }
    }

    private static boolean prepare(Context ctx) {
        if (sTrigger != null && sInstance != null) {
            return true;
        }
        if (sBroken) {
            return false;
        }
        try {
            Class<?> c;
            try {
                c = Class.forName(CLASS, false, sLoader);
            } catch (Throwable t) {
                // 兜底：直接走系统 ClassLoader
                c = Class.forName(CLASS);
            }
            Method getInstance = c.getMethod("getInstance", Context.class);
            Object inst = getInstance.invoke(null, ctx);
            if (inst == null) {
                XposedBridge.log("AIKey: ShortCutActionsUtils.getInstance 返回 null");
                sBroken = true;
                return false;
            }
            Method m = c.getMethod("triggerFunction", String.class, String.class, Bundle.class,
                    boolean.class);
            sInstance = inst;
            sTrigger = m;
            XposedBridge.log("AIKey: ShortCutActionsUtils 就绪");
            return true;
        } catch (Throwable t) {
            sBroken = true;
            XposedBridge.log("AIKey: ShortCutActionsUtils 不可用: " + t);
            return false;
        }
    }

    /** function 例如 launch_alipay_payment_code / screenshot_rear / mute。 */
    static boolean trigger(final Context ctx, final String function) {
        if (ctx == null || function == null) {
            return false;
        }
        if (function.startsWith("xiaoai:")) {
            return xiaoai(ctx, function.substring(7));
        }
        if ("launch_xiaoai_screen_identification".equals(function)) {
            return xiaoai(ctx, "start_screen_recognition");
        }
        if ("launch_ai_memory".equals(function)) {
            return xiaoai(ctx, "start_ai_memory");
        }
        if ("launch_xiaoai_memorize".equals(function)) {
            return xiaoai(ctx, "start_ai_memory");
        }
        try {
            if (!prepare(ctx)) {
                return false;
            }
            // launch_ai_memory 这类「AI 键专属」功能，系统传的触发来源是设置键名
            String src = ("launch_ai_memory".equals(function) || "launch_voice_note".equals(function))
                    ? "click_mi_ai_key" : SOURCE;
            Object r = sTrigger.invoke(sInstance, function, src, new Bundle(), true);
            boolean ok = r instanceof Boolean && (Boolean) r;
            if (!ok) {
                XposedBridge.log("AIKey: 系统拒绝执行 " + function + " (" + src + ")");
            }
            return ok;
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 执行 " + function + " 失败: " + t);
            return false;
        }
    }
}
