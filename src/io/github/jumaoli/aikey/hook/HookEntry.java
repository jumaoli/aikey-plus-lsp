package io.github.jumaoli.aikey.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** xposed_init 里登记的入口：只在 system_server 里干活。 */
public class HookEntry implements IXposedHookLoadPackage {

    /** attach 阶段 Application 可能还没建好，等主线程空转后再装。 */
    private static void installSubScreen(final int attempt) {
        final android.content.Context ctx = appContext();
        if (ctx != null) {
            SubScreenHook.install(ctx);
            return;
        }
        if (attempt >= 12) {
            XposedBridge.log("AIKey: 背屏进程拿不到 Context，放弃注册");
            return;
        }
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    installSubScreen(attempt + 1);
                }
            }, 800);
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 背屏延迟注册失败: " + t);
        }
    }

    private static android.content.Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                Object app = at.getMethod("getApplication").invoke(thread);
                if (app instanceof android.content.Context) {
                    return (android.content.Context) app;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (lpparam == null) {
                return;
            }
            if ("com.xiaomi.subscreencenter".equals(lpparam.packageName)) {
                installSubScreen(0);
                return;
            }
            if ("com.miui.securitycore".equals(lpparam.packageName)) {
                SettingsPageHook.install(lpparam.classLoader);
                return;
            }
            if (!"android".equals(lpparam.packageName)) {
                return;
            }
            XposedBridge.log("AIKey: loading in " + lpparam.processName);
            KeyHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}
