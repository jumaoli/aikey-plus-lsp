package com.aikeyplus.lsp.hook;

import android.os.Bundle;

/** 钩子侧读到的配置快照。 */
public final class HookConfig {

    public final boolean enabled;
    public final boolean consume;
    public final boolean probe;
    public final boolean haptic;
    public final String single;
    public final String doubleTap;
    public final String tripleTap;
    public final String longPress;
    public final long longMs;
    public final long doubleMs;
    public final long tripleMs;
    public final int[] codes;
    public final long at;

    public HookConfig(boolean enabled, boolean consume, boolean probe, boolean haptic,
                      String single, String doubleTap, String tripleTap, String longPress,
                      long longMs, long doubleMs, long tripleMs, int[] codes) {
        this.enabled = enabled;
        this.consume = consume;
        this.probe = probe;
        this.haptic = haptic;
        this.single = single == null ? "none" : single;
        this.doubleTap = doubleTap == null ? "none" : doubleTap;
        this.tripleTap = tripleTap == null ? "none" : tripleTap;
        this.longPress = longPress == null ? "none" : longPress;
        this.longMs = longMs <= 0 ? 900 : longMs;
        this.doubleMs = doubleMs <= 0 ? 240 : doubleMs;
        this.tripleMs = tripleMs <= 0 ? 300 : tripleMs;
        this.codes = codes == null || codes.length == 0 ? new int[]{690} : codes;
        this.at = System.currentTimeMillis();
    }

    public boolean doubleEnabled() {
        return doubleTap != null && !"none".equals(doubleTap);
    }

    public boolean tripleEnabled() {
        return doubleEnabled() && tripleTap != null && !"none".equals(tripleTap);
    }

    public boolean has(int code) {
        for (int c : codes) {
            if (c == code) {
                return true;
            }
        }
        return false;
    }

    public static HookConfig defaults() {
        return new HookConfig(true, true, false, true,
                "flashlight", "record", "none", "screenshot", 900, 240, 300, new int[]{690});
    }

    public static HookConfig from(Bundle b) {
        if (b == null) {
            return defaults();
        }
        String mode = b.getString("mode", "hook");
        boolean hookMode = "hook".equals(mode);
        int[] arr = parseCodes(b.getString("codes", "690"));
        return new HookConfig(hookMode,
                b.getBoolean("consume", true),
                b.getBoolean("probe", false),
                b.getBoolean("haptic", true),
                b.getString("single", "none"),
                b.getString("double", "none"),
                b.getString("triple", "none"),
                b.getString("long", "none"),
                b.getLong("longMs", 600),
                b.getLong("doubleMs", 350),
                b.getLong("tripleMs", 300),
                arr);
    }

    private static int[] parseCodes(String s) {
        if (s == null || s.trim().isEmpty()) {
            return new int[]{690};
        }
        String[] parts = s.split("[,，\\s]+");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v > 0) {
                    out[n++] = v;
                }
            } catch (Throwable ignored) {
            }
        }
        if (n == 0) {
            return new int[]{690};
        }
        int[] r = new int[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }
}
