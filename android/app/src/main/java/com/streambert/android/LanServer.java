package com.streambert.android;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny HTTP/1.1 server on 0.0.0.0 that a Chromecast on the same LAN fetches media from.
 *
 *   GET /p?u=<url>&r=<referer>&k=<token>[&vtt=1]   proxied remote URL (Referer/Origin/UA added,
 *                                                  HLS playlists rewritten so children go through /p)
 *   GET /f/<token>/<rootId>/<path...>[?vtt=1]      local file under a registered root (Range support)
 *
 * Every response carries CORS headers (the Cast receiver requires them for HLS) and
 * "Connection: close" (one request per connection, no keep-alive).
 * The token is random per app process; requests without it get 403.
 */
public final class LanServer {
    public static final String TOKEN = randomHex(16);
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern SRT_TIME = Pattern.compile("(\\d{1,2}:\\d{2}:\\d{2}),(\\d{3})");
    private static final int MAX_LINE = 16 * 1024;
    private static final int MAX_HEADERS = 100;

    /** rootId → canonical file or directory that may be served. */
    private static final Map<String, File> roots = new ConcurrentHashMap<>();
    private static final Map<String, String> rootIds = new ConcurrentHashMap<>(); // canonical path → id

    private static LanServer instance;

    private final ServerSocket server;
    private final ExecutorService pool;
    private volatile boolean running = true;

    private LanServer() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), 64);
        pool = new ThreadPoolExecutor(2, 16, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "sb-lan-http");
            t.setDaemon(true);
            return t;
        });
        Thread acceptor = new Thread(this::acceptLoop, "sb-lan-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Starts the server if needed (lazily) and returns it. */
    public static synchronized LanServer ensure() throws IOException {
        if (instance == null || !instance.running) instance = new LanServer();
        return instance;
    }

    public static synchronized void shutdown() {
        if (instance == null) return;
        instance.running = false;
        try { instance.server.close(); } catch (IOException ignored) {}
        instance.pool.shutdownNow();
        instance = null;
    }

    public int port() { return server.getLocalPort(); }

    /** http://<lan-ip>:<port> */
    public String base(String ip) { return "http://" + ip + ":" + port(); }

    // ------------------------------------------------------------------ URL builders

    /** Server-relative /p URL for a remote http(s) resource. */
    public static String proxyPath(String target, String referer, boolean vtt) {
        StringBuilder sb = new StringBuilder("/p?u=").append(enc(target));
        if (referer != null && !referer.isEmpty()) sb.append("&r=").append(enc(referer));
        sb.append("&k=").append(TOKEN);
        if (vtt) sb.append("&vtt=1");
        return sb.toString();
    }

    /**
     * Registers a root and returns the server-relative /f URL of {@code file} inside it.
     * For an HLS playlist the root is its folder (so relative segment URIs resolve);
     * for anything else the root is the file itself.
     */
    public static String filePath(File file, boolean wholeFolder, boolean vtt) throws IOException {
        File f = file.getCanonicalFile();
        File root = wholeFolder ? f.getParentFile() : f;
        if (root == null) throw new IOException("bad path");
        String id = registerRoot(root);
        return "/f/" + TOKEN + "/" + id + "/" + encPath(f.getName()) + (vtt ? "?vtt=1" : "");
    }

    private static String registerRoot(File canonicalRoot) {
        String key = canonicalRoot.getPath();
        String id = rootIds.get(key);
        if (id == null) {
            id = randomHex(6);
            rootIds.put(key, id);
            roots.put(id, canonicalRoot);
        }
        return id;
    }

    /** First non-loopback IPv4 of the Wi-Fi / Ethernet interface, or null. */
    public static String lanIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) continue;
                } catch (Exception e) { continue; }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.US);
                boolean mobile = name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")
                        || name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("dummy");
                if (mobile) continue;
                boolean preferred = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")
                        || name.startsWith("swlan") || name.startsWith("softap") || name.startsWith("en");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (preferred && a.isSiteLocalAddress()) return a.getHostAddress();
                    if (fallback == null && a.isSiteLocalAddress()) fallback = a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    // ------------------------------------------------------------------ server loop

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!running) return;
                continue;
            }
            try {
                pool.execute(() -> serve(s));
            } catch (RejectedExecutionException e) {
                closeQuietly(s);
            }
        }
    }

    private static final class Request {
        String method, path, query;
        final Map<String, String> headers = new HashMap<>(); // lower-case names
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(30000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            Request req = parse(in);
            if (req == null) {
                simple(out, 400, "text/plain", "bad request", false);
                return;
            }
            handle(req, out);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            closeQuietly(s);
        }
    }

    /** Parses request line + headers. Returns null on malformed input. */
    static Request parse(InputStream in) throws IOException {
        String line = readLine(in);
        // tolerate leading empty lines (RFC 7230 §3.5)
        int guard = 0;
        while (line != null && line.isEmpty() && guard++ < 4) line = readLine(in);
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) return null;
        Request r = new Request();
        r.method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];
        if (target.startsWith("http://") || target.startsWith("https://")) { // absolute-form
            try {
                URL u = new URL(target);
                target = u.getFile().isEmpty() ? "/" : u.getFile();
            } catch (Exception e) { return null; }
        }
        if (!target.startsWith("/")) return null;
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        r.query = q >= 0 ? target.substring(q + 1) : "";
        for (int i = 0; ; i++) {
            if (i > MAX_HEADERS) return null;
            String h = readLine(in);
            if (h == null) return null;
            if (h.isEmpty()) break;
            int c = h.indexOf(':');
            if (c <= 0) continue;
            r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.US), h.substring(c + 1).trim());
        }
        return r;
    }

    /** Reads one CRLF/LF-terminated line as ISO-8859-1; null on EOF before any byte. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (b.size() >= MAX_LINE) throw new IOException("line too long");
            b.write(c);
        }
        if (!any) return null;
        byte[] a = b.toByteArray();
        int len = a.length;
        if (len > 0 && a[len - 1] == '\r') len--;
        return new String(a, 0, len, StandardCharsets.ISO_8859_1);
    }

    private void handle(Request req, OutputStream out) throws IOException {
        boolean head = "HEAD".equals(req.method);
        if ("OPTIONS".equals(req.method)) {
            writeHead(out, 204, null, -1, null);
            return;
        }
        if (!"GET".equals(req.method) && !head) {
            simple(out, 405, "text/plain", "method not allowed", head);
            return;
        }
        Map<String, String> q = parseQuery(req.query);
        if ("/p".equals(req.path)) {
            if (!tokenOk(q.get("k"))) { simple(out, 403, "text/plain", "forbidden", head); return; }
            proxy(req, q, out, head);
        } else if (req.path.startsWith("/f/")) {
            String[] seg = req.path.substring(3).split("/", 3);
            if (seg.length < 3 || !tokenOk(seg[0])) { simple(out, 403, "text/plain", "forbidden", head); return; }
            local(req, seg[1], seg[2], "1".equals(q.get("vtt")), out, head);
        } else {
            simple(out, 404, "text/plain", "not found", head);
        }
    }

    private static boolean tokenOk(String t) {
        if (t == null) return false;
        return MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), TOKEN.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ /p remote proxy

    private void proxy(Request req, Map<String, String> q, OutputStream out, boolean head) throws IOException {
        String target = q.get("u");
        String referer = q.get("r");
        boolean vtt = "1".equals(q.get("vtt"));
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
            simple(out, 400, "text/plain", "bad target", head);
            return;
        }
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "*/*");
        h.put("Accept-Encoding", "identity");
        h.put("User-Agent", Net.userAgent);
        if (referer != null && !referer.isEmpty()) {
            h.put("Referer", referer);
            try {
                URL ru = new URL(referer);
                h.put("Origin", ru.getProtocol() + "://" + ru.getAuthority());
            } catch (Exception ignored) {}
        }
        String range = req.headers.get("range");
        if (range != null && !vtt) h.put("Range", range);

        Net.Response r;
        try {
            r = Net.open(target, "GET", h, null, 15000);
        } catch (Exception e) {
            simple(out, 502, "text/plain", "upstream error", head);
            return;
        }
        try {
            String ct = r.headers.getOrDefault("content-type", "");
            String ctl = ct.toLowerCase(Locale.US);
            String pathLower = pathOf(target).toLowerCase(Locale.US);
            boolean maybePlaylist = pathLower.endsWith(".m3u8") || pathLower.endsWith(".m3u") || ctl.contains("mpegurl")
                    || (range == null && (ctl.startsWith("text/plain") || ctl.startsWith("application/x-mpegurl")));

            if (vtt && r.status >= 200 && r.status < 300) {
                String text = new String(readAll(r.body, 10L * 1024 * 1024), StandardCharsets.UTF_8);
                byte[] body = toVtt(text).getBytes(StandardCharsets.UTF_8);
                writeHead(out, 200, "text/vtt; charset=utf-8", body.length, null);
                if (!head) out.write(body);
                return;
            }
            if (maybePlaylist && r.status >= 200 && r.status < 300) {
                byte[] raw = readAll(r.body, 20L * 1024 * 1024);
                String text = new String(raw, StandardCharsets.UTF_8);
                if (text.startsWith("﻿")) text = text.substring(1);
                if (text.trim().startsWith("#EXTM3U")) {
                    byte[] body = rewriteRemote(text, r.url, referer).getBytes(StandardCharsets.UTF_8);
                    writeHead(out, 200, "application/vnd.apple.mpegurl", body.length, null);
                    if (!head) out.write(body);
                } else {
                    writeHead(out, r.status, ct.isEmpty() ? guessType(pathLower) : ct, raw.length, null);
                    if (!head) out.write(raw);
                }
                return;
            }

            String type = ct.isEmpty() || ctl.startsWith("application/octet-stream") || ctl.startsWith("binary/")
                    ? guessType(pathLower) : ct;
            long len = -1;
            try { if (r.headers.containsKey("content-length")) len = Long.parseLong(r.headers.get("content-length").trim()); }
            catch (NumberFormatException ignored) {}
            Map<String, String> extra = new LinkedHashMap<>();
            if (r.headers.containsKey("content-range")) extra.put("Content-Range", r.headers.get("content-range"));
            String ar = r.headers.get("accept-ranges");
            extra.put("Accept-Ranges", ar != null ? ar : "bytes");
            writeHead(out, r.status, type, len, extra);
            if (!head) copy(r.body, out, -1);
        } finally {
            try { r.body.close(); } catch (Exception ignored) {}
            try { r.conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    /** Rewrites every child URI of a remote playlist to go through /p (same referer + token). */
    static String rewriteRemote(String text, String base, String referer) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) { sb.append('\n'); continue; }
            if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(line);
                StringBuffer b = new StringBuffer();
                while (m.find()) {
                    String child = abs(base, m.group(1));
                    String repl = isHttp(child) ? proxyPath(child, referer, false) : m.group(1);
                    m.appendReplacement(b, Matcher.quoteReplacement("URI=\"" + repl + "\""));
                }
                m.appendTail(b);
                sb.append(b).append('\n');
            } else {
                String child = abs(base, t);
                sb.append(isHttp(child) ? proxyPath(child, referer, false) : t).append('\n');
            }
        }
        return sb.toString();
    }

    /** Local playlists: relative URIs stay as-is (they resolve under /f/...); absolute http(s) ones go through /p. */
    static String rewriteLocal(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) { sb.append('\n'); continue; }
            if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(line);
                StringBuffer b = new StringBuffer();
                while (m.find()) {
                    String u = m.group(1);
                    m.appendReplacement(b, Matcher.quoteReplacement("URI=\"" + (isHttp(u) ? proxyPath(u, null, false) : u) + "\""));
                }
                m.appendTail(b);
                sb.append(b).append('\n');
            } else {
                sb.append(isHttp(t) ? proxyPath(t, null, false) : t).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ /f local files

    private void local(Request req, String rootId, String rawRel, boolean vtt, OutputStream out, boolean head) throws IOException {
        File root = roots.get(rootId);
        if (root == null) { simple(out, 403, "text/plain", "forbidden", head); return; }
        String rel = decodePath(rawRel);
        if (rel == null || rel.isEmpty() || rel.indexOf('\0') >= 0) { simple(out, 400, "text/plain", "bad path", head); return; }
        for (String part : rel.split("/")) {
            if (part.equals("..") || part.equals(".")) { simple(out, 403, "text/plain", "forbidden", head); return; }
        }
        File file;
        if (root.isFile()) {
            if (!rel.equals(root.getName())) { simple(out, 403, "text/plain", "forbidden", head); return; }
            file = root;
        } else {
            file = new File(root, rel).getCanonicalFile();
            String rp = root.getPath().endsWith(File.separator) ? root.getPath() : root.getPath() + File.separator;
            if (!file.getPath().startsWith(rp)) { simple(out, 403, "text/plain", "forbidden", head); return; }
        }
        if (!file.isFile() || !file.canRead()) { simple(out, 404, "text/plain", "not found", head); return; }

        String lower = file.getName().toLowerCase(Locale.US);
        if (vtt || lower.endsWith(".srt")) {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            byte[] body = toVtt(text).getBytes(StandardCharsets.UTF_8);
            writeHead(out, 200, "text/vtt; charset=utf-8", body.length, null);
            if (!head) out.write(body);
            return;
        }
        if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);
            byte[] body = rewriteLocal(text).getBytes(StandardCharsets.UTF_8);
            writeHead(out, 200, "application/vnd.apple.mpegurl", body.length, null);
            if (!head) out.write(body);
            return;
        }

        long size = file.length();
        String type = guessType(lower);
        long start = 0, end = size - 1;
        boolean partial = false;
        String range = req.headers.get("range");
        if (range != null && range.toLowerCase(Locale.US).startsWith("bytes=") && !range.contains(",")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash < 0) throw new NumberFormatException();
                String a = spec.substring(0, dash).trim(), b = spec.substring(dash + 1).trim();
                if (a.isEmpty()) { // suffix: last N bytes
                    long n = Long.parseLong(b);
                    if (n <= 0) throw new IllegalStateException();
                    start = Math.max(0, size - n);
                } else {
                    start = Long.parseLong(a);
                    if (!b.isEmpty()) end = Math.min(Long.parseLong(b), size - 1);
                }
                if (start >= size || start > end) throw new IllegalStateException();
                partial = true;
            } catch (NumberFormatException e) {
                start = 0; end = size - 1; // malformed → ignore the header
            } catch (IllegalStateException e) {
                Map<String, String> extra = new LinkedHashMap<>();
                extra.put("Content-Range", "bytes */" + size);
                writeHead(out, 416, "text/plain", 0, extra);
                return;
            }
        }
        long len = size == 0 ? 0 : end - start + 1;
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Accept-Ranges", "bytes");
        if (partial) extra.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
        writeHead(out, partial ? 206 : 200, type, len, extra);
        if (head || len == 0) return;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long left = len;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    // ------------------------------------------------------------------ response helpers

    private static void writeHead(OutputStream out, int status, String type, long length, Map<String, String> extra) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: *, Range, Content-Type, Accept, Origin\r\n");
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type\r\n");
        sb.append("Access-Control-Max-Age: 86400\r\n");
        sb.append("Cache-Control: no-store\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) {
            String v = e.getValue();
            if (v == null || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) continue;
            sb.append(e.getKey()).append(": ").append(v).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void simple(OutputStream out, int status, String type, String body, boolean head) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        writeHead(out, status, type, b.length, null);
        if (!head) out.write(b);
    }

    private static String reason(int s) {
        switch (s) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            default: return s < 400 ? "OK" : "Error";
        }
    }

    static String guessType(String lowerPath) {
        String p = lowerPath;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.endsWith(".m3u8") || p.endsWith(".m3u")) return "application/vnd.apple.mpegurl";
        if (p.endsWith(".ts")) return "video/mp2t";
        if (p.endsWith(".mp4") || p.endsWith(".m4s") || p.endsWith(".m4v") || p.endsWith(".cmfv")) return "video/mp4";
        if (p.endsWith(".m4a") || p.endsWith(".cmfa")) return "audio/mp4";
        if (p.endsWith(".aac")) return "audio/aac";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".webm")) return "video/webm";
        if (p.endsWith(".mkv")) return "video/x-matroska";
        if (p.endsWith(".vtt") || p.endsWith(".srt")) return "text/vtt";
        if (p.endsWith(".key") || p.endsWith(".bin")) return "application/octet-stream";
        return "application/octet-stream";
    }

    /** Converts SRT (or passes through VTT) to WebVTT. */
    static String toVtt(String text) {
        if (text.startsWith("﻿")) text = text.substring(1);
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.trim().startsWith("WEBVTT")) return t;
        return "WEBVTT\n\n" + SRT_TIME.matcher(t).replaceAll("$1.$2");
    }

    // ------------------------------------------------------------------ misc utils

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(e >= 0 ? kv.substring(0, e) : kv, "UTF-8");
                String v = e >= 0 ? URLDecoder.decode(kv.substring(e + 1), "UTF-8") : "";
                m.put(k, v);
            } catch (Exception ignored) {}
        }
        return m;
    }

    /** Percent-decodes a URL path ('+' stays literal). Null if malformed. */
    static String decodePath(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) return null;
                int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) return null;
                b.write((hi << 4) | lo);
                i += 2;
            } else if (c < 0x80) {
                b.write(c);
            } else {
                byte[] u = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                b.write(u, 0, u.length);
            }
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String encPath(String s) { return enc(s).replace("+", "%20"); }

    private static String pathOf(String url) {
        try { return new URL(url).getPath(); } catch (Exception e) { return url; }
    }

    private static boolean isHttp(String u) { return u.startsWith("http://") || u.startsWith("https://"); }

    static String abs(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (Exception e) { return rel; }
    }

    private static byte[] readAll(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out, max);
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (max > 0 && total > max) throw new IOException("too large");
            out.write(buf, 0, n);
        }
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }
}
