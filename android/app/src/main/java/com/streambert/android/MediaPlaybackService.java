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
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

/**
 * Foreground "media playback" service: keeps audio going with the screen off and
 * shows the media notification (also drives the lock-screen controls).
 */
public class MediaPlaybackService extends Service {
    static final String CHANNEL = "playback";
    static final int NOTIF_ID = 4712;
    private static volatile boolean running = false;

    static synchronized void refresh(Context ctx) {
        MediaControl mc = MediaControl.get(ctx);
        try {
            if (mc.isActive()) {
                if (!running) {
                    // only start the foreground service once something actually plays
                    if (mc.isPlaying()) MediaControl.startServiceCompat(ctx, new Intent(ctx, MediaPlaybackService.class));
                } else {
                    NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.notify(NOTIF_ID, build(ctx));
                }
            } else if (running) {
                ctx.stopService(new Intent(ctx, MediaPlaybackService.class));
            }
        } catch (Exception ignored) {}
    }

    static Notification build(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        MediaControl mc = MediaControl.get(ctx);
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean playing = mc.isPlaying();
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_streambert)
                .setColor(0xFFE50914)
                .setLargeIcon(mc.art())
                .setContentTitle(mc.title().isEmpty() ? "Vidra" : mc.title())
                .setContentText(mc.subtitle().isEmpty() ? "Vidra" : mc.subtitle())
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .addAction(android.R.drawable.ic_media_rew, "Back 10s",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(ctx, PlaybackStateCompat.ACTION_REWIND))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(ctx, PlaybackStateCompat.ACTION_PLAY_PAUSE))
                .addAction(android.R.drawable.ic_media_ff, "Forward 10s",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(ctx, PlaybackStateCompat.ACTION_FAST_FORWARD))
                .setStyle(new MediaStyle()
                        .setMediaSession(mc.session().getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        // media buttons routed here by MediaButtonReceiver
        MediaButtonReceiver.handleIntent(MediaControl.get(this).session(), intent);
        Notification n = build(this);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
        if (!MediaControl.get(this).isActive()) stopSelf();
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
