package io.github.jumaoli.aikey;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** MIUI 风格设置页：所有配置都在这里改，改完立刻生效。 */
public class MainActivity extends Activity {

    private static final int BG = 0xFFF7F7F7;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF101418;
    private static final int SUB = 0xFF8A9099;
    private static final int LINE = 0xFFEEF0F3;
    private static final int ACCENT = 0xFF3482FF;
    private static final int ACCENT2 = 0xFF5FA8FF;
    private static final int OK = 0xFF12B76A;
    private static final int WARN = 0xFFF79009;
    private static final int ERR = 0xFFF04438;
    private static final int CHIP_BG = 0xFFF3F4F7;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView hookValue;
    private TextView rootValue;
    private TextView modeValue;
    private TextView heroTitle;
    private TextView heroSub;
    private android.widget.ImageView heroIcon;
    private LinearLayout heroCardView;
    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(ACCENT);
        w.getDecorView().setSystemUiVisibility(0);
        Prefs.hydrateFromSystem(this);
        buildUi();
        refreshStatus();

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (Prefs.boolVal(this, Prefs.K_KEEP_ALIVE, true)) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                }
            } catch (Throwable ignored) {
            }
        }

        Engine e = Engine.get(this);
        e.bg().execute(new Runnable() {
            @Override
            public void run() {
                e.ensureInstalled();
                // 第一次拿到 root 时，顺手把系统自带的 AI 键动作关掉
                if (!Prefs.boolVal(MainActivity.this, "keys_applied", false) && Shell.hasRoot()) {
                    e.applySystemKeysSync();
                    Prefs.setBool(MainActivity.this, "keys_applied", true);
                }
                final Engine.Status s = e.statusSync();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        applyStatus(s);
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildUi();
        if (Prefs.boolVal(this, Prefs.K_KEEP_ALIVE, true)) {
            startKeepAlive();
        }
        refreshStatus();
    }

    // ------------------------------------------------------------------ UI 骨架

    private FrameLayout tabContent;
    private LinearLayout bottomBar;
    private int currentTab = 0;

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        tabContent = new FrameLayout(this);
        root.addView(tabContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xF7FFFFFF);
        bottomBar.setBackground(barBg);
        bottomBar.setElevation(dp(6));
        bottomBar.setPadding(0, dp(4), 0, dp(4));
        bottomBar.addView(tabButton(0, "设置"), weight1());
        bottomBar.addView(tabButton(1, "背屏"), weight1());
        root.addView(bottomBar);

        setContentView(root);
        showTab(currentTab);
    }

    private View tabButton(final int index, String label) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(10));
        t.setClickable(true);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(index == 0 ? R.drawable.ic_gear : R.drawable.ic_phone);
        iv.setTag("icon");
        t.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setTag("label");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(4);
        t.addView(tv, tp);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentTab = index;
                showTab(index);
            }
        });
        return t;
    }

    private void showTab(int index) {
        if (tabContent == null) {
            return;
        }
        tabContent.removeAllViews();
        View page = index == 0 ? settingsPage() : rearPage();
        tabContent.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < bottomBar.getChildCount(); i++) {
            LinearLayout t = (LinearLayout) bottomBar.getChildAt(i);
            android.widget.ImageView iv = (android.widget.ImageView) t.findViewWithTag("icon");
            TextView tv = (TextView) t.findViewWithTag("label");
            boolean on = i == index;
            int color = on ? ACCENT : 0xFF8A9099;
            if (iv != null) {
                iv.setColorFilter(color);
            }
            if (tv != null) {
                tv.setTextColor(color);
                tv.setTypeface(null, on ? android.graphics.Typeface.BOLD
                        : android.graphics.Typeface.NORMAL);
            }
            GradientDrawable g = new GradientDrawable();
            g.setColor(on ? 0xFFEBF2FF : 0x00000000);
            g.setCornerRadius(dp(18));
            t.setBackground(g);
        }
    }

    private View settingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(0, 0, 0, dp(28));

        page.addView(header());
        page.addView(heroCard());
        page.addView(statusCard());
        page.addView(gestureCard());
        page.addView(advancedCard());
        page.addView(actionCard());
        page.addView(creditsCard());
        page.addView(helpCard());

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(BG);
        sv.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    // ------------------------------------------------------------------ 背屏页

    private View rearPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(0, 0, 0, dp(28));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackgroundColor(Color.WHITE);
        head.setPadding(dp(24), dp(36), dp(24), dp(22));
        head.addView(text("背屏", 34, TEXT, true));
        TextView sub = text("把应用 / 小部件放到背屏（display " + Prefs.backDisplay(this) + "）", 14,
                SUB, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(6);
        head.addView(sub, sp);
        page.addView(head);

        LinearLayout card1 = card();
        card1.addView(cardTitle("背屏操作"));
        card1.addView(row("选择应用上背屏", "点一下挑应用", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickAppForRear();
            }
        }));
        card1.addView(divider());
        card1.addView(row("背屏小部件", currentWidgetLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickWidgetForRear();
            }
        }));
        card1.addView(divider());
        card1.addView(row("显示 / 关闭小部件", "点一下切换", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRearWidget();
            }
        }));
        card1.addView(divider());
        card1.addView(row("回到背屏桌面", "关掉背屏上的应用", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runActionNow("backhome", "已回到背屏桌面");
            }
        }));
        card1.addView(divider());
        card1.addView(row("背屏截图", "截当前背屏画面", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runActionNow("native:screenshot_rear", "已截图");
            }
        }));
        page.addView(card1);


        LinearLayout note = card();
        note.addView(cardTitle("说明"));
        TextView t = text("背屏是系统的第二块屏幕（display 1）。支持多屏的 Activity 可以直接上；"
                + "不支持的应用会被系统挪回主屏。\n\n小部件通过背屏中心进程挂载，需要 root 授权 "
                + "appwidget grantbind（动作会自动执行）。", 12, SUB, false);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(dp(16), dp(2), dp(16), dp(12));
        note.addView(t);
        page.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(BG);
        sv.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private String currentWidgetLabel() {
        String p = Prefs.strVal(this, Prefs.K_WIDGET_PROVIDER, "");
        if (p == null || p.isEmpty()) {
            return "未选择";
        }
        int slash = p.indexOf('/');
        return slash > 0 ? p.substring(0, slash) : p;
    }

    private void runActionNow(final String action, final String okMsg) {
        final Context c = this;
        toast("执行中…");
        Engine.get(c).bg().execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Engine.get(c).runActionSync(action);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(ok ? okMsg : "失败，看日志");
                    }
                });
            }
        });
    }

    private void pickWidgetForRear() {
        final java.util.List<android.appwidget.AppWidgetProviderInfo> list;
        try {
            list = android.appwidget.AppWidgetManager.getInstance(this).getInstalledProviders();
        } catch (Throwable t) {
            toast("读取小部件失败");
            return;
        }
        if (list == null || list.isEmpty()) {
            toast("没有可用的小部件");
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            android.appwidget.AppWidgetProviderInfo info = list.get(i);
            String pkg = info.provider != null ? info.provider.getPackageName() : "?";
            String name = ActionCatalog.appLabel(this, pkg);
            labels[i] = (name != null ? name : pkg) + "\n" + pkg;
        }
        new AlertDialog.Builder(this)
                .setTitle("背屏小部件")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        android.appwidget.AppWidgetProviderInfo info = list.get(which);
                        Prefs.setStr(MainActivity.this, Prefs.K_WIDGET_PROVIDER,
                                info.provider.flattenToString());
                        Prefs.setInt(MainActivity.this, Prefs.K_WIDGET, 1);
                        showTab(currentTab);
                        toast("已选择，点「显示 / 关闭小部件」上屏");
                    }
                })
                .show();
    }

    private void toggleRearWidget() {
        if (!rearWidgetShown) {
            rearWidgetShown = true;
            runActionNow("widget", "小部件已上背屏");
        } else {
            rearWidgetShown = false;
            runActionNow("widget_off", "小部件已关闭");
        }
    }

    private boolean rearWidgetShown = false;

    private void pickAppForRear() {
        final java.util.List<String[]> apps = ActionCatalog.launchableApps(this);
        if (apps.isEmpty()) {
            toast("没有找到可启动的应用");
            return;
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i)[0] + "\n" + apps.get(i)[1];
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要放到背屏的应用")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pkg = apps.get(which)[1];
                        String act = launcherOf(pkg);
                        runActionNow("appback:" + pkg + "/" + act, "已发到背屏");
                    }
                })
                .show();
    }

    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setBackgroundColor(Color.WHITE);
        h.setPadding(dp(24), dp(34), dp(20), dp(20));
        h.setGravity(Gravity.TOP);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text("AI 键", 34, TEXT, true));
        TextView sub = text("爽歪了AI键 · v" + BuildConfig.VERSION_NAME
                + " · 单击 / 双击 / 三击 / 长按", 14, SUB, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(6);
        col.addView(sub, sp);
        h.addView(col, weight1());

        android.widget.ImageView power = new android.widget.ImageView(this);
        power.setImageResource(R.drawable.ic_power);
        power.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent().setClassName("com.miui.securitycore",
                            "com.miui.miinput.gesture.aikey.GestureShortcutAiKeySettingsActivity"));
                } catch (Throwable t) {
                    toast("打不开系统 AI 键设置");
                }
            }
        });
        h.addView(power, new LinearLayout.LayoutParams(dp(26), dp(26)));
        return h;
    }

    /** 顶部大状态卡：绿底=工作中，灰底=未就绪。 */
    private View heroCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFE7F6EA);
        g.setCornerRadius(dp(24));
        box.setBackground(g);
        box.setPadding(dp(22), dp(20), dp(18), dp(20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), 0, dp(16), dp(12));
        box.setLayoutParams(lp);
        heroCardView = box;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        heroTitle = text("检测中", 26, TEXT, true);
        heroSub = text("正在检查钩子与 root", 14, 0xFF5B6B60, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(4);
        col.addView(heroTitle);
        col.addView(heroSub, sp);
        box.addView(col, weight1());

        heroIcon = new android.widget.ImageView(this);
        heroIcon.setImageResource(R.drawable.ic_check);
        heroIcon.setAlpha(0.85f);
        box.addView(heroIcon, new LinearLayout.LayoutParams(dp(72), dp(72)));
        return box;
    }

    private View statusCard() {
        LinearLayout card = card();
        card.addView(cardTitle("运行状态"));

        LinearLayout r1 = statusRow(iconFor("LSPosed 钩子"), "LSPosed 钩子",
                hookValue = chip("检测中", SUB));
        card.addView(r1);

        card.addView(divider());
        LinearLayout r2 = statusRow(iconFor("Root 权限"), "Root 权限",
                rootValue = chip("检测中", SUB));
        card.addView(r2);

        hint = text("", 12, SUB, false);
        hint.setPadding(dp(16), dp(10), dp(16), dp(14));
        card.addView(hint);
        return card;
    }

    private View gestureCard() {
        LinearLayout card = card();
        card.addView(cardTitle("手势动作"));
        card.addView(row("单击", ActionCatalog.label(this, Prefs.action(this, Prefs.G_SINGLE)),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickAction(Prefs.G_SINGLE);
                    }
                }));
        card.addView(divider());
        card.addView(row("双击", ActionCatalog.label(this, Prefs.action(this, Prefs.G_DOUBLE)),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickAction(Prefs.G_DOUBLE);
                    }
                }));
        card.addView(divider());
        card.addView(row("三击", ActionCatalog.label(this, Prefs.action(this, Prefs.G_TRIPLE)),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickAction(Prefs.G_TRIPLE);
                    }
                }));
        card.addView(divider());
        card.addView(row("长按", ActionCatalog.label(this, Prefs.action(this, Prefs.G_LONG)),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickAction(Prefs.G_LONG);
                    }
                }));
        return card;
    }

    private View advancedCard() {
        LinearLayout card = card();
        card.addView(cardTitle("参数"));

        card.addView(row("长按判定", Prefs.longMs(this) + " ms", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptInt("长按判定阈值（毫秒）", Prefs.K_LONG_MS, Prefs.longMs(MainActivity.this),
                        100, 5000, "按住超过这个时长算长按。");
            }
        }));
        card.addView(divider());
        card.addView(row("双击判定窗口", Prefs.doubleMs(this) + " ms", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptInt("双击判定窗口（毫秒）", Prefs.K_DOUBLE_MS, Prefs.doubleMs(MainActivity.this),
                        100, 800, "两次按下之间超过这个间隔就算两次单击。\n双击设成「无」时没有等待，单击立即响应。");
            }
        }));
        card.addView(divider());
        card.addView(row("手电筒亮度", String.valueOf(Prefs.torchLevel(this)),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        promptInt("手电筒亮度（1-255）", Prefs.K_TORCH, Prefs.torchLevel(MainActivity.this),
                                1, 255, "写 /sys/class/leds/*flash*/brightness 的值。");
                    }
                }));
        card.addView(divider());
        card.addView(row("AI 键键码", Prefs.keyCodes(this), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptKeyCodes();
            }
        }));
        card.addView(divider());
        card.addView(row("系统 AI 键设置", "打开 MIUI 原生页面", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openNativeSettings();
            }
        }));
        card.addView(divider());
        card.addView(row("背屏 display id", String.valueOf(Prefs.backDisplay(this)),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        promptInt("背屏 display id", Prefs.K_DISPLAY,
                                Prefs.backDisplay(MainActivity.this), 1, 16,
                                "am start --display N 用的就是这个值。先点「自动探测背屏」试试。");
                    }
                }));
        card.addView(divider());
        card.addView(row("背屏小部件", widgetLabel(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickWidget();
            }
        }));
        card.addView(divider());
        card.addView(row("自动探测背屏", "从 dumpsys display 里找", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoDetectDisplay();
            }
        }));
        card.addView(divider());
        card.addView(switchRow("接管系统 AI 键设置页", "设置 → AI 键 直接打开本页面（需把 com.miui.securitycore 加进作用域）",
                Prefs.K_TAKEOVER, true, new SwitchListener() {
                    @Override
                    public void onChanged(boolean checked) {
                        Engine.get(MainActivity.this).writeConfig();
                    }
                }));
        card.addView(divider());
        card.addView(switchRow("独占 AI 键", "吞掉按键事件，系统/应用都收不到", Prefs.K_CONSUME, true,
                new SwitchListener() {
                    @Override
                    public void onChanged(boolean checked) {
                        Engine.get(MainActivity.this).writeConfig();
                    }
                }));
        card.addView(divider());
        card.addView(switchRow("按键探测", "把经过框架的按键码写进日志，用来确认 AI 键键码",
                Prefs.K_PROBE, false, null));
        card.addView(divider());
        card.addView(switchRow("震动反馈", "执行动作时轻微震动（单击一下 / 双击两下 / 长按一下长的）",
                Prefs.K_HAPTIC, true, null));
        card.addView(divider());
        card.addView(switchRow("常驻后台", "前台服务保活，动作响应更快", Prefs.K_KEEP_ALIVE, true,
                new SwitchListener() {
                    @Override
                    public void onChanged(boolean checked) {
                        if (checked) {
                            startKeepAlive();
                        } else {
                            stopService(new Intent(MainActivity.this, KeepAliveService.class));
                        }
                    }
                }));
        return card;
    }

    private View actionCard() {
        LinearLayout card = card();
        card.addView(cardTitle("操作"));
        card.addView(buttonRow(
                button("立即应用", true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        applyAll();
                    }
                }),
                button("测试动作", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        testAction();
                    }
                })));
        card.addView(buttonRow(
                button("查看日志", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showLogs();
                    }
                }),
                button("复制诊断", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copyDiagnostics();
                    }
                })));
        card.addView(buttonRow(
                button("恢复默认", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        resetDefaults();
                    }
                }),
                button("还原系统 AI 键", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        restoreSystemKey();
                    }
                })));
        return card;
    }

    private View helpCard() {
        LinearLayout card = card();
        card.addView(cardTitle("说明 / 排错"));
        TextView t = text(
                "两种模式：\n"
                        + "· LSPosed 钩子：钩子在 system_server 里拦 AI 键，不需要常驻 getevent，亮屏灭屏都能用。\n"
                        + ""
                        + "动作由 root shell 执行：手电筒写 LED 节点、截图注入「音量下+电源」、录屏调系统录屏的 AppFunction。"
                        + "所以首次使用请给本应用 root 权限。\n\n"
                        + "按键没反应？\n"
                        + "1. 打开「按键探测」，按一下 AI 键，看日志里的 keyCode 是否等于上面配置的值；\n"
                        + "2. LSPosed 里确认本模块已启用、作用域已勾选「系统框架」，并且重启过手机；\n"
                        + "3. 还不行就看日志里的 hooks= 数量。\n\n"
                        + "原创：KernelSU 模块 aikey_plus v1.1.1（NekoWannn）；本 APK 是它的 LSPosed 移植版。",
                12, SUB, false);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(dp(16), dp(4), dp(16), dp(16));
        card.addView(t);
        return card;
    }

    // ------------------------------------------------------------------ 状态

    private void refreshStatus() {
        final Context c = this;
        Engine.get(this).bg().execute(new Runnable() {
            @Override
            public void run() {
                final Engine.Status s = Engine.get(c).statusSync();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        applyStatus(s);
                    }
                });
            }
        });
    }

    private void applyStatus(Engine.Status s) {
        if (s == null) {
            return;
        }
        if (hookValue != null) {
            if (s.hookThisBoot) {
                setChip(hookValue, "已注入", OK);
            } else {
                setChip(hookValue, "未注入", ERR);
            }
        }
        if (rootValue != null) {
            if (s.root == 1) {
                setChip(rootValue, "已授权", OK);
            } else if (s.root == -1) {
                setChip(rootValue, "未授权", ERR);
            } else {
                setChip(rootValue, "检测中", SUB);
            }
        }
        boolean working = s.root == 1 && s.hookThisBoot;
        if (heroTitle != null) {
            heroTitle.setText(working ? "工作中" : "未就绪");
        }
        if (heroSub != null) {
            heroSub.setText("版本 v" + BuildConfig.VERSION_NAME + " · LSPosed 钩子"
                    + (working ? " · 已生效" : " · 未生效"));
        }
        if (heroIcon != null) {
            heroIcon.setImageResource(working ? R.drawable.ic_check : R.drawable.ic_shield);
            heroIcon.setAlpha(working ? 0.9f : 0.35f);
        }
        if (heroCardView != null) {
            GradientDrawable hg = new GradientDrawable();
            hg.setColor(working ? 0xFFE7F6EA : 0xFFF1F1F3);
            hg.setCornerRadius(dp(24));
            heroCardView.setBackground(hg);
        }
        if (hint != null) {
            String h;
            if (s.root != 1) {
                h = "尚未获得 root：动作执行不了。请在 KernelSU / Magisk 里允许本应用使用 root。";
                hint.setTextColor(ERR);
            } else if (!s.hookThisBoot) {
                h = "本次开机没收到钩子心跳：请在 LSPosed 里启用模块、勾选「系统框架」并重启。";
                hint.setTextColor(WARN);
            } else if (s.hookInfo != null && s.hookInfo.startsWith("hooks=0")) {
                h = "钩子已注入，但这台机器的 PhoneWindowManager 里没找到按键方法。";
                hint.setTextColor(WARN);
            } else {
                h = "钩子已就绪（" + s.hookInfo + "）。按一下 AI 键试试。";
                hint.setTextColor(SUB);
            }
            hint.setText(h);
        }
    }

    private String modeLabel() {
        return "LSPosed 钩子";
    }

    // ------------------------------------------------------------------ 交互

    private void pickAction(final String gesture) {
        final java.util.List<String> groups = ActionCatalog.groups();
        new AlertDialog.Builder(this)
                .setTitle("选择动作分类")
                .setItems(groups.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pickActionIn(gesture, groups.get(which));
                    }
                })
                .show();
    }

    private void pickActionIn(final String gesture, String group) {
        final java.util.List<ActionCatalog.Item> items = ActionCatalog.items(group);
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle(group)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String id = items.get(which).id;
                        if ("app:".equals(id)) {
                            pickApp(gesture, "app:");
                        } else if ("appback:".equals(id)) {
                            pickApp(gesture, "appback:");
                        } else if ("shell:".equals(id)) {
                            promptShell(gesture);
                        } else {
                            setGesture(gesture, id);
                        }
                    }
                })
                .show();
    }

    private void pickApp(final String gesture, final String prefix) {
        final List<String[]> apps = ActionCatalog.launchableApps(this);
        if (apps.isEmpty()) {
            toast("没有找到可启动的应用");
            return;
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i)[0] + "\n" + apps.get(i)[1];
        }
        new AlertDialog.Builder(this)
                .setTitle("appback:".equals(prefix) ? "选择要放到背屏的应用" : "选择应用")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pkg = apps.get(which)[1];
                        if ("appback:".equals(prefix)) {
                            setGesture(gesture, "appback:" + pkg + "/" + launcherOf(pkg));
                        } else {
                            setGesture(gesture, "app:" + pkg);
                        }
                    }
                })
                .show();
    }

    /** 解析桌面入口 Activity，背屏启动要用 "包名/Activity"。 */
    private String launcherOf(String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(pkg);
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(i, 0);
            if (ri != null && ri.activityInfo != null) {
                return ri.activityInfo.name;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void promptShell(final String gesture) {
        String cur = Prefs.action(this, gesture);
        String def = cur != null && cur.startsWith("shell:") ? cur.substring(6) : "";
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(def);
        et.setHint("例如 am start -a android.intent.action.VIEW -d https://example.com");
        et.setSelection(et.getText().length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("自定义 shell 命令（root 执行）")
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String cmd = et.getText().toString().trim();
                        setGesture(gesture, cmd.isEmpty() ? "none" : "shell:" + cmd);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptKeyCodes() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(Prefs.keyCodes(this));
        et.setHint("690");
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("AI 键键码（可填多个，逗号分隔）")
                .setMessage("默认 690。不确定就先开「按键探测」，按一下 AI 键，在日志里看 keyCode。")
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String raw = et.getText().toString().trim();
                        if (raw.isEmpty()) {
                            raw = "690";
                        }
                        boolean any = false;
                        for (String p : raw.split("[,;\\s]+")) {
                            try {
                                int v = Integer.parseInt(p.trim());
                                if (v >= 3 && v <= 1000) {
                                    any = true;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        if (!any) {
                            toast("键码不合法");
                            return;
                        }
                        Prefs.setStr(MainActivity.this, Prefs.K_CODES, raw);
                        toast("已保存，立即生效");
                        buildUi();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptInt(String title, final String key, int def, final int min, final int max,
                           String message) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(def));
        et.setSelection(et.getText().length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(et);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int v;
                        try {
                            v = Integer.parseInt(et.getText().toString().trim());
                        } catch (Throwable t) {
                            toast("请输入数字");
                            return;
                        }
                        v = Prefs.clamp(v, min, max);
                        Prefs.setInt(MainActivity.this, key, v);
                        Engine.get(MainActivity.this).writeConfig();
                        buildUi();
                        refreshStatus();
                    }
                })
                .setNegativeButton("取消", null);
        if (message != null) {
            b.setMessage(message);
        }
        b.show();
    }

    private void setGesture(String gesture, String action) {
        Prefs.setAction(this, gesture, action);
        Engine.get(this).writeConfig();
        Logs.append(this, "设置 " + gesture + " = " + action);
        buildUi();
        refreshStatus();
    }

    private void applyAll() {
        final Context c = this;
        toast("正在应用…");
        Engine.get(this).bg().execute(new Runnable() {
            @Override
            public void run() {
                final String msg = Engine.get(c).applyAllSync();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(msg);
                        buildUi();
                        refreshStatus();
                    }
                });
            }
        });
    }

    private void testAction() {
        final java.util.List<String> groups = ActionCatalog.groups();
        new AlertDialog.Builder(this)
                .setTitle("试跑：选分类")
                .setItems(groups.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final java.util.List<ActionCatalog.Item> items =
                                ActionCatalog.items(groups.get(which));
                        String[] labels = new String[items.size()];
                        for (int i = 0; i < items.size(); i++) {
                            labels[i] = items.get(i).label;
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("试跑")
                                .setItems(labels, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d2, int w2) {
                                        String id = items.get(w2).id;
                                        if ("app:".equals(id) || "shell:".equals(id)) {
                                            toast("这类动作请在手势里配置");
                                            return;
                                        }
                                        runActionAsync(id);
                                    }
                                })
                                .show();
                    }
                })
                .show();
    }

    private void runActionAsync(final String act) {
        final Context c = this;
        Engine.get(c).bg().execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Engine.get(c).runActionSync(act);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(ok ? "已执行：" + act : "执行失败，看日志");
                    }
                });
            }
        });
    }
    private void showLogs() {
        final Context c = this;
        Engine.get(this).bg().execute(new Runnable() {
            @Override
            public void run() {
                final String text = Engine.get(c).readLogsSync(200);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        TextView tv = text(text, 11, TEXT, false);
                        tv.setTypeface(Typeface.MONOSPACE);
                        tv.setTextIsSelectable(true);
                        ScrollView sv = new ScrollView(MainActivity.this);
                        sv.addView(tv);
                        FrameLayout box = new FrameLayout(MainActivity.this);
                        int p = dp(16);
                        box.setPadding(p, p, p, p);
                        box.addView(sv);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("运行日志")
                                .setView(box)
                                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        copy(text);
                                    }
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    }
                });
            }
        });
    }

    private void copyDiagnostics() {
        final Context c = this;
        Engine.get(this).bg().execute(new Runnable() {
            @Override
            public void run() {
                Engine e = Engine.get(c);
                Engine.Status s = e.statusSync();
                StringBuilder sb = new StringBuilder();
                sb.append("AI 键多功能 ").append(BuildConfig.VERSION_NAME).append(" (")
                        .append(BuildConfig.BUILD_NAME).append(")\n");
                sb.append("机型: ").append(Build.MODEL).append(" / ").append(Build.DEVICE)
                        .append(" / Android ").append(Build.VERSION.RELEASE)
                        .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
                sb.append("模式: ").append(s.mode).append("  钩子本次开机: ").append(s.hookThisBoot)
                        .append("  root: ").append(s.root)
                        .append("\n钩子信息: ").append(s.hookInfo).append('\n');
                sb.append("键码: ").append(Prefs.keyCodes(c)).append('\n');
                sb.append("单击=").append(Prefs.action(c, Prefs.G_SINGLE))
                        .append(" 双击=").append(Prefs.action(c, Prefs.G_DOUBLE))
                        .append(" 长按=").append(Prefs.action(c, Prefs.G_LONG)).append('\n');
                sb.append("LONG_MS=").append(Prefs.longMs(c))
                        .append(" DOUBLE_MS=").append(Prefs.doubleMs(c))
                        .append(" TORCH=").append(Prefs.torchLevel(c)).append('\n');
                sb.append("\n--- 日志 ---\n").append(e.readLogsSync(80));
                final String out = sb.toString();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        copy(out);
                        toast("诊断信息已复制");
                    }
                });
            }
        });
    }

    private void resetDefaults() {
        new AlertDialog.Builder(this)
                .setTitle("恢复默认配置")
                .setMessage("单击=手电筒，双击=录屏，长按=截图。")
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.resetDefaults(MainActivity.this);
                        Engine.get(MainActivity.this).writeConfig();
                        buildUi();
                        refreshStatus();
                        toast("已恢复默认");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void restoreSystemKey() {
        new AlertDialog.Builder(this)
                .setTitle("还原系统 AI 键")
                .setMessage("把 AI 键动作还原成模块接管之前的值（第一次「立即应用」时已经记录）；"
                        + "没有记录时回退成小爱记忆 / 语音便签。")
                .setPositiveButton("还原", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final Context c = MainActivity.this;
                        Engine.get(c).bg().execute(new Runnable() {
                            @Override
                            public void run() {
                                Engine.get(c).restoreSystemKeysSync();
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        toast("已还原");
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String widgetLabel() {
        int id = Prefs.intVal(this, Prefs.K_WIDGET, 0);
        if (id <= 0) {
            return "未选择";
        }
        try {
            android.appwidget.AppWidgetProviderInfo info =
                    android.appwidget.AppWidgetManager.getInstance(this).getAppWidgetInfo(id);
            if (info != null && info.provider != null) {
                return info.provider.getPackageName();
            }
        } catch (Throwable ignored) {
        }
        return "已选择";
    }

    private void pickWidget() {
        final java.util.List<android.appwidget.AppWidgetProviderInfo> list;
        try {
            list = android.appwidget.AppWidgetManager.getInstance(this).getInstalledProviders();
        } catch (Throwable t) {
            toast("读取小部件失败");
            return;
        }
        if (list == null || list.isEmpty()) {
            toast("没有可用的小部件");
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            android.appwidget.AppWidgetProviderInfo info = list.get(i);
            String pkg = info.provider != null ? info.provider.getPackageName() : "?";
            String name = ActionCatalog.appLabel(this, pkg);
            labels[i] = (name != null ? name : pkg) + "\n" + pkg;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要放到背屏的小部件")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        bindWidget(list.get(which));
                    }
                })
                .show();
    }

    private void bindWidget(final android.appwidget.AppWidgetProviderInfo info) {
        if (info != null && info.provider != null) {
            Prefs.setStr(this, Prefs.K_WIDGET_PROVIDER, info.provider.flattenToString());
            Prefs.setInt(this, Prefs.K_WIDGET, 1);
            buildUi();
            toast("已选择，手势动作选「背屏：显示小部件」即可");
            return;
        }
        final int id = WidgetBoard.allocate(this);
        if (id <= 0) {
            toast("分配小部件 id 失败");
            return;
        }
        if (WidgetBoard.bind(this, info, id)) {
            Prefs.setInt(this, Prefs.K_WIDGET, id);
            buildUi();
            toast("已绑定，长按/手势选「背屏：显示小部件」即可");
            return;
        }
        // 没有 BIND_APPWIDGET：走系统授权弹窗；也可以 root 下 appwidget grantbind
        try {
            startActivityForResult(WidgetBoard.bindIntent(info, id), 1001);
        } catch (Throwable t) {
            toast("无法申请绑定权限，请用 root 执行 appwidget grantbind");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            int id = data.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
            if (id > 0) {
                Prefs.setInt(this, Prefs.K_WIDGET, id);
                buildUi();
                toast("小部件已绑定");
            }
        }
    }

    private void autoDetectDisplay() {
        toast("正在探测…");
        final Context c = this;
        Engine.get(c).bg().execute(new Runnable() {
            @Override
            public void run() {
                final String id = Engine.get(c).detectBackDisplaySync();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id == null) {
                            toast("没认出来，先按默认 " + Prefs.backDisplay(c) + " 试");
                        } else {
                            Prefs.setInt(c, Prefs.K_DISPLAY, Integer.parseInt(id));
                            Engine.get(c).writeCommands();
                            buildUi();
                            toast("背屏 display id = " + id);
                        }
                    }
                });
            }
        });
    }

    private View creditsCard() {
        LinearLayout card = card();
        card.addView(cardTitle("作者"));
        card.addView(row("酷安 @小鞠", "coolapk.com/u/3576884", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl("https://www.coolapk.com/u/3576884");
            }
        }));
        card.addView(divider());
        card.addView(cardTitle("致谢"));
        TextView t = text("以下排名不分先后：\n"
                + "· 婉彬（酷安）\n"
                + "· NekoWannn — 按键逻辑与动作实现参考 aikey_plus\n"
                + "· Cpek0（乐乐背屏）— 背屏启动方式参考\n"
                + "· LSPosed / Shizuku / Stellar — 运行环境",
                12, SUB, false);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(dp(16), dp(2), dp(16), dp(8));
        card.addView(t);
        card.addView(row("酷安 @Cpek0（乐乐背屏）", "coolapk.com/u/14823887", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl("https://www.coolapk.com/u/14823887");
            }
        }));
        card.addView(divider());
        card.addView(row("酷安 @婉彬", "coolapk.com/u/7789464", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl("https://www.coolapk.com/u/7789464");
            }
        }));
        card.addView(divider());
        TextView tip = text("赞赏码（微信 / 支付宝扫一扫）：", 11, SUB, false);
        tip.setPadding(dp(16), dp(10), dp(16), dp(4));
        card.addView(tip);
        android.widget.ImageView qr = new android.widget.ImageView(this);
        qr.setImageResource(R.drawable.reward_qr);
        qr.setAdjustViewBounds(true);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(170), dp(170));
        qp.leftMargin = dp(16);
        qp.bottomMargin = dp(14);
        card.addView(qr, qp);
        return card;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Throwable t) {
            toast("打不开浏览器");
        }
    }

    private void openNativeSettings() {
        final Context c = this;
        Engine.get(this).bg().execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Engine.get(c).openNativeAiKeySettingsSync();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            toast("没找到系统 AI 键设置页（可能不是 HyperOS）");
                        }
                    }
                });
            }
        });
    }

    private void startKeepAlive() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(new Intent(this, KeepAliveService.class));
            } else {
                startService(new Intent(this, KeepAliveService.class));
            }
        } catch (Throwable t) {
            Logs.append(this, "启动常驻服务失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 小工具

    private interface SwitchListener {
        void onChanged(boolean checked);
    }

    private View switchRow(String title, String sub, final String key, boolean def,
                           final SwitchListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setMinimumHeight(dp(64));

        int icon = iconFor(title);
        if (icon != 0) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(icon);
            iv.setColorFilter(0xFF1A1A1A);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(24), dp(24));
            blp.rightMargin = dp(14);
            row.addView(iv, blp);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 17, TEXT, false));
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(sub, 12, SUB, false);
            s.setPadding(0, dp(2), dp(8), 0);
            texts.addView(s);
        }
        row.addView(texts, weight1());

        Switch sw = new Switch(this);
        sw.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{ {android.R.attr.state_checked}, {} },
                new int[]{0xFF3482FF, 0xFFD8DCE1}));
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        sw.setChecked(Prefs.boolVal(this, key, def));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Prefs.setBool(MainActivity.this, key, isChecked);
                if (listener != null) {
                    listener.onChanged(isChecked);
                }
            }
        });
        row.addView(sw);
        return row;
    }

    private View row(String title, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(16), dp(20), dp(16));
        row.setMinimumHeight(dp(64));

        int icon = iconFor(title);
        if (icon != 0) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(icon);
            iv.setColorFilter(0xFF1A1A1A);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(24), dp(24));
            blp.rightMargin = dp(14);
            row.addView(iv, blp);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 17, TEXT, false));
        if (value != null && !value.isEmpty()) {
            TextView sub = text(value, 14, SUB, false);
            sub.setPadding(0, dp(3), dp(8), 0);
            texts.addView(sub);
        }
        row.addView(texts, weight1());
        TextView arrow = text("›", 20, 0xFFC0C4CC, false);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.leftMargin = dp(6);
        row.addView(arrow, ap);
        if (click != null) {
            row.setOnClickListener(click);
            row.setClickable(true);
        }
        return row;
    }

    /** 按标题关键词挑图标，省得每处调用都传。 */
    private int iconFor(String title) {
        if (title == null) {
            return 0;
        }
        if (title.contains("单击")) return R.drawable.ic_tap;
        if (title.contains("双击")) return R.drawable.ic_double;
        if (title.contains("三击")) return R.drawable.ic_triple;
        if (title.contains("长按")) return R.drawable.ic_long;
        if (title.contains("钩子") || title.contains("LSPosed")) return R.drawable.ic_shield;
        if (title.contains("Root")) return R.drawable.ic_root;
        if (title.contains("手电筒")) return R.drawable.ic_torch;
        if (title.contains("小部件")) return R.drawable.ic_widget;
        if (title.contains("截图") || title.contains("背屏")) return R.drawable.ic_rear;
        if (title.contains("应用") || title.contains("乐乐")) return R.drawable.ic_apps;
        if (title.contains("面板")) return R.drawable.ic_apps;
        if (title.contains("独占")) return R.drawable.ic_lock;
        if (title.contains("探测")) return R.drawable.ic_probe;
        if (title.contains("震动")) return R.drawable.ic_vibrate;
        if (title.contains("常驻")) return R.drawable.ic_keep;
        if (title.contains("心跳")) return R.drawable.ic_shield;
        return 0;
    }

    private View buttonRow(View left, View right) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(10), dp(5), dp(5), dp(5));
        r.addView(left, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.setMargins(dp(5), dp(5), dp(10), dp(5));
        r.addView(right, rp);
        return r;
    }

    private View button(String label, boolean primary, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(12), dp(10), dp(12));
        b.setTextColor(primary ? Color.WHITE : TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : 0xFFF1F2F6);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setOnClickListener(click);
        b.setClickable(true);
        return b;
    }

    private View cardTitle(String s) {
        TextView t = text(s, 12, SUB, false);
        t.setPadding(dp(16), dp(14), dp(16), dp(6));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(24));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), 0, dp(16), dp(12));
        c.setLayoutParams(lp);
        c.setClipToOutline(true);
        return c;
    }

    private LinearLayout statusRow(int iconRes, String title, View right) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(16), dp(20), dp(16));
        r.setMinimumHeight(dp(64));
        if (iconRes != 0) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageResource(iconRes);
            iv.setColorFilter(0xFF1A1A1A);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(24), dp(24));
            blp.rightMargin = dp(14);
            r.addView(iv, blp);
        }
        r.addView(text(title, 17, TEXT, false), weight1());
        if (right != null) {
            r.addView(right);
        }
        return r;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        lp.leftMargin = dp(60);
        lp.rightMargin = dp(14);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView chip(String s, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(color);
        t.setPadding(dp(9), dp(4), dp(9), dp(4));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CHIP_BG);
        g.setCornerRadius(dp(24));
        t.setBackground(g);
        return t;
    }

    private void setChip(TextView t, String s, int color) {
        t.setText(s);
        t.setTextColor(color);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("aikey", s));
        } catch (Throwable ignored) {
        }
    }
}
