package io.github.jumaoli.aikey.hook;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import de.robv.android.xposed.XposedBridge;

/**
 * 在背屏中心（com.xiaomi.subscreencenter）进程里，把小部件直接画到背屏上。
 *
 * 为什么必须在这里画：背屏 display 带 FLAG_OWN_CONTENT_ONLY，只有它自己的内容能显示。
 * 我们在它进程里用 WindowManager 加一个 overlay 窗口，内容就"属于它"，能上屏。
 *
 * 协议：
 *   io.github.jumaoli.aikey.SHOW_WIDGET   extra: provider(String, ComponentName 的扁平形式)
 *   io.github.jumaoli.aikey.HIDE_WIDGET
 */
public final class SubScreenHook {

    public static final String ACTION_SHOW = "io.github.jumaoli.aikey.SHOW_WIDGET";
    public static final String ACTION_HIDE = "io.github.jumaoli.aikey.HIDE_WIDGET";

    private static AppWidgetHost host;
    private static AppWidgetHostView widgetView;
    private static WindowManager windowManager;
    private static int widgetId;

    private SubScreenHook() {
    }

    public static void install(final Context ctx) {
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(ACTION_SHOW);
            f.addAction(ACTION_HIDE);
            final BroadcastReceiver rx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    try {
                        String action = intent == null ? null : intent.getAction();
                        if (ACTION_HIDE.equals(action)) {
                            remove(c);
                            return;
                        }
                        String provider = intent.getStringExtra("provider");
                        show(c, provider);
                    } catch (Throwable t) {
                        XposedBridge.log("AIKey: 背屏小部件失败: " + t);
                    }
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(rx, f);
            }
            XposedBridge.log("AIKey: 背屏小部件接收器已注册");
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 背屏广播注册失败: " + t);
        }
    }

    private static void show(Context ctx, String providerFlat) {
        ComponentName provider = providerFlat == null ? null
                : ComponentName.unflattenFromString(providerFlat);
        if (provider == null) {
            XposedBridge.log("AIKey: 小部件 provider 为空");
            return;
        }
        Display target = display(ctx);
        if (target == null) {
            XposedBridge.log("AIKey: 找不到背屏 display");
            return;
        }
        remove(ctx);

        AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
        if (host == null) {
            host = new AppWidgetHost(ctx, 0x5757);
        }
        host.startListening();
        widgetId = host.allocateAppWidgetId();
        boolean bound = awm.bindAppWidgetIdIfAllowed(widgetId, provider);
        if (!bound) {
            XposedBridge.log("AIKey: 绑定小部件被拒（需要 BIND_APPWIDGET）: " + provider);
            return;
        }
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(widgetId);
        if (info == null) {
            XposedBridge.log("AIKey: 小部件信息为空: " + provider);
            return;
        }
        widgetView = host.createView(ctx, widgetId, info);
        widgetView.setAppWidget(widgetId, info);

        Context displayCtx = ctx.createDisplayContext(target);
        windowManager = (WindowManager) displayCtx.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        lp.setTitle("AIKeyWidget");
        windowManager.addView(widgetView, lp);
        XposedBridge.log("AIKey: 背屏小部件已上屏: " + provider + " display=" + target.getDisplayId());
    }

    private static void remove(Context ctx) {
        try {
            if (windowManager != null && widgetView != null) {
                windowManager.removeView(widgetView);
            }
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 移除背屏小部件失败: " + t);
        }
        widgetView = null;
        windowManager = null;
        try {
            if (host != null && widgetId > 0) {
                host.deleteAppWidgetId(widgetId);
            }
        } catch (Throwable ignored) {
        }
        widgetId = 0;
    }

    private static Display display(Context ctx) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display fallback = null;
            for (Display d : dm.getDisplays()) {
                if (d.getDisplayId() == 1) {
                    return d;
                }
                if (d.getDisplayId() != Display.DEFAULT_DISPLAY && fallback == null) {
                    fallback = d;
                }
            }
            return fallback;
        } catch (Throwable t) {
            return null;
        }
    }
}
