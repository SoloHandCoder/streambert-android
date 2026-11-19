package com.streambert.android;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-WebView media proxy (replaces the desktop app's 127.0.0.1 player server).
 * https://localhost/__sbproxy?u=<url>&r=<referer>  → fetched natively with the given
 * Referer; HLS playlists are rewritten so every child request is proxied too.
 */
public final class StreamProxy {
    public static final String PATH = "/__sbproxy";
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    public static String proxied(String target, String referer) {
        try {
            return PATH + "?u=" + URLEncoder.encode(target, "UTF-8") + (referer != null ? "&r=" + URLEncoder.encode(referer, "UTF-8") : "");
        } catch (Exception e) {
            return target;
        }
    }

    public static WebResourceResponse handle(WebResourceRequest req) {
        Uri u = req.getUrl();
        String target = u.getQueryParameter("u");
        String referer = u.getQueryParameter("r");
        Map<String, String> cors = new HashMap<>();
        cors.put("Access-Control-Allow-Origin", "*");
        cors.put("Cache-Control", "no-store");
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
            return new WebResourceResponse("text/plain", "utf-8", 400, "Bad Request", cors, new ByteArrayInputStream(new byte[0]));
        }
        try {
            Map<String, String> h = new HashMap<>();
            h.put("Accept", "*/*");
            h.put("Accept-Encoding", "identity");
            if (referer != null) {
                h.put("Referer", referer);
                try {
                    URL ru = new URL(referer);
                    h.put("Origin", ru.getProtocol() + "://" + ru.getAuthority());
                } catch (Exception ignored) {}
            }
            String range = null;
            for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
                if (e.getKey().equalsIgnoreCase("range")) range = e.getValue();
            }
            if (range != null) h.put("Range", range);
            Net.Response r = Net.open(target, "GET", h, null, 15000);
            String ct = r.headers.getOrDefault("content-type", "application/octet-stream");
            boolean playlist = target.toLowerCase().contains(".m3u8") || ct.toLowerCase().contains("mpegurl");
            if (playlist && r.status == 200) {
                String text = r.text();
                if (text.trim().startsWith("#EXTM3U")) text = rewrite(text, r.url, referer);
                return new WebResourceResponse("application/vnd.apple.mpegurl", "utf-8", 200, "OK", cors,
                        new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            }
            Map<String, String> out = new HashMap<>(cors);
            for (String k : new String[]{"content-length", "content-range", "accept-ranges", "last-modified", "etag"}) {
                if (r.headers.containsKey(k)) out.put(k, r.headers.get(k));
            }
            String mime = ct.split(";")[0].trim();
            int status = r.status;
            String reason = status == 206 ? "Partial Content" : status >= 400 ? "Error" : "OK";
            return new WebResourceResponse(mime, null, status, reason, out, r.body);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", cors, new ByteArrayInputStream(new byte[0]));
        }
    }

    static String rewrite(String text, String base, String referer) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) { sb.append(line).append('\n'); continue; }
            if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(line);
                StringBuffer b = new StringBuffer();
                while (m.find()) {
                    m.appendReplacement(b, Matcher.quoteReplacement("URI=\"" + proxied(abs(base, m.group(1)), referer) + "\""));
                }
                m.appendTail(b);
                sb.append(b).append('\n');
            } else {
                sb.append(proxied(abs(base, t), referer)).append('\n');
            }
        }
        return sb.toString();
    }

    static String abs(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (Exception e) { return rel; }
    }
}
