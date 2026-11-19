package com.streambert.android;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Host based ad/tracker blocker (same list as the desktop app's BLOCKED_HOSTS)
 * plus block statistics (all-time per-domain counts, debounced batches to JS).
 */
public final class AdBlock {
    private static final Set<String> exact = new HashSet<>();
    private static final Set<String> suffix = new HashSet<>(); // "*.host" -> "host"
    private static final Map<String, String> pathRules = new HashMap<>(); // host -> path prefix
    private static volatile boolean loaded = false;

    private static int total = 0;
    private static final Map<String, Integer> domains = new HashMap<>();
    private static int batchTotal = 0;
    private static Map<String, Integer> batchDomains = new HashMap<>();
    private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> batchFuture, saveFuture;
    private static File statsFile;

    public interface BatchListener { void onBatch(JSONObject batch); }
    private static volatile BatchListener listener;
    public static void setListener(BatchListener l) { listener = l; }

    public static synchronized void load(Context ctx) {
        if (loaded) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("sb-blocklist.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim().toLowerCase();
                if (line.isEmpty()) continue;
                int slash = line.indexOf('/');
                if (slash > 0) { pathRules.put(line.substring(0, slash), line.substring(slash)); continue; }
                if (line.startsWith("*.")) suffix.add(line.substring(2));
                else exact.add(line);
            }
        } catch (Exception ignored) {}
        statsFile = new File(ctx.getFilesDir(), "blockStats.json");
        try {
            if (statsFile.exists()) {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(statsFile.toPath()), StandardCharsets.UTF_8));
                total = o.optInt("total", 0);
                JSONObject d = o.optJSONObject("domains");
                if (d != null) for (java.util.Iterator<String> it = d.keys(); it.hasNext(); ) { String k = it.next(); domains.put(k, d.optInt(k)); }
            }
        } catch (Exception ignored) {}
        loaded = true;
    }

    public static boolean isBlocked(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        if (exact.contains(host)) return true;
        String p = pathRules.get(host);
        if (p != null) {
            String path = uri.getPath();
            if (path != null && path.startsWith(p)) return true;
        }
        // walk parent domains for suffix rules (*.x.com matches a.b.x.com and x.com... desktop matches subdomains only, plus usually lists x.com separately)
        String h = host;
        int dot;
        while ((dot = h.indexOf('.')) > 0) {
            h = h.substring(dot + 1);
            if (suffix.contains(h)) return true;
        }
        return false;
    }

    public static synchronized void record(Uri uri) {
        String d = uri.getHost();
        if (d == null) return;
        d = d.toLowerCase().replaceFirst("^www\\.", "");
        total++;
        domains.put(d, domains.getOrDefault(d, 0) + 1);
        batchTotal++;
        batchDomains.put(d, batchDomains.getOrDefault(d, 0) + 1);
        if (batchFuture != null) batchFuture.cancel(false);
        batchFuture = timer.schedule(AdBlock::flushBatch, 250, TimeUnit.MILLISECONDS);
        if (saveFuture != null) saveFuture.cancel(false);
        saveFuture = timer.schedule(AdBlock::save, 3000, TimeUnit.MILLISECONDS);
    }

    private static void flushBatch() {
        JSONObject o;
        synchronized (AdBlock.class) {
            if (batchTotal == 0) return;
            o = new JSONObject();
            try {
                o.put("total", batchTotal);
                o.put("domains", new JSONObject(batchDomains));
            } catch (Exception ignored) {}
            batchTotal = 0;
            batchDomains = new HashMap<>();
        }
        BatchListener l = listener;
        if (l != null) l.onBatch(o);
    }

    public static synchronized JSONObject stats() {
        JSONObject o = new JSONObject();
        try {
            o.put("total", total);
            o.put("domains", new JSONObject(domains));
        } catch (Exception ignored) {}
        return o;
    }

    private static void save() {
        try {
            String s = stats().toString();
            try (FileOutputStream f = new FileOutputStream(statsFile)) { f.write(s.getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) {}
    }
}
