package com.aikeyplus.lsp;

import android.app.Presentation;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 把桌面小部件（AppWidget）画到背屏上。
 *
 * 做法：在背屏（display 1）上开一个 Presentation，里面放 AppWidgetHostView。
 * 这是 Android 官方支持的用法，不需要 hook 背屏 App，也不需要改系统。
 * 绑定小部件需要 BIND_APPWIDGET 权限：root 下用
 *   appwidget grantbind --package com.aikeyplus.lsp --user 0
 * 授权，或者走系统的授权弹窗（ACTION_APPWIDGET_BIND）。
 */
public final class WidgetBoard {

    private static final int HOST_ID = 0x5757;

    private static Presentation sPresentation;
    private static AppWidgetHost sHost;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WidgetBoard() {
    }

    /** 在背屏显示指定小部件。 */
    public static boolean show(final Context c, final int widgetId) {
        if (widgetId <= 0) {
            return false;
        }
        final AppWidgetManager awm = AppWidgetManager.getInstance(c);
        final AppWidgetProviderInfo info = awm.getAppWidgetInfo(widgetId);
        if (info == null) {
            Logs.append(c, "小部件 " + widgetId + " 不存在");
            return false;
        }
        final Display display = rearDisplay(c);
        if (display == null) {
            Logs.append(c, "找不到背屏 display");
            return false;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    hideInternal();
                    sHost = new AppWidgetHost(c, HOST_ID);
                    sHost.startListening();
                    AppWidgetHostView view = sHost.createView(c, widgetId, info);
                    view.setAppWidget(widgetId, info);

                    sPresentation = new Presentation(c, display);
                    FrameLayout root = new FrameLayout(sPresentation.getContext());
                    root.addView(view, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                    sPresentation.setContentView(root);
                    sPresentation.show();
                    Logs.append(c, "背屏小部件已显示: " + info.provider.flattenToShortString());
                } catch (Throwable t) {
                    Logs.append(c, "背屏小部件显示失败: " + t);
                }
            }
        });
        return true;
    }

    public static void hide(final Context c) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                hideInternal();
                Logs.append(c, "背屏小部件已关闭");
            }
        });
    }

    private static void hideInternal() {
        try {
            if (sPresentation != null) {
                sPresentation.dismiss();
            }
        } catch (Throwable ignored) {
        }
        sPresentation = null;
        try {
            if (sHost != null) {
                sHost.stopListening();
                sHost = null;
            }
        } catch (Throwable ignored) {
        }
    }

    public static Display rearDisplay(Context c) {
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            int want = Prefs.backDisplay(c);
            Display fallback = null;
            for (Display d : dm.getDisplays()) {
                if (d.getDisplayId() == want) {
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

    /** 分配一个小部件 id（需要 AppWidgetHost）。 */
    public static int allocate(Context c) {
        try {
            if (sHost == null) {
                sHost = new AppWidgetHost(c, HOST_ID);
            }
            return sHost.allocateAppWidgetId();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 绑定小部件；返回 true 表示已绑定。 */
    public static boolean bind(Context c, AppWidgetProviderInfo info, int widgetId) {
        AppWidgetManager awm = AppWidgetManager.getInstance(c);
        try {
            return awm.bindAppWidgetIdIfAllowed(widgetId, info.provider);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 没选过小部件时，自动绑一个能绑的（优先时钟）。 */
    public static int autoBind(Context c) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(c);
            java.util.List<AppWidgetProviderInfo> list = awm.getInstalledProviders();
            if (list == null) {
                return 0;
            }
            for (AppWidgetProviderInfo info : list) {
                if (info == null || info.provider == null) {
                    continue;
                }
                int id = allocate(c);
                if (id <= 0) {
                    continue;
                }
                if (bind(c, info, id)) {
                    Logs.append(c, "自动绑定小部件: " + info.provider.flattenToShortString());
                    return id;
                }
            }
        } catch (Throwable t) {
            Logs.append(c, "自动绑定小部件失败: " + t);
        }
        return 0;
    }

    /** 申请绑定用的系统弹窗 Intent。 */
    public static android.content.Intent bindIntent(AppWidgetProviderInfo info, int widgetId) {
        android.content.Intent i = new android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
        return i;
    }
}
