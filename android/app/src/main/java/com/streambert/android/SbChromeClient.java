package com.streambert.android;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;

/** Native fullscreen for embedded players + popup blocking. */
public class SbChromeClient extends BridgeWebChromeClient {
    private final Activity activity;
    private View customView;
    private CustomViewCallback customCallback;
    private int prevOrientation;

    public SbChromeClient(Bridge bridge) {
        super(bridge);
        this.activity = bridge.getActivity();
    }

    public volatile long lastHide = 0;

    public boolean isFullscreen() { return customView != null; }

    /** true while an in-app player (Movie/TV page) is showing: its fullscreen
     *  requests become an in-app fullscreen so the app's overlays (next-episode
     *  countdown, skip intro) stay visible. */
    public static volatile boolean interceptFullscreen = false;

    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {
        if (customView != null) { callback.onCustomViewHidden(); return; }
        if (interceptFullscreen) {
            callback.onCustomViewHidden();
            StreambertPlugin.emit("webviewFullscreenRequest", true);
            return;
        }
        customView = view;
        customCallback = callback;
        prevOrientation = activity.getRequestedOrientation();
        FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
        view.setBackgroundColor(Color.BLACK);
        decor.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setImmersive(true);
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        StreambertPlugin.emit("fullscreen", true);
    }

    @Override
    public void onHideCustomView() {
        if (customView == null) return;
        lastHide = System.currentTimeMillis();
        FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
        decor.removeView(customView);
        customView = null;
        setImmersive(false);
        activity.setRequestedOrientation(prevOrientation);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        StreambertPlugin.emit("fullscreen", false);
    }

    void setImmersive(boolean on) {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        if (on) {
            c.hide(WindowInsetsCompat.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            c.show(WindowInsetsCompat.Type.systemBars());
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        // window.open / target=_blank from embedded players → blocked (popup ads)
        return false;
    }

    @Override
    public Bitmap getDefaultVideoPoster() {
        // no grey "play" placeholder before the video starts
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    }
}
