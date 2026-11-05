package de.robv.android.xposed;

/**
 * 编译期桩：真正的实现由 LSPosed 在运行时注入。
 * 只声明本模块用到的几个反射辅助方法。
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("stub");
    }

    public static java.lang.reflect.Method findMethodExact(
            Class<?> clazz, String methodName, Object... parameterTypes) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new UnsupportedOperationException("stub");
    }
}
