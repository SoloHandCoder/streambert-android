package com.streambert.android;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * https://localhost/__sbcontent?u=<content:// uri> → streams a document the app
 * has access to (downloads saved to a picked folder) with HTTP Range support,
 * so the in-app player can seek.
 */
final class ContentServe {
    static final String PATH = "/__sbcontent";
    private ContentServe() {}

    static WebResourceResponse handle(Context ctx, WebResourceRequest req) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Accept-Ranges", "bytes");
        String u = req.getUrl().getQueryParameter("u");
        if (u == null || !u.startsWith("content://")) {
            return new WebResourceResponse("text/plain", "utf-8", 400, "Bad Request", h, new ByteArrayInputStream(new byte[0]));
        }
        try {
            Uri uri = Uri.parse(u);
            String mime = ctx.getContentResolver().getType(uri);
            if (mime == null) mime = u.endsWith(".vtt") ? "text/vtt" : "video/mp4";
            AssetFileDescriptor afd = ctx.getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd == null) throw new IOException("cannot open");
            long total = afd.getLength();
            if (total < 0) total = afd.getParcelFileDescriptor().getStatSize();
            FileInputStream fin = afd.createInputStream();
            String range = null;
            for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
                if (e.getKey().equalsIgnoreCase("range")) range = e.getValue();
            }
            // The WebView applies the requested byte range to intercepted
            // responses itself (same approach as Capacitor's local server):
            // return the whole stream and only describe the range in headers.
            int status = 200;
            String reason = "OK";
            if (range != null && range.startsWith("bytes=") && total > 0) {
                String[] p = range.substring(6).split("-", 2);
                String from = p[0].isEmpty() ? "0" : p[0];
                long to = total - 1;
                try { if (p.length > 1 && !p[1].isEmpty()) to = Math.min(total - 1, Long.parseLong(p[1])); } catch (NumberFormatException ignored) {}
                status = 206;
                reason = "Partial Content";
                h.put("Content-Range", "bytes " + from + "-" + to + "/" + total);
            }
            InputStream stream = new FilterInputStream(fin) {
                @Override public void close() throws IOException {
                    super.close();
                    afd.close();
                }
            };
            return new WebResourceResponse(mime, null, status, reason, h, stream);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
        }
    }
}
