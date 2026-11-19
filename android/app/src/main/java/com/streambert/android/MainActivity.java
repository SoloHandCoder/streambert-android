package com.streambert.android;

import android.graphics.Color;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.BridgeActivity;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class MainActivity extends BridgeActivity {
    private SbChromeClient chromeClient;
    private AppLock appLock;

    public AppLock lock() { return appLock; }

    public SbChromeClient chrome() { return chromeClient; }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(StreambertPlugin.class);
        registerPlugin(CastPlugin.class);
        super.onCreate(savedInstanceState);
        AdBlock.load(this);

        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        getWindow().setStatusBarColor(Color.parseColor("#0a0a0a"));
        getWindow().setNavigationBarColor(Color.parseColor("#111111"));

        WebView wv = getBridge().getWebView();
        wv.setBackgroundColor(Color.parseColor("#0a0a0a"));
        WebSettings s = wv.getSettings();
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true); // so window.open goes to onCreateWindow (blocked) instead of navigating the app
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // look like regular mobile Chrome (some embed hosts reject the WebView marker)
        String ua = s.getUserAgentString().replace("; wv)", ")").replaceAll(" Version/\\d+\\.\\d+", "");
        s.setUserAgentString(ua);
        Net.userAgent = ua;

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);

        wv.setWebViewClient(new SbWebViewClient(getBridge()));
        chromeClient = new SbChromeClient(getBridge());
        wv.setWebChromeClient(chromeClient);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try (InputStream in = getAssets().open("sb-inject.js")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                WebViewCompat.addDocumentStartJavaScript(wv, out.toString("UTF-8"), Collections.singleton("*"));
            } catch (Exception ignored) {}
        }

        handleIncoming(getIntent());
        appLock = new AppLock(this);
        appLock.onCreate();

        // Back while a player is fullscreen → leave fullscreen first
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (appLock != null && appLock.isLocked()) { moveTaskToBack(true); return; }
                if (chromeClient.isFullscreen()) {
                    chromeClient.onHideCustomView();
                    return;
                }
                // the fullscreen view already consumed this back press
                if (System.currentTimeMillis() - chromeClient.lastHide < 600) return;
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (appLock != null) appLock.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (appLock != null) appLock.onResume();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (appLock != null) appLock.onStop();
    }

    // ───────────── shortcuts / shared links ─────────────
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    private void handleIncoming(android.content.Intent intent) {
        if (intent == null) return;
        String text = null;
        String action = intent.getAction();
        if (android.content.Intent.ACTION_SEND.equals(action)) {
            CharSequence t = intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT);
            if (t != null) text = t.toString();
        } else if (android.content.Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            text = intent.getData().toString();
        }
        if (text != null && !text.trim().isEmpty()) StreambertPlugin.deliverIncoming(text.trim());
    }

    // ───────────── picture-in-picture ─────────────
    private boolean pipEligible = false;
    private android.util.Rational pipAspect = new android.util.Rational(16, 9);

    private boolean pipSupported() {
        return android.os.Build.VERSION.SDK_INT >= 26
                && getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private android.app.PictureInPictureParams buildPipParams() {
        android.app.PictureInPictureParams.Builder b = new android.app.PictureInPictureParams.Builder()
                .setAspectRatio(pipAspect);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(pipEligible);
            b.setSeamlessResizeEnabled(true);
        }
        return b.build();
    }

    /** Called from JS (via the plugin) whenever playback starts/stops. */
    public void updatePipParams(boolean playing, double aspect) {
        pipEligible = playing;
        if (aspect > 0.4 && aspect < 2.39) {
            pipAspect = new android.util.Rational((int) Math.round(aspect * 1000), 1000);
        }
        if (!pipSupported()) return;
        try { setPictureInPictureParams(buildPipParams()); } catch (Exception ignored) {}
    }

    public boolean enterPip() {
        if (!pipSupported()) return false;
        try { return enterPictureInPictureMode(buildPipParams()); } catch (Exception e) { return false; }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android 12+ auto-enters via setAutoEnterEnabled; older versions need this
        if (pipEligible && android.os.Build.VERSION.SDK_INT >= 26 && android.os.Build.VERSION.SDK_INT < 31) enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        StreambertPlugin.emit("pip", inPip);
    }
}
