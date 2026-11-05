package io.github.jumaoli.aikey;

import android.app.Application;

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 释放 shell 动作脚本 + 写一份 config.conf（很快，文件都很小）
        try {
            Engine.get(this).bg().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Shell.run("settings put system aikey_native_busy 0", 5000);
                    } catch (Throwable ignored) {
                    }
                }
            });
            Prefs.hydrateFromSystem(this);
            Engine.get(this).syncFromSystemSelectionSync();
            Engine.get(this).ensureInstalled();
        } catch (Throwable t) {
            Logs.append(this, "初始化失败: " + t);
        }
    }
}
