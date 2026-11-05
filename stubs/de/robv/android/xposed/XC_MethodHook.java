package de.robv.android.xposed;

import java.lang.reflect.Member;

import de.robv.android.xposed.callbacks.XCallback;

/**
 * 编译期桩：真正的实现由 LSPosed 在运行时注入。
 */
public abstract class XC_MethodHook {

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam extends XCallback.Param {
        public Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() {
            throw new UnsupportedOperationException("stub");
        }

        public void setResult(Object result) {
            throw new UnsupportedOperationException("stub");
        }

        public Throwable getThrowable() {
            throw new UnsupportedOperationException("stub");
        }

        public boolean hasThrowable() {
            throw new UnsupportedOperationException("stub");
        }

        public void setThrowable(Throwable throwable) {
            throw new UnsupportedOperationException("stub");
        }

        public Object getResultOrThrowable() throws Throwable {
            throw new UnsupportedOperationException("stub");
        }
    }

    public class Unhook {
        public Member getHookedMethod() {
            throw new UnsupportedOperationException("stub");
        }

        public void unhook() {
            throw new UnsupportedOperationException("stub");
        }
    }
}
