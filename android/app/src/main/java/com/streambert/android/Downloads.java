package com.streambert.android;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native replacement for the desktop's external vid-dl CLI.
 * HLS: picks the best variant (+ its audio rendition), downloads every segment /
 * key / init map with 6 parallel connections and writes a local playlist that the
 * in-app player (hls.js) plays offline. Direct files (mp4) are streamed to disk.
 */
public final class Downloads {
    public interface Listener { void onProgress(JSONObject update); }

    private static Downloads instance;
    public static synchronized Downloads get(Context ctx) {
        if (instance == null) instance = new Downloads(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final File store;
    private final List<JSONObject> list = new ArrayList<>();
    private final Map<String, Job> active = new ConcurrentHashMap<>();
    private final Map<String, String> currentFallback = new ConcurrentHashMap<>();
    private final ExecutorService jobs = Executors.newFixedThreadPool(2);
    private volatile Listener listener;

    private static final class Job {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean pausing = new AtomicBoolean(false);
        Future<?> future;
        ExecutorService pool;
    }

    private final android.content.SharedPreferences prefs;

    private Downloads(Context ctx) {
        this.ctx = ctx;
        this.store = new File(ctx.getFilesDir(), "downloads.json");
        this.prefs = ctx.getSharedPreferences("sb_downloads", Context.MODE_PRIVATE);
        try {
            if (store.exists()) {
                JSONArray a = new JSONArray(new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    // downloads interrupted by app kill -> continue automatically
                    if ("downloading".equals(o.optString("status"))) {
                        o.put("status", "paused");
                        o.put("_autoResume", true);
                        o.put("speed", "");
                        o.put("lastMessage", "Interrupted, resuming…");
                    }
                    list.add(o);
                }
            }
        } catch (Exception ignored) {}
        registerNetworkMonitor();
    }

    public void setListener(Listener l) { listener = l; }

    // ───────────────────────── settings ─────────────────────────

    public boolean wifiOnly() { return prefs.getBoolean("wifiOnly", false); }
    public boolean convertMp4() { return prefs.getBoolean("convertMp4", true); }
    /** "app" (private app storage), "movies" (public Movies/Streambert) or "custom" (picked folder). */
    public String location() { return prefs.getString("location", "app"); }
    public String treeUri() { return prefs.getString("treeUri", null); }
    public String treeName() { return prefs.getString("treeName", null); }

    public void setWifiOnly(boolean v) {
        prefs.edit().putBoolean("wifiOnly", v).apply();
        onNetworkChanged();
    }
    public void setConvertMp4(boolean v) { prefs.edit().putBoolean("convertMp4", v).apply(); }
    public void setLocation(String loc, String tree, String name) {
        android.content.SharedPreferences.Editor ed = prefs.edit().putString("location", loc);
        if (tree != null) ed.putString("treeUri", tree).putString("treeName", name);
        ed.apply();
    }

    // ───────────────────────── network (Wi-Fi only) ─────────────────────────

    private boolean computeNetworkAllowed() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities c = n != null ? cm.getNetworkCapabilities(n) : null;
            if (c == null || !c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
            if (!wifiOnly()) return true;
            return c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    || c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET);
        } catch (Exception e) {
            return true;
        }
    }

    public boolean networkAllowed() { return computeNetworkAllowed(); }

    public String waitReason() {
        return wifiOnly() ? "Waiting for Wi-Fi…" : "Waiting for network…";
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable netCheck = this::onNetworkChanged;

    private void registerNetworkMonitor() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.ConnectivityManager.NetworkCallback cb = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network network) { schedule(); }
                @Override public void onLost(android.net.Network network) { schedule(); }
                @Override public void onCapabilitiesChanged(android.net.Network network, android.net.NetworkCapabilities nc) { schedule(); }
                private void schedule() { main.removeCallbacks(netCheck); main.postDelayed(netCheck, 1500); }
            };
            cm.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb);
        } catch (Exception ignored) {}
    }

    /** Pauses running downloads when the network isn't allowed, resumes waiting ones when it is. */
    public synchronized void onNetworkChanged() {
        if (!computeNetworkAllowed()) {
            for (String id : new ArrayList<>(active.keySet())) pause(id, waitReason(), true);
        } else {
            resumeAuto();
        }
    }

    /** Resumes downloads that were paused automatically (app killed / waiting for network). */
    public synchronized void resumeAuto() {
        if (!computeNetworkAllowed()) return;
        for (JSONObject o : new ArrayList<>(list)) {
            if ("paused".equals(o.optString("status")) && o.optBoolean("_autoResume", false)) resume(o.optString("id"));
        }
    }

    public File baseDir() {
        File d = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (d == null) d = new File(ctx.getFilesDir(), "Movies");
        File b = new File(d, "Streambert");
        //noinspection ResultOfMethodCallIgnored
        b.mkdirs();
        return b;
    }

    public synchronized JSONArray all() {
        JSONArray a = new JSONArray();
        for (JSONObject o : list) a.put(o);
        return a;
    }

    public synchronized int activeCount() { return active.size(); }

    private synchronized void save() {
        try (FileOutputStream f = new FileOutputStream(store)) {
            f.write(all().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private synchronized JSONObject find(String id) {
        for (JSONObject o : list) if (id.equals(o.optString("id"))) return o;
        return null;
    }

    public synchronized boolean update(String id, JSONObject patch) {
        JSONObject o = find(id);
        if (o == null) return false;
        try {
            for (java.util.Iterator<String> it = patch.keys(); it.hasNext(); ) {
                String k = it.next();
                o.put(k, patch.get(k));
            }
        } catch (Exception ignored) {}
        save();
        return true;
    }

    private void emit(String id, Object... kv) {
        JSONObject u = new JSONObject();
        try {
            u.put("id", id);
            for (int i = 0; i + 1 < kv.length; i += 2) u.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) {}
        update(id, u);
        Listener l = listener;
        if (l != null) {
            JSONObject full;
            synchronized (this) {
                JSONObject o = find(id);
                full = o != null ? o : u;
            }
            try { l.onProgress(new JSONObject(full.toString())); } catch (Exception ignored) {}
        }
        DownloadService.refresh(ctx);
    }

    /** Starts a download, returns its id. */
    public String start(JSONObject args, Map<String, String> headers) {
        String id = UUID.randomUUID().toString();
        JSONObject e = new JSONObject();
        try {
            e.put("id", id);
            e.put("name", args.optString("name", "Video"));
            e.put("m3u8Url", args.optString("m3u8Url"));
            e.put("downloadPath", baseDir().getAbsolutePath());
            e.put("filePath", JSONObject.NULL);
            e.put("status", "downloading");
            e.put("progress", 0);
            e.put("speed", "");
            e.put("size", "");
            e.put("totalFragments", 0);
            e.put("completedFragments", 0);
            e.put("lastMessage", "Queued");
            JSONObject hj = new JSONObject();
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) hj.put(h.getKey(), h.getValue());
            e.put("_headers", hj);
            String fbu = args.optString("fallbackUrl", "");
            if (!fbu.isEmpty() && !"null".equals(fbu)) e.put("_fallback", fbu);
            e.put("startedAt", System.currentTimeMillis());
            e.put("completedAt", JSONObject.NULL);
            for (String k : new String[]{"mediaId", "mediaType", "season", "episode", "posterPath", "tmdbId"}) {
                e.put(k, args.has(k) ? args.get(k) : JSONObject.NULL);
            }
            if (e.isNull("tmdbId") && !e.isNull("mediaId")) e.put("tmdbId", e.get("mediaId"));
            e.put("subtitles", args.optJSONArray("subtitles") != null ? args.optJSONArray("subtitles") : new JSONArray());
            e.put("subtitlePaths", new JSONArray());
        } catch (Exception ignored) {}

        synchronized (this) {
            // replace stale entries for the same media (like desktop)
            String tmdb = e.optString("tmdbId"), type = e.optString("mediaType"),
                    s = e.optString("season"), ep = e.optString("episode");
            if (!tmdb.isEmpty() && !"null".equals(tmdb)) {
                list.removeIf(d -> !active.containsKey(d.optString("id")) && tmdb.equals(d.optString("tmdbId"))
                        && type.equals(d.optString("mediaType")) && s.equals(d.optString("season")) && ep.equals(d.optString("episode")));
            }
            list.add(0, e);
            save();
        }
        if (!computeNetworkAllowed()) {
            emit(id, "status", "paused", "_autoResume", true, "lastMessage", waitReason());
            return id;
        }
        submit(id, e);
        return id;
    }

    private Map<String, String> headersOf(JSONObject e) {
        Map<String, String> h = new HashMap<>();
        JSONObject hj = e.optJSONObject("_headers");
        if (hj != null) for (java.util.Iterator<String> it = hj.keys(); it.hasNext(); ) {
            String k = it.next();
            h.put(k, hj.optString(k));
        }
        return h;
    }

    private synchronized void submit(String id, JSONObject e) {
        if (active.containsKey(id)) return;
        String fb = e.optString("_fallback", "");
        if (!fb.isEmpty()) currentFallback.put(id, fb);
        Job job = new Job();
        active.put(id, job);
        DownloadService.refresh(ctx);
        Map<String, String> headers = headersOf(e);
        job.future = jobs.submit(() -> run(id, e, headers, job));
    }

    /** Stops a running download but keeps everything downloaded so far. */
    public synchronized void pause(String id, String reason, boolean autoResume) {
        Job j = active.remove(id);
        if (j != null) {
            j.pausing.set(true);
            j.cancelled.set(true);
            if (j.pool != null) j.pool.shutdownNow();
            if (j.future != null) j.future.cancel(true);
        }
        JSONObject o = find(id);
        if (o != null && !"completed".equals(o.optString("status"))) {
            emit(id, "status", "paused", "speed", "", "_autoResume", autoResume,
                    "lastMessage", reason != null ? reason : "Paused");
        }
        DownloadService.refresh(ctx);
    }

    /** Continues a paused (or failed) download from where it stopped. */
    public synchronized void resume(String id) {
        JSONObject o = find(id);
        if (o == null || active.containsKey(id)) return;
        String st = o.optString("status");
        if (!"paused".equals(st) && !"error".equals(st)) return;
        if (!computeNetworkAllowed()) {
            emit(id, "status", "paused", "_autoResume", true, "lastMessage", waitReason());
            return;
        }
        emit(id, "status", "downloading", "_autoResume", false, "lastMessage", "Resuming…", "speed", "");
        submit(id, o);
    }

    public void cancel(String id) {
        Job j = active.remove(id);
        if (j != null) {
            j.cancelled.set(true);
            if (j.pool != null) j.pool.shutdownNow();
            if (j.future != null) j.future.cancel(true);
        }
        DownloadService.refresh(ctx);
    }

    public synchronized void delete(String id, boolean deleteFiles) {
        cancel(id);
        JSONObject o = find(id);
        if (o != null) {
            if (deleteFiles) {
                deleteMedia(o);
            }
            list.remove(o);
            save();
        }
    }

    public synchronized int deleteAll() {
        int n = 0;
        for (JSONObject o : new ArrayList<>(list)) {
            cancel(o.optString("id"));
            if (deleteMedia(o)) n++;
        }
        list.clear();
        save();
        return n;
    }

    public synchronized void clearRegistry() {
        for (JSONObject o : new ArrayList<>(list)) cancel(o.optString("id"));
        list.clear();
        save();
    }

    private boolean deleteMedia(JSONObject o) {
        boolean ok = false;
        String fp = o.optString("filePath", "");
        String dir = o.optString("_dir", "");
        if (!dir.isEmpty()) ok = deleteRecursive(new File(dir));
        else if (!fp.isEmpty() && !"null".equals(fp)) ok = StorageMover.deletePath(ctx, fp);
        JSONArray subs = o.optJSONArray("subtitlePaths");
        if (subs != null) for (int i = 0; i < subs.length(); i++) {
            JSONObject sp = subs.optJSONObject(i);
            if (sp != null) StorageMover.deletePath(ctx, sp.optString("path", ""));
        }
        return ok;
    }

    public static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return false;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        return f.delete();
    }

    public static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long s = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) s += sizeOf(k);
        return s;
    }

    public synchronized long totalSize() {
        long s = 0;
        for (JSONObject o : list) {
            String dir = o.optString("_dir", "");
            if (!dir.isEmpty()) s += sizeOf(new File(dir));
            else {
                String fp = o.optString("filePath", "");
                if (!fp.isEmpty()) s += StorageMover.sizeOfPath(ctx, fp);
            }
        }
        return s;
    }

    // ───────────────────────── worker ─────────────────────────

    static String safeName(String s) {
        String n = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        if (n.isEmpty()) n = "Video";
        if (n.length() > 80) n = n.substring(0, 80);
        return n;
    }

    static String human(double bytes) {
        if (bytes > 1e9) return String.format(Locale.US, "%.2f GB", bytes / 1e9);
        if (bytes > 1e6) return String.format(Locale.US, "%.1f MB", bytes / 1e6);
        if (bytes > 1e3) return String.format(Locale.US, "%.1f KB", bytes / 1e3);
        return ((long) bytes) + " B";
    }

    private void run(String id, JSONObject e, Map<String, String> headers, Job job) {
        String url = e.optString("m3u8Url");
        String name = safeName(e.optString("name"));
        String prevDir = e.optString("_dir", "");
        File dir = !prevDir.isEmpty() ? new File(prevDir) : new File(baseDir(), name + "_" + id.substring(0, 6));
        File partial = new File(dir, name + ".mp4.part");
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            update(id, new JSONObject().put("_dir", dir.getAbsolutePath()));
            emit(id, "lastMessage", "Starting…");
            // resume a partially downloaded direct file with a Range request
            if ("file".equals(e.optString("_kind")) && partial.exists() && partial.length() > 0) {
                String done = downloadFileResume(id, url, headers, partial, new File(dir, name + ".mp4"), job);
                if (job.cancelled.get()) return;
                finish(id, e, dir, name, headers, done, false, job);
                return;
            }
            Net.Response probe = Net.open(url, "GET", headers, null, 15000);
            String ct = probe.headers.getOrDefault("content-type", "").toLowerCase();
            // sniff the body: many hosts serve playlists from extension-less URLs
            java.io.BufferedInputStream bin = new java.io.BufferedInputStream(probe.body, 65536);
            bin.mark(64);
            byte[] head = new byte[32];
            int hn = 0, c0;
            while (hn < head.length && (c0 = bin.read(head, hn, head.length - hn)) > 0) hn += c0;
            bin.reset();
            probe.body = bin;
            String headStr = new String(head, 0, Math.max(0, hn), StandardCharsets.UTF_8).replace("﻿", "").trim();
            boolean isHls = headStr.startsWith("#EXTM3U") || url.toLowerCase().contains(".m3u8") || ct.contains("mpegurl");
            String finalPath;
            if (isHls) {
                String text = probe.text();
                if (!text.trim().startsWith("#EXTM3U")) throw new IOException("Not a valid HLS playlist (HTTP " + probe.status + ")");
                update(id, new JSONObject().put("_kind", "hls"));
                finalPath = downloadHls(id, probe.url, text, dir, headers, job);
                if (job.cancelled.get()) return;
                finish(id, e, dir, name, headers, finalPath, true, job);
            } else {
                if (probe.status >= 400) { probe.conn.disconnect(); throw new IOException("HTTP " + probe.status); }
                update(id, new JSONObject().put("_kind", "file"));
                finalPath = downloadFile(id, probe, partial, job);
                if (job.cancelled.get()) return;
                File mp4 = new File(dir, name + ".mp4");
                if (!new File(finalPath).renameTo(mp4)) throw new IOException("rename failed");
                if (mp4.length() < 64 * 1024) throw new IOException("Server returned no video (file too small)");
                finish(id, e, dir, name, headers, mp4.getAbsolutePath(), false, job);
            }
        } catch (Throwable t) {
            boolean paused = job.pausing.get();
            active.remove(id, job);
            if (paused || job.cancelled.get()) return;
            if (!computeNetworkAllowed()) {
                // connection dropped / switched to mobile data -> wait and continue later
                emit(id, "status", "paused", "_autoResume", true, "speed", "", "lastMessage", waitReason());
                return;
            }
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            emit(id, "status", "error", "lastMessage", "Error: " + msg, "speed", "");
        } finally {
            DownloadService.refresh(ctx);
        }
    }

    /** Subtitles, optional HLS -> MP4 conversion, move to the chosen save location, mark completed. */
    private void finish(String id, JSONObject e, File dir, String name, Map<String, String> headers,
                        String finalPath, boolean hls, Job job) throws Exception {
        JSONArray subPaths = downloadSubtitles(e, dir, name, headers);
        String path = finalPath;
        if (hls && convertMp4()) {
            File mp4 = new File(dir, name + ".mp4");
            try {
                emit(id, "progress", 99, "lastMessage", "Converting to MP4…", "speed", "");
                final long[] last = {0};
                Remuxer.hlsToMp4(new File(finalPath), mp4, (pct, msg) -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] > 600) {
                        last[0] = now;
                        emit(id, "lastMessage", "Converting to MP4… " + pct + "%");
                    }
                });
                // keep only the mp4 + subtitles
                File[] kids = dir.listFiles();
                if (kids != null) for (File k : kids) {
                    String n = k.getName();
                    if (k.equals(mp4) || n.endsWith(".vtt") || n.endsWith(".srt")) continue;
                    deleteRecursive(k);
                }
                path = mp4.getAbsolutePath();
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Throwable t) {
                // conversion not possible (codec etc.) -> keep the HLS version, it plays in-app
                android.util.Log.w("Streambert", "MP4 conversion failed", t);
                //noinspection ResultOfMethodCallIgnored
                mp4.delete();
            }
        }
        if (job.cancelled.get()) return;
        JSONObject moved = null;
        if (path.endsWith(".mp4") && !"app".equals(location())) {
            emit(id, "lastMessage", "Saving to " + ("custom".equals(location()) ? treeName() : "Movies") + "…");
            moved = StorageMover.move(ctx, this, new File(path), subPaths, name);
        }
        active.remove(id, job);
        if (moved != null) {
            emit(id, "subtitlePaths", moved.optJSONArray("subtitlePaths"), "status", "completed", "progress", 100,
                    "filePath", moved.optString("filePath"), "_dir", "", "_location", moved.optString("location"),
                    "completedAt", System.currentTimeMillis(), "lastMessage", "Completed", "speed", "",
                    "size", human(moved.optLong("bytes")));
            deleteRecursive(dir);
        } else {
            emit(id, "subtitlePaths", subPaths, "status", "completed", "progress", 100, "filePath", path,
                    "completedAt", System.currentTimeMillis(), "lastMessage", "Completed", "speed", "",
                    "size", human(sizeOf(dir)));
        }
    }

    /** Saves the subtitles chosen/captured before the download next to the video. */
    private JSONArray downloadSubtitles(JSONObject e, File dir, String name, Map<String, String> headers) {
        JSONArray out = new JSONArray();
        JSONArray subs = e.optJSONArray("subtitles");
        if (subs == null) return out;
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < subs.length(); i++) {
            JSONObject s = subs.optJSONObject(i);
            if (s == null) continue;
            String url = s.optString("url", "");
            String lang = s.optString("lang", "unknown").replaceAll("[^A-Za-z0-9_-]", "");
            if (lang.isEmpty()) lang = "unknown";
            if (lang.length() > 12) lang = lang.substring(0, 12);
            try {
                byte[] data;
                String ext = "vtt";
                if (url.startsWith("data:")) {
                    int comma = url.indexOf(',');
                    String meta = url.substring(5, comma);
                    String payload = url.substring(comma + 1);
                    data = meta.contains(";base64") ? android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                            : java.net.URLDecoder.decode(payload, "UTF-8").getBytes(StandardCharsets.UTF_8);
                    if (meta.toLowerCase().contains("srt") || meta.toLowerCase().contains("subrip")) ext = "srt";
                } else if (url.startsWith("http")) {
                    Net.Response r = Net.open(url, "GET", headers, null, 15000);
                    if (r.status >= 400) { r.conn.disconnect(); continue; }
                    String ct = r.headers.getOrDefault("content-type", "").toLowerCase();
                    data = r.bytes(10L * 1024 * 1024);
                    if (url.toLowerCase().split("\\?")[0].endsWith(".srt") || ct.contains("srt") || ct.contains("subrip")) ext = "srt";
                } else continue;
                if (data.length == 0) continue;
                String fname = name + "." + lang + "." + ext;
                int n = 2;
                while (used.contains(fname)) fname = name + "." + lang + "." + (n++) + "." + ext;
                used.add(fname);
                File f = new File(dir, fname);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                JSONObject sp = new JSONObject();
                sp.put("lang", s.optString("lang", "unknown"));
                sp.put("path", f.getAbsolutePath());
                sp.put("name", s.optString("name", fname));
                sp.put("file_id", s.has("file_id") ? s.get("file_id") : JSONObject.NULL);
                out.put(sp);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    private static Map<String, String> attrs(String line) {
        Map<String, String> m = new HashMap<>();
        int c = line.indexOf(':');
        if (c < 0) return m;
        Matcher mt = ATTR.matcher(line.substring(c + 1));
        while (mt.find()) {
            String v = mt.group(2);
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
            m.put(mt.group(1), v);
        }
        return m;
    }

    private static String abs(String base, String rel) {
        try {
            URL b = new URL(base);
            URL u = new URL(b, rel.trim());
            // hosts that sign playlists with ?token=… often expect the same token on
            // child requests (their player adds it client-side) → inherit it
            if (u.getQuery() == null && b.getQuery() != null && u.getHost().equalsIgnoreCase(b.getHost())
                    && b.getQuery().toLowerCase().contains("token")) {
                return u.toString() + "?" + b.getQuery();
            }
            return u.toString();
        } catch (Exception e) { return rel; }
    }

    private String downloadHls(String id, String url, String text, File dir, Map<String, String> headers, Job job) throws Exception {
        if (text.contains("#EXT-X-STREAM-INF")) {
            // master playlist → best variant
            String[] lines = text.split("\r?\n");
            long bestBw = -1;
            String bestUri = null, bestInf = null;
            Map<String, String> audioByGroup = new HashMap<>();
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.startsWith("#EXT-X-MEDIA:")) {
                    Map<String, String> a = attrs(l);
                    if ("AUDIO".equals(a.get("TYPE")) && a.containsKey("URI")) {
                        String g = a.get("GROUP-ID");
                        if (!audioByGroup.containsKey(g) || "YES".equals(a.get("DEFAULT"))) audioByGroup.put(g, abs(url, a.get("URI")));
                    }
                } else if (l.startsWith("#EXT-X-STREAM-INF")) {
                    Map<String, String> a = attrs(l);
                    long bw = 0;
                    try { bw = Long.parseLong(a.getOrDefault("BANDWIDTH", "0")); } catch (Exception ignored) {}
                    String res = a.get("RESOLUTION");
                    if (res != null && res.contains("x")) {
                        try { bw += Long.parseLong(res.split("x")[1]) * 1_000_000_000L; } catch (Exception ignored) {}
                    }
                    int j = i + 1;
                    while (j < lines.length && (lines[j].trim().isEmpty() || lines[j].trim().startsWith("#"))) j++;
                    if (j < lines.length && bw > bestBw) { bestBw = bw; bestUri = abs(url, lines[j]); bestInf = l; }
                }
            }
            if (bestUri == null) throw new IOException("No playable variant in playlist");
            Map<String, String> inf = attrs(bestInf);
            String audioUrl = inf.containsKey("AUDIO") ? audioByGroup.get(inf.get("AUDIO")) : null;
            final String fallbackUrl = this.currentFallback.get(id);

            List<Seg> segs = new ArrayList<>();
            File vDir = new File(dir, "v");
            String vText;
            try {
                vText = fetchText(bestUri, headers);
            } catch (IOException first) {
                String fb = fallbackUrl;
                if (fb == null) throw first;
                // host adds per-playlist tokens client-side: borrow the token of the
                // playlist the player loaded, else download that playlist itself
                int q = fb.indexOf('?');
                String alt = q > 0 ? bestUri + (bestUri.contains("?") ? "&" : "?") + fb.substring(q + 1) : null;
                String t = null;
                if (alt != null) { try { t = fetchText(alt, headers); bestUri = alt; } catch (IOException ignored) {} }
                if (t == null) {
                    String ft = fetchText(fb, headers);
                    List<Seg> s2 = new ArrayList<>();
                    String local = planMedia(fb, ft, dir, "", s2);
                    fetchAll(id, s2, headers, job);
                    File out = new File(dir, "index.m3u8");
                    write(out, local);
                    return out.getAbsolutePath();
                }
                vText = t;
                audioUrl = null;
            }
            String vLocal = planMedia(bestUri, vText, vDir, "", segs);
            write(new File(vDir, "index.m3u8"), vLocal);
            String aLocal;
            if (audioUrl != null) {
                File aDir = new File(dir, "a");
                String aText = fetchText(audioUrl, headers);
                aLocal = planMedia(audioUrl, aText, aDir, "", segs);
                write(new File(aDir, "index.m3u8"), aLocal);
            }
            StringBuilder m = new StringBuilder("#EXTM3U\n");
            StringBuilder si = new StringBuilder("#EXT-X-STREAM-INF:BANDWIDTH=").append(inf.getOrDefault("BANDWIDTH", "1000000"));
            if (inf.containsKey("RESOLUTION")) si.append(",RESOLUTION=").append(inf.get("RESOLUTION"));
            if (inf.containsKey("CODECS")) si.append(",CODECS=\"").append(inf.get("CODECS")).append('"');
            if (audioUrl != null) {
                m.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"a/index.m3u8\"\n");
                si.append(",AUDIO=\"aud\"");
            }
            m.append(si).append("\nv/index.m3u8\n");
            fetchAll(id, segs, headers, job);
            File master = new File(dir, "index.m3u8");
            write(master, m.toString());
            return master.getAbsolutePath();
        }
        List<Seg> segs = new ArrayList<>();
        String local = planMedia(url, text, dir, "", segs);
        fetchAll(id, segs, headers, job);
        File out = new File(dir, "index.m3u8");
        write(out, local);
        return out.getAbsolutePath();
    }

    private static final class Seg { String url; File file; }

    private String fetchText(String url, Map<String, String> headers) throws IOException {
        IOException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                Net.Response r = Net.open(url, "GET", headers, null, 15000);
                if (r.status >= 400) { r.conn.disconnect(); throw new IOException("HTTP " + r.status + " for playlist"); }
                return r.text();
            } catch (IOException e) { last = e; }
        }
        throw last;
    }

    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    /** Rewrites a media playlist to local files and queues every referenced resource. */
    private String planMedia(String base, String text, File dir, String prefix, List<Seg> out) {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        Map<String, String> seen = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        int n = 0, k = 0;
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("#")) {
                if ((t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MAP") || t.startsWith("#EXT-X-SESSION-KEY")) && t.contains("URI=\"")) {
                    Matcher m = URI_ATTR.matcher(t);
                    StringBuffer b = new StringBuffer();
                    while (m.find()) {
                        String u = m.group(1);
                        if (u.startsWith("skd:") || u.startsWith("data:")) { m.appendReplacement(b, Matcher.quoteReplacement(m.group(0))); continue; }
                        String a = abs(base, u);
                        String local = seen.get(a);
                        if (local == null) {
                            local = (t.startsWith("#EXT-X-MAP") ? "init" : "key") + (++k) + ".bin";
                            seen.put(a, local);
                            Seg s = new Seg(); s.url = a; s.file = new File(dir, local); out.add(s);
                        }
                        m.appendReplacement(b, Matcher.quoteReplacement("URI=\"" + local + "\""));
                    }
                    m.appendTail(b);
                    sb.append(b).append('\n');
                } else {
                    sb.append(t).append('\n');
                }
                continue;
            }
            String a = abs(base, t);
            String local = seen.get(a);
            if (local == null) {
                String path = a.split("\\?")[0];
                String ext = path.substring(path.lastIndexOf('/') + 1);
                int dot = ext.lastIndexOf('.');
                ext = dot >= 0 ? ext.substring(dot).toLowerCase() : ".ts";
                if (ext.length() > 6 || !ext.matches("\\.[a-z0-9]+")) ext = ".ts";
                // fake image extensions used by some hosts → keep bytes but name as .ts
                if (ext.equals(".jpg") || ext.equals(".png") || ext.equals(".html") || ext.equals(".js") || ext.equals(".css") || ext.equals(".txt")) ext = ".ts";
                local = String.format(Locale.US, "s%05d%s", ++n, ext);
                seen.put(a, local);
                Seg s = new Seg(); s.url = a; s.file = new File(dir, local); out.add(s);
            }
            sb.append(prefix).append(local).append('\n');
        }
        String res = sb.toString();
        if (!res.contains("#EXT-X-ENDLIST")) res += "#EXT-X-ENDLIST\n";
        return res;
    }

    private void fetchAll(String id, List<Seg> segs, Map<String, String> headers, Job job) throws Exception {
        final int total = segs.size();
        AtomicInteger done = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        AtomicLong lastEmit = new AtomicLong(0);
        final long t0 = System.currentTimeMillis();
        final AtomicLong windowBytes = new AtomicLong(0);
        final AtomicLong windowStart = new AtomicLong(t0);
        final String[] speedStr = {""};
        final Throwable[] err = {null};
        emit(id, "totalFragments", total, "lastMessage", "Fragment 0 / " + total);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        job.pool = pool;
        List<Future<?>> fs = new ArrayList<>();
        for (Seg s : segs) {
            fs.add(pool.submit(() -> {
                if (job.cancelled.get() || err[0] != null) return;
                if (s.file.exists() && s.file.length() > 0) { done.incrementAndGet(); return; }
                Exception last = null;
                for (int attempt = 0; attempt < 5 && !job.cancelled.get(); attempt++) {
                    try {
                        Net.Response r = Net.open(s.url, "GET", headers, null, 20000);
                        if (r.status >= 400) { r.conn.disconnect(); throw new IOException("HTTP " + r.status); }
                        File tmp = new File(s.file.getPath() + ".part");
                        try (InputStream in = r.body; FileOutputStream f = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[65536];
                            int c;
                            while ((c = in.read(buf)) > 0) {
                                if (job.cancelled.get()) throw new IOException("cancelled");
                                f.write(buf, 0, c);
                                bytes.addAndGet(c);
                                windowBytes.addAndGet(c);
                            }
                        } finally { r.conn.disconnect(); }
                        if (!tmp.renameTo(s.file)) throw new IOException("rename failed");
                        last = null;
                        break;
                    } catch (Exception ex) {
                        last = ex;
                        try { Thread.sleep(600L * (attempt + 1)); } catch (InterruptedException ie) { return; }
                    }
                }
                if (last != null && !job.cancelled.get()) { err[0] = last; return; }
                int d = done.incrementAndGet();
                long now = System.currentTimeMillis();
                long prev = lastEmit.get();
                if (!job.cancelled.get() && (now - prev > 400 || d == total) && lastEmit.compareAndSet(prev, now)) {
                    long ws = windowStart.get();
                    if (now - ws > 1500) {
                        double bps = windowBytes.getAndSet(0) * 1000.0 / (now - ws);
                        windowStart.set(now);
                        speedStr[0] = String.format(Locale.US, "%.2f MB/s", bps / 1e6);
                    }
                    double est = d > 0 ? (double) bytes.get() / d * total : 0;
                    int pct = (int) Math.min(99, Math.floor(d * 100.0 / Math.max(1, total)));
                    emit(id, "progress", pct, "completedFragments", d, "totalFragments", total,
                            "speed", speedStr[0], "size", "~" + human(est),
                            "lastMessage", "Fragment " + d + " / " + total);
                }
            }));
        }
        for (Future<?> f : fs) {
            try { f.get(); } catch (Exception ignored) {}
        }
        pool.shutdown();
        if (job.cancelled.get()) throw new IOException("cancelled");
        if (err[0] != null) throw new IOException("Fragment failed: " + err[0].getMessage());
    }

    private String downloadFileResume(String id, String url, Map<String, String> headers, File part, File mp4, Job job) throws Exception {
        Map<String, String> h = new HashMap<>(headers);
        h.put("Range", "bytes=" + part.length() + "-");
        Net.Response r = Net.open(url, "GET", h, null, 20000);
        String done;
        if (r.status == 206) done = appendFile(id, r, part, job);
        else if (r.status >= 400) { r.conn.disconnect(); throw new IOException("HTTP " + r.status); }
        else done = downloadFile(id, r, part, job); // server ignored the range -> start over
        if (job.cancelled.get()) return done;
        if (!new File(done).renameTo(mp4)) throw new IOException("rename failed");
        return mp4.getAbsolutePath();
    }

    private String appendFile(String id, Net.Response r, File out, Job job) throws Exception {
        long len = -1;
        String cr = r.headers.getOrDefault("content-range", "");
        int slash = cr.lastIndexOf('/');
        if (slash > 0) try { len = Long.parseLong(cr.substring(slash + 1).trim()); } catch (Exception ignored) {}
        long got = out.length(), lastT = 0, winB = 0, winT = System.currentTimeMillis();
        String speed = "";
        try (InputStream in = r.body; FileOutputStream f = new FileOutputStream(out, true)) {
            byte[] buf = new byte[131072];
            int c;
            while ((c = in.read(buf)) > 0) {
                if (job.cancelled.get()) throw new IOException("cancelled");
                f.write(buf, 0, c);
                got += c;
                winB += c;
                long now = System.currentTimeMillis();
                if (now - winT > 1500) { speed = String.format(Locale.US, "%.2f MB/s", winB * 1000.0 / (now - winT) / 1e6); winB = 0; winT = now; }
                if (now - lastT > 500) {
                    lastT = now;
                    int pct = len > 0 ? (int) Math.min(99, got * 100 / len) : 0;
                    emit(id, "progress", pct, "speed", speed, "size", len > 0 ? human(len) : human(got),
                            "lastMessage", pct + "% of " + (len > 0 ? human(len) : "?"));
                }
            }
        } finally { r.conn.disconnect(); }
        return out.getAbsolutePath();
    }

    private String downloadFile(String id, Net.Response r, File out, Job job) throws Exception {
        long len = -1;
        try { len = Long.parseLong(r.headers.getOrDefault("content-length", "-1")); } catch (Exception ignored) {}
        long got = 0, lastT = 0, winB = 0, winT = System.currentTimeMillis();
        String speed = "";
        try (InputStream in = r.body; FileOutputStream f = new FileOutputStream(out)) {
            byte[] buf = new byte[131072];
            int c;
            while ((c = in.read(buf)) > 0) {
                if (job.cancelled.get()) throw new IOException("cancelled");
                f.write(buf, 0, c);
                got += c;
                winB += c;
                long now = System.currentTimeMillis();
                if (now - winT > 1500) { speed = String.format(Locale.US, "%.2f MB/s", winB * 1000.0 / (now - winT) / 1e6); winB = 0; winT = now; }
                if (now - lastT > 500) {
                    lastT = now;
                    int pct = len > 0 ? (int) Math.min(99, got * 100 / len) : 0;
                    emit(id, "progress", pct, "speed", speed, "size", len > 0 ? human(len) : human(got),
                            "lastMessage", pct + "% of " + (len > 0 ? human(len) : "?"));
                }
            }
        } finally { r.conn.disconnect(); }
        return out.getAbsolutePath();
    }

    private static void write(File f, String s) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }

    static void startServiceCompat(Context ctx, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }
}
