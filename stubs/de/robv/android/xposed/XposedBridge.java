package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * 编译期桩：真正的实现由 LSPosed 在运行时注入。
 * 这个类不会被打进 APK（build.ps1 用 compileOnly classpath）。
 */
public final class XposedBridge {

    public static final int XPOSED_BRIDGE_VERSION = 82;

    private XposedBridge() {
    }

    public static void log(String text) {
        throw new UnsupportedOperationException("stub");
    }

    public static void log(Throwable t) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }
}
