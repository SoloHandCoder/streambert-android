package com.streambert.android;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.CookieManager;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "Streambert")
public class StreambertPlugin extends Plugin {
    private static volatile StreambertPlugin instance;
    private final ExecutorService io = Executors.newFixedThreadPool(4);

    /** url → request headers seen when the WebView fetched it (for downloads / external players). */
    private static final Map<String, Map<String, String>> streamMeta =
            Collections.synchronizedMap(new LinkedHashMap<String, Map<String, String>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> e) { return size() > 300; }
            });

    @Override
    public void load() {
        instance = this;
        AdBlock.setListener(batch -> {
            try { notifyListeners("blocked", JSObject.fromJSONObject(batch)); } catch (Exception ignored) {}
        });
        Downloads.get(getContext()).setListener(update -> {
            try { notifyListeners("downloadProgress", JSObject.fromJSONObject(update)); } catch (Exception ignored) {}
        });
        DownloadService.ensureChannels(getContext());
    }

    static void emit(String event, boolean value) {
        StreambertPlugin p = instance;
        if (p == null) return;
        JSObject o = new JSObject();
        o.put("value", value);
        p.notifyListeners(event, o);
    }

    static void onMediaFound(String url, Map<String, String> headers, boolean subtitle) {
        if (!subtitle && !streamMeta.containsKey(url)) {
            Map<String, String> m = new HashMap<>();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey().toLowerCase();
                if (k.equals("referer") || k.equals("origin") || k.equals("user-agent")) m.put(k, e.getValue());
            }
            streamMeta.put(url, m);
        }
        StreambertPlugin p = instance;
        if (p == null) return;
        JSObject o = new JSObject();
        o.put("url", url);
        p.notifyListeners(subtitle ? "subtitleFound" : "m3u8Found", o);
    }

    private static Map<String, String> headersFor(String url, JSObject extra) {
        Map<String, String> h = new HashMap<>();
        Map<String, String> meta = streamMeta.get(url);
        if (meta != null) {
            if (meta.containsKey("referer")) h.put("Referer", meta.get("referer"));
            if (meta.containsKey("origin")) h.put("Origin", meta.get("origin"));
            if (meta.containsKey("user-agent")) h.put("User-Agent", meta.get("user-agent"));
        }
        if (extra != null) {
            for (Iterator<String> it = extra.keys(); it.hasNext(); ) {
                String k = it.next();
                h.put(k, extra.optString(k));
            }
        }
        return h;
    }

    // ───────────── HTTP (no CORS) ─────────────

    @PluginMethod
    public void http(PluginCall call) {
        io.execute(() -> {
            try {
                String url = call.getString("url");
                String method = call.getString("method", "GET");
                JSObject hdrs = call.getObject("headers", new JSObject());
                Map<String, String> h = new HashMap<>();
                for (Iterator<String> it = hdrs.keys(); it.hasNext(); ) {
                    String k = it.next();
                    h.put(k, hdrs.optString(k));
                }
                String body = call.getString("body");
                String rt = call.getString("responseType", "text");
                int timeout = call.getInt("timeout", 15000);
                Net.Response r = Net.open(url, method, h, body != null ? body.getBytes(StandardCharsets.UTF_8) : null, timeout);
                byte[] data = r.bytes(60L * 1024 * 1024);
                JSObject res = new JSObject();
                res.put("status", r.status);
                res.put("url", r.url);
                JSObject rh = new JSObject();
                for (Map.Entry<String, String> e : r.headers.entrySet()) rh.put(e.getKey(), e.getValue());
                res.put("headers", rh);
                res.put("data", "base64".equals(rt) ? Base64.encodeToString(data, Base64.NO_WRAP) : new String(data, StandardCharsets.UTF_8));
                call.resolve(res);
            } catch (Exception e) {
                call.reject(e.getMessage() != null ? e.getMessage() : "request failed");
            }
        });
    }

    // ───────────── files ─────────────

    @PluginMethod
    public void writeFile(PluginCall call) {
        io.execute(() -> {
            try {
                File f = new File(call.getString("path"));
                //noinspection ResultOfMethodCallIgnored
                f.getParentFile().mkdirs();
                String data = call.getString("data", "");
                byte[] bytes = "base64".equals(call.getString("encoding")) ? Base64.decode(data, Base64.DEFAULT) : data.getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
                JSObject r = new JSObject();
                r.put("path", f.getAbsolutePath());
                call.resolve(r);
            } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
        });
    }

    @PluginMethod
    public void readFile(PluginCall call) {
        io.execute(() -> {
            try {
                byte[] b = Files.readAllBytes(new File(call.getString("path")).toPath());
                JSObject r = new JSObject();
                r.put("data", "base64".equals(call.getString("encoding")) ? Base64.encodeToString(b, Base64.NO_WRAP) : new String(b, StandardCharsets.UTF_8));
                call.resolve(r);
            } catch (Exception e) { call.reject(String.valueOf(e.getMessage())); }
        });
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        File f = new File(call.getString("path", "/nonexistent"));
        JSObject r = new JSObject();
        r.put("ok", f.isDirectory() ? Downloads.deleteRecursive(f) : f.delete());
        call.resolve(r);
    }

    @PluginMethod
    public void fileExists(PluginCall call) {
        String p = call.getString("path");
        JSObject r = new JSObject();
        r.put("exists", p != null && new File(p).exists());
        call.resolve(r);
    }

    @PluginMethod
    public void getPaths(PluginCall call) {
        JSObject r = new JSObject();
        r.put("downloads", Downloads.get(getContext()).baseDir().getAbsolutePath());
        r.put("files", getContext().getFilesDir().getAbsolutePath());
        r.put("cache", getContext().getCacheDir().getAbsolutePath());
        try {
            r.put("version", getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName);
        } catch (Exception ignored) {}
        call.resolve(r);
    }

    @PluginMethod
    public void scanDirectory(PluginCall call) {
        io.execute(() -> {
            JSArray out = new JSArray();
            scan(new File(call.getString("path", "")), 0, out);
            JSObject r = new JSObject();
            r.put("files", out);
            call.resolve(r);
        });
    }

    private void scan(File dir, int depth, JSArray out) {
        if (depth > 3 || dir == null) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) { scan(k, depth + 1, out); continue; }
            String n = k.getName();
            int dot = n.lastIndexOf('.');
            String ext = dot >= 0 ? n.substring(dot).toLowerCase() : "";
            if (!(ext.equals(".mp4") || ext.equals(".mkv") || ext.equals(".webm") || ext.equals(".mov") || ext.equals(".m4v") || ext.equals(".ts"))) continue;
            JSObject o = new JSObject();
            o.put("filePath", k.getAbsolutePath());
            o.put("name", dot >= 0 ? n.substring(0, dot) : n);
            o.put("size", Downloads.human(k.length()));
            o.put("ext", ext);
            out.put(o);
        }
    }

    // ───────────── downloads ─────────────

    @PluginMethod
    public void startDownload(PluginCall call) {
        requestNotificationPermission();
        String url = call.getString("m3u8Url");
        Map<String, String> h = headersFor(url, call.getObject("headers"));
        String id = Downloads.get(getContext()).start(call.getData(), h);
        JSObject r = new JSObject();
        r.put("ok", true);
        r.put("id", id);
        call.resolve(r);
    }

    @PluginMethod
    public void getDownloads(PluginCall call) {
        JSObject r = new JSObject();
        try { r.put("list", new JSArray(Downloads.get(getContext()).all().toString())); } catch (Exception e) { r.put("list", new JSArray()); }
        call.resolve(r);
    }

    @PluginMethod
    public void deleteDownload(PluginCall call) {
        Downloads.get(getContext()).delete(call.getString("id", ""), call.getBoolean("deleteFiles", true));
        JSObject r = new JSObject();
        r.put("ok", true);
        call.resolve(r);
    }

    @PluginMethod
    public void deleteAllDownloads(PluginCall call) {
        io.execute(() -> {
            int n = Downloads.get(getContext()).deleteAll();
            JSObject r = new JSObject();
            r.put("ok", true);
            r.put("deleted", n);
            r.put("errors", 0);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void clearDownloadRegistry(PluginCall call) {
        Downloads.get(getContext()).clearRegistry();
        call.resolve();
    }

    @PluginMethod
    public void updateDownload(PluginCall call) {
        JSObject patch = call.getObject("patch", new JSObject());
        JSObject r = new JSObject();
        r.put("ok", Downloads.get(getContext()).update(call.getString("id", ""), patch));
        call.resolve(r);
    }

    @PluginMethod
    public void getDownloadsSize(PluginCall call) {
        io.execute(() -> {
            JSObject r = new JSObject();
            r.put("bytes", Downloads.get(getContext()).totalSize());
            call.resolve(r);
        });
    }

    @PluginMethod
    public void getVideoDuration(PluginCall call) {
        io.execute(() -> {
            JSObject r = new JSObject();
            try {
                MediaMetadataRetriever m = new MediaMetadataRetriever();
                m.setDataSource(call.getString("path"));
                String d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                m.release();
                r.put("seconds", d != null ? Long.parseLong(d) / 1000.0 : null);
            } catch (Exception e) { r.put("seconds", null); }
            call.resolve(r);
        });
    }

    // ───────────── block stats ─────────────

    @PluginMethod
    public void getBlockStats(PluginCall call) {
        try { call.resolve(JSObject.fromJSONObject(AdBlock.stats())); } catch (Exception e) { call.resolve(new JSObject()); }
    }

    @PluginMethod
    public void getStreamMeta(PluginCall call) {
        Map<String, String> m = streamMeta.get(call.getString("url", ""));
        JSObject r = new JSObject();
        if (m != null) for (Map.Entry<String, String> e : m.entrySet()) r.put(e.getKey(), e.getValue());
        call.resolve(r);
    }

    // ───────────── system ─────────────

    @PluginMethod
    public void openExternal(PluginCall call) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(call.getString("url")));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) { call.reject("No app can open this link"); }
    }

    /** Opens a stream (m3u8) or a local file in an external video player (VLC, MX Player, …). */
    @PluginMethod
    public void openInPlayer(PluginCall call) {
        String url = call.getString("url", "");
        String title = call.getString("title", "Streambert");
        try {
            Uri uri;
            String type;
            if (url.startsWith("/")) {
                uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", new File(url));
                type = url.endsWith(".m3u8") ? "application/x-mpegURL" : "video/*";
            } else {
                uri = Uri.parse(url);
                type = url.toLowerCase().contains(".m3u8") ? "application/x-mpegURL" : "video/*";
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, type);
            i.putExtra("title", title);
            Map<String, String> h = headersFor(url, null);
            if (!h.isEmpty()) {
                // MX Player / Just Player header extra
                String[] arr = new String[h.size() * 2];
                int k = 0;
                for (Map.Entry<String, String> e : h.entrySet()) { arr[k++] = e.getKey(); arr[k++] = e.getValue(); }
                i.putExtra("headers", arr);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            JSObject r = new JSObject();
            r.put("ok", true);
            r.put("player", "external");
            call.resolve(r);
        } catch (ActivityNotFoundException e) {
            JSObject r = new JSObject();
            r.put("ok", false);
            r.put("error", "No video player app installed (try VLC)");
            call.resolve(r);
        } catch (Exception e) {
            JSObject r = new JSObject();
            r.put("ok", false);
            r.put("error", String.valueOf(e.getMessage()));
            call.resolve(r);
        }
    }

    @PluginMethod
    public void showNotification(PluginCall call) {
        try {
            requestNotificationPermission();
            NotificationManager nm = (NotificationManager) getContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            DownloadService.ensureChannels(getContext());
            nm.notify((int) (System.currentTimeMillis() % 100000) + 10, new NotificationCompat.Builder(getContext(), "events")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(call.getString("title", "Streambert"))
                    .setContentText(call.getString("body", ""))
                    .setAutoCancel(true)
                    .build());
        } catch (Exception ignored) {}
        call.resolve();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && getActivity() != null &&
                ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            getActivity().runOnUiThread(() -> ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77));
        }
    }

    @PluginMethod
    public void setKeepScreenOn(PluginCall call) {
        boolean on = call.getBoolean("on", false);
        getActivity().runOnUiThread(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        call.resolve();
    }

    @PluginMethod
    public void setFullscreen(PluginCall call) {
        boolean on = call.getBoolean("on", false);
        getActivity().runOnUiThread(() -> {
            MainActivity a = (MainActivity) getActivity();
            a.chrome().setImmersive(on);
            a.setRequestedOrientation(on ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        });
        call.resolve();
    }

    @PluginMethod
    public void exitFullscreen(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            MainActivity a = (MainActivity) getActivity();
            if (a.chrome().isFullscreen()) a.chrome().onHideCustomView();
        });
        call.resolve();
    }

    @PluginMethod
    public void exitApp(PluginCall call) {
        call.resolve();
        getActivity().runOnUiThread(() -> getActivity().finishAndRemoveTask());
    }

    @PluginMethod
    public void getCacheSize(PluginCall call) {
        io.execute(() -> {
            JSObject r = new JSObject();
            long s = Downloads.sizeOf(getContext().getCacheDir());
            File wv = new File(getContext().getDataDir(), "app_webview");
            File c = new File(wv, "Default/HTTP Cache");
            s += Downloads.sizeOf(c);
            r.put("bytes", s);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void clearCache(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try { getBridge().getWebView().clearCache(true); } catch (Exception ignored) {}
            io.execute(() -> {
                File[] kids = getContext().getCacheDir().listFiles();
                if (kids != null) for (File k : kids) Downloads.deleteRecursive(k);
                JSObject r = new JSObject();
                r.put("ok", true);
                call.resolve(r);
            });
        });
    }

    @PluginMethod
    public void clearCookies(PluginCall call) {
        CookieManager.getInstance().removeAllCookies(v -> {
            JSObject r = new JSObject();
            r.put("ok", true);
            call.resolve(r);
        });
    }
}
