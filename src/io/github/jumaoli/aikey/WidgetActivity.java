package io.github.jumaoli.aikey;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 背屏小部件宿主页。
 *
 * 用 Activity 而不是 Presentation：这块背屏是系统私有屏，Presentation（Dialog 类型窗口）
 * 挂不上去，但普通 Activity 可以（相机等应用就是用 am start --display 1 过去的）。
 *
 * 启动方式：
 *   am start --display 1 -n io.github.jumaoli.aikey/.WidgetActivity --ei widget_id 794
 * App 内用 ActivityOptions.setLaunchDisplayId 直接拉起，不依赖 root。
 */
public class WidgetActivity extends Activity {

    private AppWidgetHost host;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.util.Log.i("AIKeyApp", "WidgetActivity onCreate display=" + getDisplay());

        int id = getIntent().getIntExtra("widget_id", 0);
        if (id <= 0) {
            id = Prefs.intVal(this, Prefs.K_WIDGET, 0);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        TextView hint = new TextView(this);
        hint.setTextColor(0xFF888888);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setGravity(Gravity.CENTER);

        if (id <= 0) {
            hint.setText("还没有选择小部件\n请在「爽歪了AI键」里选一个");
            root.addView(hint, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(root);
            return;
        }

        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(this);
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
            if (info == null) {
                hint.setText("小部件已失效（id=" + id + "）");
                root.addView(hint, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                host = new AppWidgetHost(this, 0x5757);
                host.startListening();
                AppWidgetHostView view = host.createView(this, id, info);
                view.setAppWidget(id, info);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                int pad = Math.round(8 * getResources().getDisplayMetrics().density);
                root.setPadding(pad, pad, pad, pad);
                root.addView(view, lp);
                Logs.append(this, "背屏小部件页打开: " + info.provider.flattenToShortString());
            }
        } catch (Throwable t) {
            hint.setText("小部件加载失败: " + t);
            root.addView(hint, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        try {
            if (host != null) {
                host.stopListening();
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    /** 从 App 内部在背屏拉起本页。 */
    public static boolean launchOnRear(Activity from, int displayId, int widgetId) {
        try {
            android.content.Intent i = new android.content.Intent(from, WidgetActivity.class);
            i.putExtra("widget_id", widgetId);
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            from.startActivity(i, opts.toBundle());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
