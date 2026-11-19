package com.streambert.android;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

/**
 * Lock-screen / notification / headset / PiP media controls for whatever video
 * is playing in the WebView (embedded players, in-app player). The JS side
 * reports state via StreambertPlugin.setPlaybackState and receives commands as
 * "mediaCommand" events.
 */
public final class MediaControl {
    public interface CommandListener { void onCommand(String cmd, double value); }

    private static MediaControl instance;
    public static synchronized MediaControl get(Context ctx) {
        if (instance == null) instance = new MediaControl(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final MediaSessionCompat session;
    private volatile CommandListener listener;
    private volatile boolean active = false, playing = false;
    private String title = "", subtitle = "";
    private long durationMs = 0, positionMs = 0;
    private Bitmap art;

    private MediaControl(Context ctx) {
        this.ctx = ctx;
        session = new MediaSessionCompat(ctx, "Streambert");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { send("play", 0); }
            @Override public void onPause() { send("pause", 0); }
            @Override public void onStop() { send("pause", 0); }
            @Override public void onSeekTo(long pos) { send("seek", pos / 1000.0); }
            @Override public void onFastForward() { send("skip", 10); }
            @Override public void onRewind() { send("skip", -10); }
            @Override public void onSkipToNext() { send("skip", 10); }
            @Override public void onSkipToPrevious() { send("skip", -10); }
        });
        try {
            art = BitmapFactory.decodeResource(ctx.getResources(), R.mipmap.ic_launcher);
        } catch (Exception ignored) {}
    }

    private void send(String cmd, double v) {
        CommandListener l = listener;
        if (l != null) l.onCommand(cmd, v);
        // optimistic state so the notification button flips immediately
        if ("play".equals(cmd)) { playing = true; publish(); }
        else if ("pause".equals(cmd)) { playing = false; publish(); }
    }

    public void setListener(CommandListener l) { listener = l; }
    public MediaSessionCompat session() { return session; }
    public boolean isActive() { return active; }
    public boolean isPlaying() { return active && playing; }
    public String title() { return title; }
    public String subtitle() { return subtitle; }
    public Bitmap art() { return art; }

    public synchronized void update(boolean active, boolean playing, String title, String subtitle, double pos, double dur) {
        boolean changed = active != this.active || playing != this.playing || !this.title.equals(title);
        this.active = active;
        this.playing = playing;
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? "" : subtitle;
        this.positionMs = (long) (Math.max(0, pos) * 1000);
        this.durationMs = dur > 0 && !Double.isInfinite(dur) ? (long) (dur * 1000) : 0;
        publish();
        if (changed) MediaPlaybackService.refresh(ctx);
    }

    private void publish() {
        if (!active) {
            session.setActive(false);
            return;
        }
        session.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title.isEmpty() ? "Streambert" : title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle.isEmpty() ? "Streambert" : subtitle)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                .build());
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_REWIND
                | PlaybackStateCompat.ACTION_FAST_FORWARD | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        if (durationMs > 0) actions |= PlaybackStateCompat.ACTION_SEEK_TO;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        positionMs, playing ? 1f : 0f)
                .build());
        session.setActive(true);
    }

    static void startServiceCompat(Context ctx, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }
}
