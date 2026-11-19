package com.streambert.android;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.mediarouter.app.MediaRouteChooserDialog;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.cast.HlsSegmentFormat;
import com.google.android.gms.cast.HlsVideoSegmentFormat;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaTrack;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Chromecast support (Default Media Receiver). Media is served to the receiver by {@link LanServer}.
 *
 * JS: isAvailable() · cast({url,title,referer?,startTime?,subtitleUrl?,isLocal?}) · stop() · getState()
 * Event: "castState" {connected, deviceName}
 */
@CapacitorPlugin(name = "StreambertCast")
public class CastPlugin extends Plugin {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private CastContext cast;             // main thread only
    private Pending pending;              // media waiting for a session (main thread only)
    private boolean sessionStarting;

    /** One-shot resolver for a cast() call. */
    private static final class Once {
        final PluginCall call;
        final AtomicBoolean done = new AtomicBoolean();
        Once(PluginCall c) { call = c; }
        void ok() {
            if (!done.compareAndSet(false, true)) return;
            JSObject o = new JSObject();
            o.put("ok", true);
            call.resolve(o);
        }
        void fail(String error) {
            if (!done.compareAndSet(false, true)) return;
            JSObject o = new JSObject();
            o.put("ok", false);
            o.put("error", error);
            call.resolve(o);
        }
    }

    private static final class Pending {
        final MediaLoadRequestData request;
        final Once once;
        Pending(MediaLoadRequestData r, Once o) { request = r; once = o; }
    }

    private final SessionManagerListener<CastSession> sessionListener = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarting(CastSession s) { sessionStarting = true; }
        @Override public void onSessionStarted(CastSession s, String id) { sessionStarting = false; emitState(); loadPending(s); }
        @Override public void onSessionStartFailed(CastSession s, int err) {
            sessionStarting = false;
            failPending("Could not connect to the Cast device (" + CastStatusCodes.getStatusCodeString(err) + ")");
            emitState();
        }
        @Override public void onSessionEnding(CastSession s) {}
        @Override public void onSessionEnded(CastSession s, int err) {
            sessionStarting = false;
            emitState();
            if (pending == null) LanServer.shutdown();
        }
        @Override public void onSessionResuming(CastSession s, String id) { sessionStarting = true; }
        @Override public void onSessionResumed(CastSession s, boolean wasSuspended) { sessionStarting = false; emitState(); loadPending(s); }
        @Override public void onSessionResumeFailed(CastSession s, int err) { sessionStarting = false; emitState(); }
        @Override public void onSessionSuspended(CastSession s, int reason) { emitState(); }
    };

    @Override
    public void load() {
        main.post(this::castContext);
    }

    @Override
    protected void handleOnDestroy() {
        try {
            if (cast != null) cast.getSessionManager().removeSessionManagerListener(sessionListener, CastSession.class);
        } catch (Throwable ignored) {}
        LanServer.shutdown();
        io.shutdownNow();
        super.handleOnDestroy();
    }

    /** Lazily obtains the CastContext; null when Play services / Cast are unavailable. Main thread only. */
    private CastContext castContext() {
        if (cast != null) return cast;
        try {
            int gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(getContext());
            if (gms != ConnectionResult.SUCCESS) { android.util.Log.w("StreambertCast", "Play services unavailable: " + gms); return null; }
            @SuppressWarnings("deprecation")
            CastContext c = CastContext.getSharedInstance(getContext());
            c.getSessionManager().addSessionManagerListener(sessionListener, CastSession.class);
            cast = c;
        } catch (Throwable t) {
            android.util.Log.w("StreambertCast", "Cast unavailable", t);
            cast = null;
        }
        return cast;
    }

    private CastSession currentSession() {
        try {
            CastContext c = castContext();
            if (c == null) return null;
            CastSession s = c.getSessionManager().getCurrentCastSession();
            return s != null && s.isConnected() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private JSObject stateObject() {
        JSObject o = new JSObject();
        CastSession s = currentSession();
        o.put("connected", s != null);
        String name = null;
        try { if (s != null && s.getCastDevice() != null) name = s.getCastDevice().getFriendlyName(); } catch (Throwable ignored) {}
        o.put("deviceName", name);
        return o;
    }

    private void emitState() {
        try { notifyListeners("castState", stateObject()); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ plugin methods

    @PluginMethod
    public void isAvailable(PluginCall call) {
        main.post(() -> {
            JSObject o = new JSObject();
            boolean ok;
            try { ok = castContext() != null; } catch (Throwable t) { ok = false; }
            o.put("available", ok);
            call.resolve(o);
        });
    }

    @PluginMethod
    public void getState(PluginCall call) {
        main.post(() -> call.resolve(stateObject()));
    }

    @PluginMethod
    public void stop(PluginCall call) {
        main.post(() -> {
            try {
                CastContext c = castContext();
                if (c != null) c.getSessionManager().endCurrentSession(true);
            } catch (Throwable ignored) {}
            JSObject o = new JSObject();
            o.put("ok", true);
            call.resolve(o);
        });
    }

    @PluginMethod
    public void cast(PluginCall call) {
        final Once once = new Once(call);
        final String url = call.getString("url");
        final String title = call.getString("title", "");
        final String referer = call.getString("referer");
        final double startTime = call.getDouble("startTime", 0.0);
        final String subtitleUrl = call.getString("subtitleUrl");
        final boolean isLocal = Boolean.TRUE.equals(call.getBoolean("isLocal", false));
        if (url == null || url.isEmpty()) { once.fail("No URL given"); return; }

        // GMS / Cast check on the main thread first so we fail fast on devices without it
        main.post(() -> {
            if (castContext() == null) { once.fail("Google Play services / Cast is not available on this device"); return; }
            io.execute(() -> {
                MediaLoadRequestData req;
                try {
                    req = buildRequest(url, title, referer, startTime, subtitleUrl, isLocal);
                } catch (UserError e) {
                    once.fail(e.getMessage());
                    return;
                } catch (Throwable t) {
                    once.fail("Could not prepare media: " + t.getMessage());
                    return;
                }
                main.post(() -> start(req, once));
            });
        });
    }

    private static final class UserError extends Exception {
        UserError(String m) { super(m); }
    }

    // ------------------------------------------------------------------ session / loading

    private void start(MediaLoadRequestData req, Once once) {
        CastSession s = currentSession();
        if (s != null) {
            load(s, req, once);
            return;
        }
        if (pending != null) pending.once.fail("Superseded by a newer cast request");
        final Pending p = new Pending(req, once);
        pending = p;
        Activity act = getActivity();
        CastContext c = castContext();
        if (act == null || act.isFinishing() || c == null) { failPending("App is not in the foreground"); return; }
        try {
            final MediaRouteSelector selector = c.getMergedSelector();
            if (selector == null) { failPending("Cast is not ready yet, try again"); return; }
            MediaRouteChooserDialog d = new MediaRouteChooserDialog(act);
            d.setRouteSelector(selector);
            d.setOnDismissListener(di -> main.postDelayed(() -> {
                if (pending != p || sessionStarting) return;
                if (currentSession() != null) { loadPending(currentSession()); return; }
                boolean selected = false;
                try {
                    MediaRouter.RouteInfo r = MediaRouter.getInstance(act).getSelectedRoute();
                    selected = r != null && !r.isDefault() && r.matchesSelector(selector);
                } catch (Throwable ignored) {}
                if (!selected) failPending("No Cast device selected");
            }, 500));
            d.show();
        } catch (Throwable t) {
            failPending("Could not open the Cast device picker: " + t.getMessage());
            return;
        }
        // give up if no session comes up in time
        main.postDelayed(() -> { if (pending == p) failPending("Timed out connecting to the Cast device"); }, 60_000);
    }

    private void loadPending(CastSession s) {
        Pending p = pending;
        if (p == null) return;
        pending = null;
        load(s, p.request, p.once);
    }

    private void failPending(String error) {
        Pending p = pending;
        pending = null;
        if (p != null) p.once.fail(error);
    }

    private void load(CastSession s, MediaLoadRequestData req, Once once) {
        try {
            RemoteMediaClient rmc = s.getRemoteMediaClient();
            if (rmc == null) { once.fail("Cast session has no media channel"); return; }
            rmc.load(req).setResultCallback(res -> {
                if (res.getStatus().isSuccess()) once.ok();
                else once.fail("Chromecast could not load the media ("
                        + CastStatusCodes.getStatusCodeString(res.getStatus().getStatusCode()) + ")");
            });
            // the receiver may take a while to answer; the load request itself is out
            main.postDelayed(once::ok, 20_000);
        } catch (Throwable t) {
            once.fail("Load failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ request building (io thread)

    private MediaLoadRequestData buildRequest(String url, String title, String referer, double startTime,
                                              String subtitleUrl, boolean isLocal) throws Exception {
        String ip = LanServer.lanIp();
        if (ip == null) throw new UserError("Not connected to Wi-Fi (the phone and the Chromecast must be on the same network)");
        LanServer srv = LanServer.ensure();
        String base = srv.base(ip);

        Src src = resolve(url, referer, isLocal);
        String contentUrl;
        boolean hls;
        Boolean fmp4 = null;
        if (src.file != null) {
            File f = src.file;
            if (f.isDirectory()) f = new File(f, "index.m3u8");
            if (!f.isFile()) throw new UserError("File not found: " + f.getPath());
            hls = f.getName().toLowerCase(Locale.US).endsWith(".m3u8");
            contentUrl = base + LanServer.filePath(f, hls, false);
            if (hls) fmp4 = probeLocal(f);
        } else {
            contentUrl = base + LanServer.proxyPath(src.url, src.referer, false);
            String path = pathOf(src.url).toLowerCase(Locale.US);
            hls = path.endsWith(".m3u8") || path.endsWith(".m3u");
            if (!path.endsWith(".mp4") && !path.endsWith(".webm") && !path.endsWith(".mkv")) {
                Boolean[] r = probeRemote(src.url, src.referer);
                if (r != null) { hls = true; fmp4 = r[0]; }
            }
        }

        String lowerUrl = (src.file != null ? src.file.getName() : pathOf(src.url)).toLowerCase(Locale.US);
        String contentType = hls ? "application/x-mpegURL"
                : lowerUrl.endsWith(".webm") ? "video/webm" : "video/mp4";

        MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        md.putString(MediaMetadata.KEY_TITLE, title == null || title.isEmpty() ? "Streambert" : title);

        MediaInfo.Builder mb = new MediaInfo.Builder(contentUrl)
                .setContentUrl(contentUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(contentType)
                .setMetadata(md);
        if (hls) {
            boolean f4 = Boolean.TRUE.equals(fmp4);
            mb.setHlsSegmentFormat(f4 ? HlsSegmentFormat.FMP4 : HlsSegmentFormat.TS);
            mb.setHlsVideoSegmentFormat(f4 ? HlsVideoSegmentFormat.FMP4 : HlsVideoSegmentFormat.MPEG2_TS);
        }

        long[] active = null;
        String subUrl = subtitleUrl == null || subtitleUrl.isEmpty() ? null : subtitleLanUrl(base, subtitleUrl, src.referer);
        if (subUrl != null) {
            MediaTrack track = new MediaTrack.Builder(1, MediaTrack.TYPE_TEXT)
                    .setName("Subtitles")
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentId(subUrl)
                    .setContentType("text/vtt")
                    .setLanguage("und")
                    .build();
            mb.setMediaTracks(Collections.singletonList(track));
            active = new long[]{1};
        }

        MediaLoadRequestData.Builder rb = new MediaLoadRequestData.Builder()
                .setMediaInfo(mb.build())
                .setAutoplay(true)
                .setCurrentTime(Math.max(0, (long) (startTime * 1000)));
        if (active != null) rb.setActiveTrackIds(active);
        return rb.build();
    }

    private static final class Src {
        String url, referer;
        File file;
    }

    /** Normalizes the incoming URL: absolute path / file:// / Capacitor file URL / in-app proxy URL / remote. */
    private static Src resolve(String url, String referer, boolean isLocal) throws UserError {
        Src s = new Src();
        s.referer = referer;
        String u = url.trim();
        int cf = u.indexOf("/_capacitor_file_");
        if (cf >= 0 && !u.startsWith("/")) {
            u = Uri.decode(u.substring(cf + "/_capacitor_file_".length()));
        } else if (u.startsWith("file://")) {
            u = Uri.decode(u.substring(7));
        }
        if (u.contains(StreamProxy.PATH + "?")) { // already-proxied WebView URL
            Uri pu = Uri.parse(u.startsWith("/") ? "https://localhost" + u : u);
            String inner = pu.getQueryParameter("u");
            if (inner != null) {
                u = inner;
                String r = pu.getQueryParameter("r");
                if (r != null && (s.referer == null || s.referer.isEmpty())) s.referer = r;
            }
        }
        if (u.startsWith("/") || isLocal) {
            if (!u.startsWith("/")) throw new UserError("Local path must be absolute");
            s.file = new File(u);
            return s;
        }
        if (!(u.startsWith("http://") || u.startsWith("https://"))) throw new UserError("Unsupported URL (only http/https or local files)");
        s.url = u;
        return s;
    }

    private static String subtitleLanUrl(String base, String sub, String referer) {
        try {
            Src s = resolve(sub, referer, false);
            if (s.file != null) {
                if (!s.file.isFile()) return null;
                return base + LanServer.filePath(s.file, false, true);
            }
            return base + LanServer.proxyPath(s.url, s.referer, true);
        } catch (Throwable t) {
            return null; // blob:/data: URLs etc. are not castable
        }
    }

    // ------------------------------------------------------------------ HLS probing

    /** fMP4 detection for a local playlist (follows the first variant of a master playlist). */
    private static Boolean probeLocal(File f) {
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String variant = firstVariant(text);
            if (variant != null && !variant.startsWith("http")) {
                File v = new File(f.getParentFile(), variant.split("\\?")[0]);
                if (v.isFile()) text = new String(Files.readAllBytes(v.toPath()), StandardCharsets.UTF_8);
            }
            return isFmp4(text);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Returns {fmp4} when the remote URL is an HLS playlist, null when it isn't (or can't be fetched). */
    private static Boolean[] probeRemote(String url, String referer) {
        try {
            String text = fetchPlaylist(url, referer);
            if (text == null) return null;
            String variant = firstVariant(text);
            if (variant != null) {
                String child = fetchPlaylist(LanServer.abs(url, variant), referer);
                if (child != null) text = child;
            }
            return new Boolean[]{isFmp4(text)};
        } catch (Throwable t) {
            return null;
        }
    }

    private static String fetchPlaylist(String url, String referer) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "*/*");
        h.put("Accept-Encoding", "identity");
        if (referer != null && !referer.isEmpty()) {
            h.put("Referer", referer);
            try {
                URL ru = new URL(referer);
                h.put("Origin", ru.getProtocol() + "://" + ru.getAuthority());
            } catch (Exception ignored) {}
        }
        Net.Response r = Net.open(url, "GET", h, null, 10000);
        try (InputStream in = r.body) {
            if (r.status < 200 || r.status >= 300) return null;
            byte[] head = new byte[16];
            int n = 0;
            while (n < head.length) {
                int k = in.read(head, n, head.length - n);
                if (k <= 0) break;
                n += k;
            }
            String start = new String(head, 0, n, StandardCharsets.UTF_8).replace("﻿", "").trim();
            if (!start.startsWith("#EXTM3U")) return null; // not a playlist: stop reading (could be a big mp4)
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(head, 0, n);
            byte[] buf = new byte[16384];
            int k;
            while ((k = in.read(buf)) > 0) {
                out.write(buf, 0, k);
                if (out.size() > 5 * 1024 * 1024) break;
            }
            return out.toString("UTF-8");
        } finally {
            try { r.conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static String firstVariant(String text) {
        boolean next = false;
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith("#EXT-X-STREAM-INF")) { next = true; continue; }
            if (next && !t.isEmpty() && !t.startsWith("#")) return t;
        }
        return null;
    }

    private static boolean isFmp4(String text) {
        if (text.contains("#EXT-X-MAP")) return true;
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String p = t.split("\\?")[0].toLowerCase(Locale.US);
            return p.endsWith(".m4s") || p.endsWith(".mp4") || p.endsWith(".cmfv");
        }
        return false;
    }

    private static String pathOf(String url) {
        try { return new URL(url).getPath(); } catch (Exception e) { return url; }
    }
}
