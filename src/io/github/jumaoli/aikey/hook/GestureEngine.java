package io.github.jumaoli.aikey.hook;

/**
 * 单击 / 双击 / 三击 / 长按 状态机（纯 Java，不依赖 Android，方便在电脑上跑单元测试）。
 *
 * 规则：
 *   - 按下时长 &lt; 20ms 视为抖动，直接丢弃
 *   - 按下时长 &gt;= longMs 视为长按
 *   - 没开双击：短按立刻算单击
 *   - 开了双击：短按后等 doubleMs 看第二下；
 *       第二下短 → 再看是否开了三击：开了就再等 tripleMs 看第三下，等不到算双击；
 *       第三下短 → 三击；任一按够久 → 长按
 */
public final class GestureEngine {

    public static final String SINGLE = "single";
    public static final String DOUBLE = "double";
    public static final String TRIPLE = "triple";
    public static final String LONG = "long";

    /** 抖动阈值：低于这个时长的按下不产生任何动作。 */
    public static final long MIN_PRESS_MS = 20;
    /** 额外等待余量，抵消事件分发 / 调度延迟。 */
    public static final long SLACK_MS = 35;

    private static final int IDLE = 0;
    private static final int DOWN = 1;
    private static final int WAIT_2 = 2;
    private static final int DOWN_2 = 3;
    private static final int WAIT_3 = 4;
    private static final int DOWN_3 = 5;

    private final long longMs;
    private final long doubleMs;
    private final long tripleMs;
    private final boolean doubleEnabled;
    private final boolean tripleEnabled;

    private int state = IDLE;
    private long downAt;
    private long upAt;

    public GestureEngine(long longMs, long doubleMs, boolean doubleEnabled) {
        this(longMs, doubleMs, 300, doubleEnabled, false);
    }

    public GestureEngine(long longMs, long doubleMs, long tripleMs,
                         boolean doubleEnabled, boolean tripleEnabled) {
        this.longMs = Math.max(50, longMs);
        this.doubleMs = Math.max(50, doubleMs);
        this.tripleMs = Math.max(50, tripleMs);
        this.doubleEnabled = doubleEnabled;
        this.tripleEnabled = doubleEnabled && tripleEnabled;
    }

    public long longMs() {
        return longMs;
    }

    public long doubleMs() {
        return doubleMs;
    }

    public long tripleMs() {
        return tripleMs;
    }

    public boolean doubleEnabled() {
        return doubleEnabled;
    }

    public boolean tripleEnabled() {
        return tripleEnabled;
    }

    /** 当前状态对应的等待窗口（含余量）。 */
    public long waitMs() {
        if (state == WAIT_2) {
            return doubleMs + SLACK_MS;
        }
        if (state == WAIT_3) {
            return tripleMs + SLACK_MS;
        }
        return 0;
    }

    public boolean waiting() {
        return state == WAIT_2 || state == WAIT_3;
    }

    public void reset() {
        state = IDLE;
        downAt = 0;
        upAt = 0;
    }

    /** 按下。返回 null 或一个要立刻派发的手势。 */
    public String onDown(long t) {
        switch (state) {
            case IDLE:
                state = DOWN;
                downAt = t;
                return null;
            case WAIT_2:
                if (t - upAt <= waitMs()) {
                    state = DOWN_2;
                    downAt = t;
                    return null;
                }
                state = DOWN;
                downAt = t;
                return SINGLE;
            case WAIT_3:
                if (t - upAt <= waitMs()) {
                    state = DOWN_3;
                    downAt = t;
                    return null;
                }
                state = DOWN;
                downAt = t;
                return DOUBLE;
            default:
                return null;
        }
    }

    /** 抬起。返回 null 或一个要立刻派发的手势。 */
    public String onUp(long t) {
        if (state == DOWN) {
            long dur = t - downAt;
            if (dur < MIN_PRESS_MS) {
                state = IDLE;
                return null;
            }
            if (dur >= longMs) {
                state = IDLE;
                return LONG;
            }
            if (!doubleEnabled) {
                state = IDLE;
                return SINGLE;
            }
            state = WAIT_2;
            upAt = t;
            return null;
        }
        if (state == DOWN_2) {
            long dur = t - downAt;
            if (dur < MIN_PRESS_MS) {
                state = IDLE;
                return null;
            }
            if (dur >= longMs) {
                state = IDLE;
                return LONG;
            }
            if (!tripleEnabled) {
                state = IDLE;
                return DOUBLE;
            }
            state = WAIT_3;
            upAt = t;
            return null;
        }
        if (state == DOWN_3) {
            long dur = t - downAt;
            if (dur < MIN_PRESS_MS) {
                state = IDLE;
                return null;
            }
            if (dur >= longMs) {
                state = IDLE;
                return LONG;
            }
            state = IDLE;
            return TRIPLE;
        }
        return null;
    }

    /** 等待窗口到期。返回 null / "single" / "double"。 */
    public String onTimeout() {
        if (state == WAIT_2) {
            state = IDLE;
            return SINGLE;
        }
        if (state == WAIT_3) {
            state = IDLE;
            return DOUBLE;
        }
        return null;
    }

    public boolean idle() {
        return state == IDLE;
    }
}
