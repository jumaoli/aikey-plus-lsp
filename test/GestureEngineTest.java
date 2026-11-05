package com.aikeyplus.lsp.hook;

/**
 * GestureEngine 的回归测试（纯 JVM，不依赖 Android）。
 * 编译运行：
 *   javac -d out test\GestureEngineTest.java src\com\aikeyplus\lsp\hook\GestureEngine.java
 *   java -cp out com.aikeyplus.lsp.hook.GestureEngineTest
 */
public final class GestureEngineTest {

    private static int cases;
    private static int failed;

    public static void main(String[] args) {
        singleWithoutDouble();
        singleWithDoubleTimesOut();
        doubleTap();
        longPress();
        longPressOnSecondPress();
        shortPressIgnored();
        secondPressAfterWindow();
        doubleSecondTooShort();
        tripleTap();
        tripleDisabledGivesDouble();
        tripleTimesOutGivesDouble();
        System.out.println();
        System.out.println("cases=" + cases + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void singleWithoutDouble() {
        GestureEngine e = new GestureEngine(600, 350, false);
        eq("单按/无双击 按下", null, e.onDown(1000));
        eq("单按/无双击 抬起→单击", "single", e.onUp(1100));
        eq("单按/无双击 回到空闲", null, e.onTimeout());
    }

    private static void singleWithDoubleTimesOut() {
        GestureEngine e = new GestureEngine(600, 350, true);
        eq("短按/开双击 按下", null, e.onDown(1000));
        eq("短按/开双击 抬起先不派发", null, e.onUp(1100));
        check("短按/开双击 进入等待", e.waiting());
        eq("短按/开双击 超时→单击", "single", e.onTimeout());
        eq("短按/开双击 超时后再超时", null, e.onTimeout());
    }

    private static void doubleTap() {
        GestureEngine e = new GestureEngine(600, 350, true);
        e.onDown(1000);
        e.onUp(1060);
        eq("双击 第二次按下", null, e.onDown(1200));
        eq("双击 第二次抬起→双击", "double", e.onUp(1260));
        eq("双击 状态回到空闲", null, e.onTimeout());
    }

    private static void longPress() {
        GestureEngine e = new GestureEngine(600, 350, true);
        eq("长按 按下", null, e.onDown(1000));
        eq("长按 700ms 抬起→长按", "long", e.onUp(1700));
    }

    private static void longPressOnSecondPress() {
        GestureEngine e = new GestureEngine(600, 350, true);
        e.onDown(1000);
        e.onUp(1060);
        e.onDown(1200);
        eq("第二次按很久→长按", "long", e.onUp(1900));
    }

    private static void shortPressIgnored() {
        GestureEngine e = new GestureEngine(600, 350, true);
        eq("抖动 按下", null, e.onDown(1000));
        eq("抖动 5ms 抬起→忽略", null, e.onUp(1005));
        eq("抖动 之后没有等待", null, e.onTimeout());
    }

    private static void secondPressAfterWindow() {
        GestureEngine e = new GestureEngine(600, 350, true);
        e.onDown(1000);
        e.onUp(1060);
        // 窗口是 350+60=410ms；700ms 后才按第二下，第一下要补发单击
        eq("超窗口 第二次按下先补发单击", "single", e.onDown(1760));
        // 这一按自己仍然是「短按」，因为配了双击，所以要再等一次窗口
        eq("超窗口 这一按仍要等窗口", null, e.onUp(1860));
        eq("超窗口 等满窗口后补发单击", "single", e.onTimeout());
    }

    private static void doubleSecondTooShort() {
        GestureEngine e = new GestureEngine(600, 350, true);
        e.onDown(1000);
        e.onUp(1060);
        e.onDown(1100);
        eq("二次按下 5ms 也算抖动", null, e.onUp(1105));
    }

    private static void tripleTap() {
        GestureEngine e = new GestureEngine(600, 350, 400, true, true);
        eq("三击 第1按", null, e.onDown(1000));
        eq("三击 第1松", null, e.onUp(1050));
        eq("三击 第2按", null, e.onDown(1200));
        eq("三击 第2松", null, e.onUp(1250));
        eq("三击 第3按", null, e.onDown(1400));
        eq("三击 第3松→三击", "triple", e.onUp(1450));
        eq("三击 回到空闲", null, e.onTimeout());
    }

    private static void tripleDisabledGivesDouble() {
        GestureEngine e = new GestureEngine(600, 350, 400, true, false);
        eq("三击关 第1按", null, e.onDown(1000));
        eq("三击关 第1松", null, e.onUp(1050));
        eq("三击关 第2按", null, e.onDown(1200));
        eq("三击关 第2松→双击", "double", e.onUp(1250));
    }

    private static void tripleTimesOutGivesDouble() {
        GestureEngine e = new GestureEngine(600, 350, 400, true, true);
        eq("超时双击 第1按", null, e.onDown(1000));
        eq("超时双击 第1松", null, e.onUp(1050));
        eq("超时双击 第2按", null, e.onDown(1200));
        eq("超时双击 第2松", null, e.onUp(1250));
        eq("超时双击 窗口到期→双击", "double", e.onTimeout());
    }
    private static void eq(String name, String expected, String actual) {
        cases++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            failed++;
            System.out.println("FAIL  " + name + "  expected=" + expected + " actual=" + actual);
        } else {
            System.out.println("ok    " + name);
        }
    }

    private static void check(String name, boolean cond) {
        cases++;
        if (!cond) {
            failed++;
            System.out.println("FAIL  " + name);
        } else {
            System.out.println("ok    " + name);
        }
    }
}
