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

    public SbChromeClient chrome() { return chromeClient; }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(StreambertPlugin.class);
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

        // Back while a player is fullscreen → leave fullscreen first
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
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
}
