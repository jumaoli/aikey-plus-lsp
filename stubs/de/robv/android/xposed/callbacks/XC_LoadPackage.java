package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/**
 * 编译期桩：真正的实现由 LSPosed 在运行时注入。
 */
public abstract class XC_LoadPackage extends XCallback {

    public XC_LoadPackage() {
    }

    public static class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
        public int uid;
    }
}
