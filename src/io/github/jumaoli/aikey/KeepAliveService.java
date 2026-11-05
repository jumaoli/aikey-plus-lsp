package io.github.jumaoli.aikey;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻前台服务：让进程活着，root shell 就不会被回收，
 * 按 AI 键的动作延迟能稳定在几十毫秒。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL = "aikey";
    private static final int NOTI_ID = 0x41;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTI_ID, buildNotification());
        } catch (Throwable t) {
            Logs.append(this, "前台服务启动失败: " + t);
            stopSelf();
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "AI 键运行状态",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("AI 键多功能")
                .setContentText("按键监听已就绪")
                .setSmallIcon(R.drawable.ic_stat_aikey)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
