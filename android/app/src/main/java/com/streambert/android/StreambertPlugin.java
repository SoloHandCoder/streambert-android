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
        try { EpisodeCheckWorker.schedule(getContext()); } catch (Exception ignored) {}
        // continue downloads interrupted by an app kill / waiting for network
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> Downloads.get(getContext()).resumeAuto(), 3000);
        MediaControl.get(getContext()).setListener((cmd, value) -> {
            JSObject o = new JSObject();
            o.put("cmd", cmd);
            o.put("value", value);
            notifyListeners("mediaCommand", o);
        });
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
        String dp = call.getString("path", "/nonexistent");
        File f = new File(dp);
        JSObject r = new JSObject();
        r.put("ok", StorageMover.isContent(dp) ? StorageMover.deletePath(getContext(), dp)
                : f.isDirectory() ? Downloads.deleteRecursive(f) : f.delete());
        call.resolve(r);
    }

    @PluginMethod
    public void fileExists(PluginCall call) {
        String p = call.getString("path");
        JSObject r = new JSObject();
        r.put("exists", StorageMover.exists(getContext(), p));
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
    public void pauseDownload(PluginCall call) {
        Downloads.get(getContext()).pause(call.getString("id", ""), "Paused", false);
        call.resolve();
    }

    @PluginMethod
    public void resumeDownload(PluginCall call) {
        Downloads.get(getContext()).resume(call.getString("id", ""));
        call.resolve();
    }

    @PluginMethod
    public void getDownloadSettings(PluginCall call) {
        Downloads d = Downloads.get(getContext());
        JSObject r = new JSObject();
        r.put("wifiOnly", d.wifiOnly());
        r.put("convertMp4", d.convertMp4());
        r.put("location", d.location());
        r.put("folderName", d.treeName());
        r.put("moviesPath", StorageMover.moviesDir().getAbsolutePath());
        r.put("networkAllowed", d.networkAllowed());
        call.resolve(r);
    }

    @PluginMethod
    public void setDownloadSettings(PluginCall call) {
        Downloads d = Downloads.get(getContext());
        if (call.getData().has("wifiOnly")) d.setWifiOnly(call.getBoolean("wifiOnly", false));
        if (call.getData().has("convertMp4")) d.setConvertMp4(call.getBoolean("convertMp4", true));
        String loc = call.getString("location");
        if ("app".equals(loc)) d.setLocation("app", null, null);
        if ("movies".equals(loc)) {
            if (StorageMover.needsLegacyPermission() && getActivity() != null
                    && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                getActivity().runOnUiThread(() -> ActivityCompat.requestPermissions(getActivity(),
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 78));
            }
            d.setLocation("movies", null, null);
        }
        if ("custom".equals(loc) && d.treeUri() != null) d.setLocation("custom", null, null);
        getDownloadSettings(call);
    }

    /** Opens the system folder picker; the picked folder becomes the save location. */
    @PluginMethod
    public void pickDownloadFolder(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        AppLock.suppressUntil = System.currentTimeMillis() + 5 * 60_000L;
        startActivityForResult(call, i, "pickFolderResult");
    }

    @com.getcapacitor.annotation.ActivityCallback
    private void pickFolderResult(PluginCall call, androidx.activity.result.ActivityResult result) {
        if (call == null) return;
        JSObject r = new JSObject();
        Uri tree = result.getData() != null ? result.getData().getData() : null;
        if (result.getResultCode() != android.app.Activity.RESULT_OK || tree == null) {
            r.put("ok", false);
            call.resolve(r);
            return;
        }
        try {
            getContext().getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {}
        String name = StorageMover.displayName(getContext(), tree);
        Downloads.get(getContext()).setLocation("custom", tree.toString(), name);
        r.put("ok", true);
        r.put("folderName", name);
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
                    .setSmallIcon(R.drawable.ic_stat_streambert)
                    .setColor(0xFFE50914)
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


    // ───────────── playback state: media session, PiP, brightness, volume ─────────────

    @PluginMethod
    public void setPlaybackState(PluginCall call) {
        boolean active = call.getBoolean("active", false);
        boolean playing = call.getBoolean("playing", false);
        MediaControl.get(getContext()).update(active, playing, call.getString("title", ""),
                call.getString("subtitle", ""), call.getDouble("position", 0.0), call.getDouble("duration", 0.0));
        MainActivity a = (MainActivity) getActivity();
        if (a != null) a.runOnUiThread(() -> a.updatePipParams(active && playing, call.getDouble("aspect", 16.0 / 9.0)));
        call.resolve();
    }

    // ───────────── home-screen widget ─────────────

    @PluginMethod
    public void updateWidget(PluginCall call) {
        JSArray items = call.getArray("items", new JSArray());
        io.execute(() -> ContinueWidget.save(getContext(), items));
        call.resolve();
    }

    /** Asks the launcher to add the widget (Android shows a confirm dialog). */
    @PluginMethod
    public void pinWidget(PluginCall call) {
        JSObject r = new JSObject();
        try {
            android.appwidget.AppWidgetManager m = android.appwidget.AppWidgetManager.getInstance(getContext());
            boolean ok = Build.VERSION.SDK_INT >= 26 && m.isRequestPinAppWidgetSupported()
                    && m.requestPinAppWidget(new android.content.ComponentName(getContext(), ContinueWidget.class), null, null);
            r.put("ok", ok);
        } catch (Exception e) {
            r.put("ok", false);
        }
        call.resolve(r);
    }

    // ───────────── app lock ─────────────

    @PluginMethod
    public void getLockSettings(PluginCall call) {
        android.content.SharedPreferences p = getContext().getSharedPreferences(AppLock.PREFS, android.content.Context.MODE_PRIVATE);
        int avail = AppLock.availability(getContext());
        JSObject r = new JSObject();
        r.put("enabled", p.getBoolean("enabled", false));
        r.put("timeoutMs", p.getLong("timeoutMs", 60_000L));
        r.put("available", avail == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS);
        r.put("reason", avail == androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                ? "Set up a screen lock (PIN, pattern, password or fingerprint) on your phone first."
                : avail == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS ? "" : "This device can't verify your identity.");
        call.resolve(r);
    }

    /** Changing the lock always asks for verification first. */
    @PluginMethod
    public void setLockSettings(PluginCall call) {
        android.content.SharedPreferences p = getContext().getSharedPreferences(AppLock.PREFS, android.content.Context.MODE_PRIVATE);
        Boolean enabled = call.getData().has("enabled") ? call.getBoolean("enabled") : null;
        Long timeout = call.getData().has("timeoutMs") ? call.getLong("timeoutMs") : null;
        Runnable apply = () -> {
            android.content.SharedPreferences.Editor ed = p.edit();
            if (enabled != null) ed.putBoolean("enabled", enabled);
            if (timeout != null) ed.putLong("timeoutMs", timeout);
            ed.apply();
            getLockSettings(call);
        };
        if (enabled == null && !p.getBoolean("enabled", false)) { apply.run(); return; }
        if (AppLock.availability(getContext()) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            if (Boolean.TRUE.equals(enabled)) { call.reject("Set up a screen lock on your phone first."); return; }
            apply.run();
            return;
        }
        getActivity().runOnUiThread(() -> AppLock.authenticate((androidx.appcompat.app.AppCompatActivity) getActivity(),
                Boolean.TRUE.equals(enabled) ? "Turn on app lock" : "Change app lock", ok -> {
                    if (ok) apply.run();
                    else call.reject("Not verified");
                }));
    }

    // ───────────── background new-episode check ─────────────

    @PluginMethod
    public void syncEpisodeWatch(PluginCall call) {
        android.content.SharedPreferences p = getContext().getSharedPreferences(EpisodeCheckWorker.PREFS, android.content.Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = p.edit();
        if (call.getData().has("token")) ed.putString("token", call.getString("token", ""));
        if (call.getData().has("enabled")) ed.putBoolean("enabled", call.getBoolean("enabled", true));
        JSArray shows = call.getArray("shows");
        if (shows != null) ed.putString("shows", shows.toString());
        ed.apply();
        JSObject st = call.getObject("state");
        if (st != null) EpisodeCheckWorker.mergeState(p, st);
        call.resolve();
    }

    @PluginMethod
    public void getEpisodeState(PluginCall call) {
        android.content.SharedPreferences p = getContext().getSharedPreferences(EpisodeCheckWorker.PREFS, android.content.Context.MODE_PRIVATE);
        JSObject r = new JSObject();
        try { r.put("state", new JSObject(p.getString("state", "{}"))); } catch (Exception e) { r.put("state", new JSObject()); }
        r.put("lastRun", p.getLong("lastRun", 0));
        call.resolve(r);
    }

    @PluginMethod
    public void runEpisodeCheckNow(PluginCall call) {
        EpisodeCheckWorker.runNow(getContext());
        call.resolve();
    }

    @PluginMethod
    public void setInterceptFullscreen(PluginCall call) {
        SbChromeClient.interceptFullscreen = call.getBoolean("on", false);
        call.resolve();
    }

    @PluginMethod
    public void enterPip(PluginCall call) {
        MainActivity a = (MainActivity) getActivity();
        JSObject r = new JSObject();
        if (a == null) { r.put("ok", false); call.resolve(r); return; }
        a.runOnUiThread(() -> {
            r.put("ok", a.enterPip());
            call.resolve(r);
        });
    }

    @PluginMethod
    public void setBrightness(PluginCall call) {
        double v = call.getDouble("value", -1.0);
        getActivity().runOnUiThread(() -> {
            android.view.WindowManager.LayoutParams lp = getActivity().getWindow().getAttributes();
            lp.screenBrightness = v < 0 ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    : (float) Math.max(0.01, Math.min(1.0, v));
            getActivity().getWindow().setAttributes(lp);
        });
        call.resolve();
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int v = (int) Math.round(Math.max(0, Math.min(1, call.getDouble("value", 0.5))) * max);
        try { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0); } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void getLevels(PluginCall call) {
        android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
        int max = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
        double vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / (double) max;
        getActivity().runOnUiThread(() -> {
            float b = getActivity().getWindow().getAttributes().screenBrightness;
            double bright = b;
            if (b < 0) {
                try {
                    bright = android.provider.Settings.System.getInt(getContext().getContentResolver(),
                            android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255.0;
                } catch (Exception e) { bright = 0.5; }
            }
            JSObject r = new JSObject();
            r.put("brightness", bright);
            r.put("volume", vol);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void getDeviceInfo(PluginCall call) {
        android.app.UiModeManager um = (android.app.UiModeManager) getContext().getSystemService(android.content.Context.UI_MODE_SERVICE);
        JSObject r = new JSObject();
        r.put("tv", um != null && um.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION);
        r.put("pip", Build.VERSION.SDK_INT >= 26 && getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE));
        call.resolve(r);
    }

    // ───────────── incoming shortcuts / shared links ─────────────
    private static volatile String pendingIncoming = null;

    static void deliverIncoming(String text) {
        pendingIncoming = text;
        StreambertPlugin p = instance;
        if (p != null && p.hasListeners("incoming")) {
            JSObject o = new JSObject();
            o.put("text", text);
            pendingIncoming = null;
            p.notifyListeners("incoming", o);
        }
    }

    /** Returns (and clears) a link/shortcut that arrived before the page was ready. */
    @PluginMethod
    public void takeIncoming(PluginCall call) {
        JSObject r = new JSObject();
        r.put("text", pendingIncoming);
        pendingIncoming = null;
        call.resolve(r);
    }

    // ───────────── self update (APK from GitHub releases) ─────────────

    private volatile boolean updateCancelled = false;

    @PluginMethod
    public void installUpdate(PluginCall call) {
        updateCancelled = false;
        String url = call.getString("url", "");
        io.execute(() -> {
            JSObject res = new JSObject();
            try {
                if (Build.VERSION.SDK_INT >= 26 && !getContext().getPackageManager().canRequestPackageInstalls()) {
                    AppLock.suppressUntil = System.currentTimeMillis() + 5 * 60_000L;
                    Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getContext().getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(i);
                    JSObject w = new JSObject();
                    w.put("percent", 0);
                    w.put("label", "Allow \"Install unknown apps\" for Streambert, then come back…");
                    notifyListeners("updateProgress", w);
                    // wait (up to 3 min) until the user grants it, then continue
                    long until = System.currentTimeMillis() + 180_000;
                    while (!getContext().getPackageManager().canRequestPackageInstalls()) {
                        if (updateCancelled || System.currentTimeMillis() > until) {
                            res.put("ok", false);
                            res.put("error", updateCancelled ? "Cancelled" : "Permission \"Install unknown apps\" was not granted.");
                            call.resolve(res);
                            return;
                        }
                        Thread.sleep(500);
                    }
                }
                Map<String, String> h = new HashMap<>();
                h.put("Accept", "application/octet-stream");
                Net.Response r = Net.open(url, "GET", h, null, 20000);
                if (r.status >= 400) { r.conn.disconnect(); throw new java.io.IOException("HTTP " + r.status); }
                long total = -1;
                try { total = Long.parseLong(r.headers.getOrDefault("content-length", "-1")); } catch (Exception ignored) {}
                File dir = new File(getContext().getCacheDir(), "update");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File apk = new File(dir, "streambert-update.apk");
                long got = 0, last = 0;
                try (java.io.InputStream in = r.body; FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (updateCancelled) throw new java.io.IOException("cancelled");
                        out.write(buf, 0, n);
                        got += n;
                        long now = System.currentTimeMillis();
                        if (now - last > 250) {
                            last = now;
                            JSObject p = new JSObject();
                            p.put("percent", total > 0 ? (int) (got * 100 / total) : 0);
                            p.put("label", String.format(java.util.Locale.US, "Downloading… %.1f MB%s", got / 1e6,
                                    total > 0 ? String.format(java.util.Locale.US, " / %.1f MB", total / 1e6) : ""));
                            notifyListeners("updateProgress", p);
                        }
                    }
                } finally { r.conn.disconnect(); }
                if (apk.length() < 500_000) throw new java.io.IOException("Downloaded file is not a valid APK");
                JSObject p = new JSObject();
                p.put("percent", 100);
                p.put("label", "Launching installer…");
                notifyListeners("updateProgress", p);
                // Android blocks activity starts from the background → wait until the app is in front
                androidx.appcompat.app.AppCompatActivity act = (androidx.appcompat.app.AppCompatActivity) getActivity();
                long until2 = System.currentTimeMillis() + 300_000;
                while (!act.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    if (updateCancelled || System.currentTimeMillis() > until2) throw new java.io.IOException("cancelled");
                    Thread.sleep(300);
                }
                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", apk);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                act.runOnUiThread(() -> act.startActivity(i));
                res.put("ok", true);
                call.resolve(res);
            } catch (Exception e) {
                res.put("ok", false);
                res.put("error", "cancelled".equals(e.getMessage()) ? "Cancelled" : "Update failed: " + e.getMessage());
                call.resolve(res);
            }
        });
    }

    @PluginMethod
    public void cancelUpdate(PluginCall call) {
        updateCancelled = true;
        call.resolve();
    }
}
