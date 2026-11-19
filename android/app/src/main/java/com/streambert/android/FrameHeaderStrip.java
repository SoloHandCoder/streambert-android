package com.streambert.android;

import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Desktop parity: the Electron app strips X-Frame-Options / CSP from player and
 * trailer pages so they can be embedded (e.g. Invidious sends SAMEORIGIN).
 * Here, documents loaded into iframes are fetched natively and returned without
 * those headers. Cookies are forwarded/stored through the WebView CookieManager.
 * Redirects and Cloudflare challenges are left to the WebView (returns null).
 */
final class FrameHeaderStrip {
    private FrameHeaderStrip() {}

    static boolean isSubframeDocument(WebResourceRequest req) {
        if (req.isForMainFrame() || !"GET".equalsIgnoreCase(req.getMethod())) return false;
        String accept = null;
        for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
            if (e.getKey().equalsIgnoreCase("accept")) accept = e.getValue();
        }
        return accept != null && accept.startsWith("text/html");
    }

    static WebResourceResponse fetch(WebResourceRequest req) {
        Uri u = req.getUrl();
        String url = u.toString();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
                String k = e.getKey();
                if (k.equalsIgnoreCase("cookie") || k.equalsIgnoreCase("accept-encoding")) continue;
                c.setRequestProperty(k, e.getValue());
            }
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) c.setRequestProperty("Cookie", cookies);
            int code = c.getResponseCode();

            List<String> setCookies = c.getHeaderFields().get("Set-Cookie");
            if (setCookies != null) {
                CookieManager cm = CookieManager.getInstance();
                for (String sc : setCookies) cm.setCookie(url, sc);
            }
            // let the WebView handle redirects (keeps the frame URL right) and
            // bot challenges (needs the real browser) by itself
            if (code >= 300 && code < 400) { c.disconnect(); return null; }
            String cfm = c.getHeaderField("cf-mitigated");
            if (cfm != null || ((code == 403 || code == 503) && "cloudflare".equalsIgnoreCase(c.getHeaderField("server")))) {
                c.disconnect();
                return null;
            }

            String ct = c.getContentType();
            if (ct == null) ct = "text/html";
            String mime = ct.split(";")[0].trim();
            String charset = null;
            for (String part : ct.split(";")) {
                part = part.trim();
                if (part.toLowerCase().startsWith("charset=")) charset = part.substring(8).replace("\"", "");
            }
            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                String lk = k.toLowerCase();
                if (lk.equals("x-frame-options") || lk.equals("content-security-policy")
                        || lk.equals("content-security-policy-report-only") || lk.equals("set-cookie")
                        || lk.equals("content-encoding") || lk.equals("content-length")
                        || lk.equals("transfer-encoding") || lk.equals("content-type")) continue;
                out.put(k, e.getValue().get(e.getValue().size() - 1));
            }
            InputStream in;
            try { in = c.getInputStream(); } catch (Exception ex) { in = c.getErrorStream(); }
            if (in == null) in = new java.io.ByteArrayInputStream(new byte[0]);
            String reason = c.getResponseMessage();
            if (reason == null || reason.isEmpty()) reason = code < 400 ? "OK" : "Error";
            return new WebResourceResponse(mime, charset != null ? charset : "utf-8", code, reason, out, in);
        } catch (Exception e) {
            if (c != null) c.disconnect();
            return null; // fall back to the WebView's own request
        }
    }
}
