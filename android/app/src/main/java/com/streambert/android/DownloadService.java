package com.streambert.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/** Keeps the process alive (foreground service) while downloads are running. */
public class DownloadService extends Service {
    static final String CHANNEL = "downloads";
    static final int NOTIF_ID = 4711;
    private static volatile boolean running = false;
    private static long lastRefresh = 0;

    static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel("events", "Notifications", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    /** Starts/updates/stops the service to match the number of active downloads. */
    static synchronized void refresh(Context ctx) {
        int n = Downloads.get(ctx).activeCount();
        if (n > 0) {
            long now = System.currentTimeMillis();
            if (running && now - lastRefresh < 1000) return;
            lastRefresh = now;
            try {
                if (!running) {
                    Downloads.startServiceCompat(ctx, new Intent(ctx, DownloadService.class));
                } else {
                    NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.notify(NOTIF_ID, build(ctx, n));
                }
            } catch (Exception ignored) {}
        } else if (running) {
            ctx.stopService(new Intent(ctx, DownloadService.class));
        }
    }

    static Notification build(Context ctx, int n) {
        ensureChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_streambert)
                    .setColor(0xFFE50914)
                .setContentTitle("Streambert")
                .setContentText(n == 1 ? "Downloading 1 item…" : "Downloading " + n + " items…")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setContentIntent(pi)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        Notification n = build(this, Math.max(1, Downloads.get(this).activeCount()));
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
        if (Downloads.get(this).activeCount() == 0) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
