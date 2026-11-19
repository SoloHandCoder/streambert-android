package com.streambert.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small HttpURLConnection helper shared by the plugin, the proxy and the downloader. */
public final class Net {
    /** Browser-like UA (the WebView's own UA with the "; wv" WebView marker removed). */
    public static volatile String userAgent =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

    public static final class Response {
        public int status;
        public String url;
        public Map<String, String> headers = new HashMap<>();
        public HttpURLConnection conn;
        public InputStream body;

        public byte[] bytes(long max) throws IOException {
            try (InputStream in = body) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (max > 0 && total > max) throw new IOException("response too large");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                conn.disconnect();
            }
        }

        public String text() throws IOException {
            return new String(bytes(20L * 1024 * 1024), StandardCharsets.UTF_8);
        }
    }

    /** Opens a request, following redirects manually (also across http/https). Body stream is left open. */
    public static Response open(String url, String method, Map<String, String> headers, byte[] body, int timeoutMs) throws IOException {
        String current = url;
        for (int hop = 0; hop < 10; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(Math.max(timeoutMs, 20000));
            c.setRequestMethod(method == null ? "GET" : method);
            boolean hasUa = false;
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getValue() == null) continue;
                    if (e.getKey().equalsIgnoreCase("user-agent")) hasUa = true;
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (!hasUa) c.setRequestProperty("User-Agent", userAgent);
            if (body != null && !"GET".equals(method) && !"HEAD".equals(method)) {
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) { os.write(body); }
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && code != 304) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect without location");
                current = new URL(new URL(current), loc).toString();
                if (code == 303) { method = "GET"; body = null; }
                continue;
            }
            Response r = new Response();
            r.status = code;
            r.url = current;
            r.conn = c;
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                r.headers.put(e.getKey().toLowerCase(), e.getValue().get(e.getValue().size() - 1));
            }
            InputStream in;
            try { in = c.getInputStream(); } catch (IOException ex) { in = c.getErrorStream(); }
            if (in == null) in = new java.io.ByteArrayInputStream(new byte[0]);
            r.body = in;
            return r;
        }
        throw new IOException("too many redirects");
    }

    public static Response get(String url, Map<String, String> headers) throws IOException {
        return open(url, "GET", headers, null, 15000);
    }
}
