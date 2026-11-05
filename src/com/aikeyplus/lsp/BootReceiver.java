package com.aikeyplus.lsp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                return;
            }
            final Context c = context.getApplicationContext();
            Engine e = Engine.get(c);
            e.ensureInstalled();
            Logs.append(c, "开机完成：准备启动");

            if (Prefs.boolVal(c, Prefs.K_KEEP_ALIVE, true)) {
                try {
                    c.startForegroundService(new Intent(c, KeepAliveService.class));
                } catch (Throwable t) {
                    Logs.append(c, "开机启动前台服务失败: " + t);
                }
            }
            if (Prefs.boolVal(c, Prefs.K_AUTO_START, true)
                    && Prefs.MODE_DAEMON.equals(Prefs.mode(c))) {
                e.bg().execute(new Runnable() {
                    @Override
                    public void run() {
                        Engine.get(c).startDaemonSync();
                    }
                });
            }
        } catch (Throwable t) {
            Logs.append(context, "开机处理失败: " + t);
        }
    }
}
