package io.github.jumaoli.aikey.hook;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * system_server 里的按键拦截。
 *
 * 挂的是 PhoneWindowManager#interceptKeyBeforeQueueing / #interceptKeyBeforeDispatching：
 * 前者在"入队前"调用，亮屏灭屏都能收到；后者是分发前的最后一道，作为兜底。
 * 两个点可能同时看到同一个事件，所以用 (downTime, action, keyCode) 去重。
 *
 * 命中配置里的键码后：
 *   - 立刻吞掉事件（PASS_TO_USER 位清零），系统/应用都收不到
 *   - 交给手势状态机判单击 / 双击 / 长按
 *   - 通过 ContentProvider 通知 App 执行动作（动作在 App 里用 root shell 跑，和原模块一致）
 */
public final class KeyHook {

    private static final int ACTION_PASS_TO_USER = 0x00000001;

    /** 不同 Android 版本 / MIUI 分支里 PhoneWindowManager 可能在的包名。 */
    private static final String[] POLICY_CLASSES = {
            "com.android.server.wm.PhoneWindowManager",
            "com.android.server.policy.PhoneWindowManager",
            "com.android.server.wm.MiuiPhoneWindowManager",
            "com.android.server.policy.MiuiPhoneWindowManager",
            "com.android.server.policy.BasePhoneWindowManager",
            "com.android.server.wm.BasePhoneWindowManager",
    };

    private static final Object LOCK = new Object();

    private static volatile HookConfig cfg = HookConfig.defaults();
    private static volatile long cfgAt;
    private static volatile Context ctx;
    private static Handler handler;
    private static Handler dispatchHandler;
    private static GestureEngine engine;
    private static int timeoutToken;
    private static long lastDown = -1;
    private static int dbg;
    private static long eLong, eDouble, eTriple;
    private static boolean eDoubleOn, eTripleOn;
    private static int lastAct = -1;
    private static int lastCode = -1;
    private static long lastProbe;
    private static long lastKeyLog;
    private static boolean installed;
    private static String helloInfo = "";
    private static final AtomicBoolean REFRESH_PENDING = new AtomicBoolean(false);

    private KeyHook() {
    }

    public static void install(final ClassLoader cl) {
        if (installed) {
            return;
        }
        installed = true;
        HandlerThread ht = new HandlerThread("aikey-hook");
        ht.start();
        handler = new Handler(ht.getLooper());
        HandlerThread dt = new HandlerThread("aikey-dispatch-h");
        dt.start();
        dispatchHandler = new Handler(dt.getLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                init(cl);
            }
        });
    }

    private static void init(ClassLoader cl) {
        try {
            ctx = systemContext();
            NativeActions.init(cl);
        try {
            refreshConfig(true);
            XposedBridge.log("AIKey: 配置预加载完成 single="
                    + (cfg == null ? "?" : cfg.single));
            warmRetry(0);
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 配置预加载失败: " + t);
        }
            int total = 0;
            StringBuilder hit = new StringBuilder();
            java.util.HashSet<String> done = new java.util.HashSet<>();
            for (String name : POLICY_CLASSES) {
                Class<?> c = null;
                try {
                    c = Class.forName(name, false, cl);
                } catch (Throwable ignored) {
                }
                if (c == null || !done.add(name)) {
                    continue;
                }
                int n = hookClass(c);
                if (n > 0) {
                    total += n;
                    if (hit.length() > 0) {
                        hit.append(',');
                    }
                    hit.append(name);
                    XposedBridge.log("AIKey: hooked " + name + " methods=" + n);
                }
            }
            // 有些机型（MIUI/HyperOS）用子类替换了 PhoneWindowManager，
            // 这里再从 WindowManagerService 里把真正在用的 policy 类捞出来挂一遍。
            String live = hookLivePolicy(done);
            if (live != null) {
                total++;
                if (hit.length() > 0) {
                    hit.append(',');
                }
                hit.append(live);
            }
            if (total == 0) {
                XposedBridge.log("AIKey: 没找到 PhoneWindowManager 的按键方法");
                Bridge.log(ctx, "钩子已注入，但没找到 PhoneWindowManager 挂钩点；请改用守护进程模式");
                helloInfo = "hooks=0";
            } else {
                Bridge.log(ctx, "钩子已注入并挂钩: " + hit);
                helloInfo = "hooks=" + total + " " + hit;
            }
            // system_server 刚起来时 PackageManager 可能还没准备好，心跳重试几次
            scheduleHello(0);
        } catch (Throwable t) {
            XposedBridge.log(t);
            Bridge.log(ctx, "钩子初始化异常: " + t);
        }
    }

    /** 从 WindowManagerService 里拿到实际使用的 policy 实例，挂它自己的类。 */
    private static String hookLivePolicy(java.util.HashSet<String> done) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "window");
            if (binder == null) {
                return null;
            }
            Class<?> c = binder.getClass();
            java.lang.reflect.Field f = null;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("mPolicy");
                } catch (Throwable ignored) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            Object policy = f.get(binder);
            if (policy == null) {
                return null;
            }
            String name = policy.getClass().getName();
            if (!done.add(name)) {
                return null;
            }
            int n = hookClass(policy.getClass());
            if (n <= 0) {
                return null;
            }
            XposedBridge.log("AIKey: hooked live policy " + name + " methods=" + n);
            return name;
        } catch (Throwable t) {
            XposedBridge.log("AIKey: live policy 探测失败: " + t);
            return null;
        }
    }

    private static void scheduleHello(final int attempt) {
        long delay = attempt == 0 ? 800L : (long) Math.min(60000, 5000L * (1L << attempt));
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (ctx == null) {
                    return;
                }
                if (Bridge.hello(ctx, helloInfo) || attempt >= 4) {
                    return;
                }
                scheduleHello(attempt + 1);
            }
        }, delay);
    }

    private static int hookClass(Class<?> c) {
        int n = 0;
        try {
            n += XposedBridge.hookAllMethods(c, "interceptKeyBeforeQueueing",
                    new KeyCallback(true)).size();
        } catch (Throwable ignored) {
        }
        try {
            n += XposedBridge.hookAllMethods(c, "interceptKeyBeforeDispatching",
                    new KeyCallback(false)).size();
        } catch (Throwable ignored) {
        }
        return n;
    }

    private static final class KeyCallback extends XC_MethodHook {
        private final boolean queueing;

        KeyCallback(boolean queueing) {
            this.queueing = queueing;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] args = param.args;
                if (args == null || args.length == 0) {
                    return;
                }
                KeyEvent ev = null;
                for (Object o : args) {
                    if (o instanceof KeyEvent) {
                        ev = (KeyEvent) o;
                        break;
                    }
                }
                if (ev == null) {
                    return;
                }
                boolean consume = handle(ev, param.thisObject);
                if (!consume) {
                    return;
                }
                if (!(param.method instanceof Method)) {
                    return;
                }
                Method m = (Method) param.method;
                Class<?> rt = m.getReturnType();
                int flags = 0;
                if (args[args.length - 1] instanceof Integer) {
                    flags = (Integer) args[args.length - 1];
                }
                if (rt == int.class) {
                    // 清掉 PASS_TO_USER：事件不再进窗口分发，系统自己也不会处理
                    param.setResult(flags & ~ACTION_PASS_TO_USER);
                } else if (rt == long.class) {
                    // interceptKeyBeforeDispatching: -1 = 丢弃
                    param.setResult(-1L);
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    /** App 正在借系统执行原生动作（run_native 注入 AI 键），这段时间不能吞事件。 */
    private static volatile boolean sBusyCached;

    /** 后台每 500ms 刷一次，按键线程只读缓存，避免跨进程调用。 */
    private static void startBusyPoller() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ctx != null) {
                        String v = android.provider.Settings.System.getString(
                                ctx.getContentResolver(), "aikey_native_busy");
                        sBusyCached = "1".equals(v);
                    }
                } catch (Throwable ignored) {
                }
                handler.postDelayed(this, 500);
            }
        });
    }

    private static boolean nativeBusy() {
        return sBusyCached;
    }


    /** 返回 true 表示这个事件要吞掉。 */
    private static boolean handle(KeyEvent ev, Object pwm) {
        HookConfig c = cfg;
        int code = ev.getKeyCode();
        // 配置直接从 Settings 读（很快），不要依赖可能被阻塞的后台线程
        if (SystemClock.uptimeMillis() - cfgAt > 1000) {
            refreshConfig(true); // inline refresh
            c = cfg;
        }
        if (!c.has(code)) {
            return false;
        }
        if (nativeBusy()) {
            return false;
        }
        if (c.probe) {
            probe(ev);
        }
        long dt = ev.getDownTime();
        int act = ev.getAction();
        synchronized (LOCK) {
            if (dt == lastDown && act == lastAct && code == lastCode) {
                // 另一个挂钩点已经处理过同一个事件
                return c.consume;
            }
            lastDown = dt;
            lastAct = act;
            lastCode = code;
        }
        if (!c.enabled || ev.getRepeatCount() > 0) {
            return c.consume;
        }
        long t = ev.getEventTime();
        if (SystemClock.uptimeMillis() - lastKeyLog > 800) {
            lastKeyLog = SystemClock.uptimeMillis();
            XposedBridge.log("AIKey: key code=" + code
                    + (act == KeyEvent.ACTION_DOWN ? " down" : " up") + " t=" + t);
        }
        String g = null;
        synchronized (LOCK) {
            // 配置变了就重建引擎，否则三击这类后开的开关不会生效
            if (engine == null || eLong != c.longMs || eDouble != c.doubleMs
                    || eTriple != c.tripleMs || eDoubleOn != c.doubleEnabled()
                    || eTripleOn != c.tripleEnabled()) {
                engine = new GestureEngine(c.longMs, c.doubleMs, c.tripleMs,
                        c.doubleEnabled(), c.tripleEnabled());
                eLong = c.longMs;
                eDouble = c.doubleMs;
                eTriple = c.tripleMs;
                eDoubleOn = c.doubleEnabled();
                eTripleOn = c.tripleEnabled();
                XposedBridge.log("AIKey: 手势引擎重建 long=" + c.longMs + " double=" + c.doubleMs
                        + " triple=" + c.tripleMs + " 双击=" + eDoubleOn + " 三击=" + eTripleOn);
            }
            if (act == KeyEvent.ACTION_DOWN) {
                g = engine.onDown(t);
                if (dbg < 20) { dbg++; XposedBridge.log("AIKey: [dbg] down -> " + g + " waiting=" + engine.waiting()); }
            } else if (act == KeyEvent.ACTION_UP) {
                g = engine.onUp(t);
                if (dbg < 20) { dbg++; XposedBridge.log("AIKey: [dbg] up -> " + g + " waiting=" + engine.waiting()); }
            }
            if (engine.waiting()) {
                armTimeout(engine.waitMs());
            } else {
                timeoutToken++;
            }
        }
        if (g != null) {
            dispatch(g);
        }
        maybeRefresh();
        return c.consume;
    }

    private static void armTimeout(final long ms) {
        final int token = ++timeoutToken;
        dispatchHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                String g = null;
                synchronized (LOCK) {
                    if (token != timeoutToken) {
                        return;
                    }
                    if (engine == null) {
                        return;
                    }
                    g = engine.onTimeout();
                    if (dbg < 25) { dbg++; XposedBridge.log("AIKey: [dbg] timeout -> " + g); }
                }
                if (g != null) {
                    dispatch(g);
                }
            }
        }, ms + 15);
    }

    private static void dispatch(final String gesture) {
        XposedBridge.log("AIKey: [dbg] dispatch " + gesture);
        dispatchHandler.post(new Runnable() {
            @Override
            public void run() {
                HookConfig c = cfg;
                if (c.haptic) {
                    haptic(gesture);
                }
                String action = "single".equals(gesture) ? c.single
                        : ("double".equals(gesture) ? c.doubleTap
                        : ("triple".equals(gesture) ? c.tripleTap : c.longPress));
                if (action != null && action.startsWith("native:")) {
                    String fn = action.substring(7);
                    if (NativeActions.trigger(ctx, fn)) {
                        XposedBridge.log("AIKey: 手势 " + gesture + " -> 原生 " + fn + " ok");
                        Bridge.log(ctx, "手势 " + gesture + " -> 原生 " + fn + " ok");
                        return;
                    }
                    XposedBridge.log("AIKey: 原生 " + fn + " 失败，回退给 App");
                }
                final String g2 = gesture;
                final String a2 = action;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Bridge.dispatch(ctx, g2, a2);
                    }
                }, "aikey-dispatch").start();
            }
        });
    }

    /** 手势震动反馈：单击一下、双击两下、长按一下长的。 */
    private static void haptic(String gesture) {
        try {
            if (ctx == null) {
                return;
            }
            android.os.Vibrator v = (android.os.Vibrator)
                    ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                return;
            }
            long[] pattern;
            if ("triple".equals(gesture)) {
                pattern = new long[]{0, 16, 60, 16, 60, 16};
            } else if ("double".equals(gesture)) {
                pattern = new long[]{0, 18, 70, 18};
            } else if ("long".equals(gesture)) {
                pattern = new long[]{0, 45};
            } else {
                pattern = new long[]{0, 22};
            }
            v.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1));
        } catch (Throwable t) {
            XposedBridge.log("AIKey: 震动失败: " + t);
        }
    }

    private static void probe(KeyEvent ev) {
        long now = SystemClock.uptimeMillis();
        if (now - lastProbe < 120) {
            return;
        }
        lastProbe = now;
        String a = ev.getAction() == KeyEvent.ACTION_DOWN ? "DOWN" : "UP";
        Bridge.log(ctx, "按键探测 " + a + " keyCode=" + ev.getKeyCode()
                + " scanCode=" + ev.getScanCode() + " device=" + ev.getDeviceId());
    }

    private static boolean pollerStarted;

    private static void maybeRefresh() {
        if (!pollerStarted && handler != null) {
            pollerStarted = true;
            startBusyPoller();
        }
        long now = SystemClock.uptimeMillis();
        if (now - cfgAt < 2000) {
            return;
        }
        if (!REFRESH_PENDING.compareAndSet(false, true)) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                REFRESH_PENDING.set(false);
                }
        });
    }

    private static void refreshConfig(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - cfgAt < 2000) {
            return;
        }
        cfgAt = now;
        HookConfig nc = Bridge.fetchConfig(ctx);
        if (nc == null) {
            return;
        }
        synchronized (LOCK) {
            if (engine == null
                    || engine.longMs() != nc.longMs
                    || engine.doubleMs() != nc.doubleMs
                    || engine.doubleEnabled() != nc.doubleEnabled()) {
                engine = new GestureEngine(nc.longMs, nc.doubleMs, nc.doubleEnabled());
            }
        }
        cfg = nc;
    }

    /** App 还没起来时 preload 只能拿到默认值，这里持续重试直到拿到真实配置。 */
    private static void warmRetry(final int attempt) {
        if (attempt >= 20 || handler == null) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    HookConfig before = cfg;
                    refreshConfig(true);
                    HookConfig now = cfg;
                    boolean ok = now != null && now.tripleEnabled()
                            || (now != null && !"flashlight".equals(now.single));
                    if (!ok) {
                        warmRetry(attempt + 1);
                    } else {
                        XposedBridge.log("AIKey: 配置就绪 single=" + now.single
                                + " triple=" + now.tripleTap);
                    }
                } catch (Throwable t) {
                    warmRetry(attempt + 1);
                }
            }
        }, 5000);
    }

    private static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                Object c = at.getMethod("getSystemContext").invoke(thread);
                if (c instanceof Context) {
                    return (Context) c;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> ag = Class.forName("android.app.AppGlobals");
            Object c = ag.getMethod("getInitialApplication").invoke(null);
            if (c instanceof Context) {
                return (Context) c;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
