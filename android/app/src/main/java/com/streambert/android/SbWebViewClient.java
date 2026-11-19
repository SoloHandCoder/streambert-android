package com.streambert.android;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

import java.io.ByteArrayInputStream;
import java.util.Collections;

/**
 * Network layer for the whole WebView, the mobile equivalent of the desktop's
 * player session hooks: ad/tracker blocking, m3u8/vtt capture, media proxy and
 * protection against embed players hijacking the app's top-level navigation.
 */
public class SbWebViewClient extends BridgeWebViewClient {
    public SbWebViewClient(Bridge bridge) { super(bridge); }

    private static boolean isApp(Uri u) {
        return "localhost".equals(u.getHost());
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri u = request.getUrl();
        String scheme = u.getScheme();
        if (isApp(u)) {
            if (StreamProxy.PATH.equals(u.getPath())) return StreamProxy.handle(request);
            return super.shouldInterceptRequest(view, request);
        }
        if ("http".equals(scheme) || "https".equals(scheme)) {
            if (AdBlock.isBlocked(u)) {
                AdBlock.record(u);
                return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", Collections.emptyMap(),
                        new ByteArrayInputStream(new byte[0]));
            }
            String s = u.toString();
            String lower = s.toLowerCase();
            if (lower.contains(".m3u8")) StreambertPlugin.onMediaFound(s, request.getRequestHeaders(), false);
            else if (lower.contains(".vtt")) StreambertPlugin.onMediaFound(s, request.getRequestHeaders(), true);
            if (FrameHeaderStrip.isSubframeDocument(request)) {
                WebResourceResponse r = FrameHeaderStrip.fetch(request);
                if (r != null) return r;
            }
        }
        return super.shouldInterceptRequest(view, request);
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri u = request.getUrl();
        // iframes (embedded players) may navigate freely inside their frame
        if (!request.isForMainFrame()) return false;
        if (isApp(u)) return false;
        String scheme = u.getScheme();
        if ("about".equals(scheme) || "blob".equals(scheme) || "data".equals(scheme)) return false;
        // embed players trying to replace the app with an ad page → blocked
        return true;
    }
}
