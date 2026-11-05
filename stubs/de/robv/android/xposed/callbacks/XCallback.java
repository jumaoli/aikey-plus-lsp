package de.robv.android.xposed.callbacks;

/**
 * 编译期桩：真正的实现由 LSPosed 在运行时注入。
 */
public abstract class XCallback {

    public XCallback() {
    }

    public static class Param {
        public Object extra;
    }
}
