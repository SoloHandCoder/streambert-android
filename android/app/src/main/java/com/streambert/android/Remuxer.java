package com.streambert.android;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Converts a fully downloaded local HLS folder (see {@code Downloads.downloadHls})
 * into a single regular MP4 using only framework APIs:
 * <ol>
 *   <li>each media playlist (video, optional separate audio rendition) is joined into one
 *       continuous temp file next to the output (MPEG-TS, fragmented MP4 or raw ADTS),
 *       applying BYTERANGE, AES-128 decryption and fake-image-header stripping;</li>
 *   <li>the temp files are demuxed with {@link MediaExtractor} and written with
 *       {@link MediaMuxer} (timestamps normalised to start at 0, discontinuities closed).</li>
 * </ol>
 * Everything is streamed; nothing proportional to the file size is held in memory.
 */
public final class Remuxer {

    public interface Progress { void onProgress(int percent, String message); }

    private Remuxer() {}

    private static final String TAG = "Remuxer";
    private static final int IO_BUF = 256 * 1024;
    private static final int HEAD_LIMIT = 512 * 1024;      // max bytes scanned for a fake header
    private static final int TS_PACKET = 188;
    private static final int TS_CHECK = 5;                  // consecutive sync bytes required
    private static final int DEFAULT_MAX_SAMPLE = 2 * 1024 * 1024;
    private static final int MAX_SAMPLE_LIMIT = 64 * 1024 * 1024;
    private static final long FWD_JUMP_US = 10_000_000L;    // forward gap treated as discontinuity
    private static final long VIDEO_BACK_TOL_US = 2_000_000L; // B-frame reordering tolerance
    private static final long AUDIO_BACK_TOL_US = 500_000L;
    private static final long SYNC_BASE_TOL_US = 10_000_000L;
    private static final int PRESCAN_SAMPLES = 600;
    /** Below API 30 MPEG4Writer may use 32-bit chunk offsets (no co64) → refuse >4 GB there. */
    private static final long MAX_32BIT_MP4 = 4_000_000_000L;

    private enum Kind { TS, FMP4, ADTS }

    // ───────────────────────────────────────────── public API

    /** Converts &lt;dir&gt;/index.m3u8 (master or media playlist) into outMp4. Throws on failure (caller keeps the HLS folder then). */
    public static void hlsToMp4(File playlist, File outMp4, Progress progress) throws Exception {
        File outTmp = new File(outMp4.getPath() + ".tmp");
        File vTemp = null, aTemp = null;
        boolean ok = false;
        Tracker tr = new Tracker(progress);
        try {
            tr.report("Reading playlist", true);
            File vPl = playlist, aPl = null;
            String text = readText(playlist);
            if (text.contains("#EXT-X-STREAM-INF")) {
                File[] pl = parseMaster(playlist, text);
                vPl = pl[0];
                aPl = pl[1];
            }
            Media vMedia = parseMedia(vPl);
            Media aMedia = aPl != null ? parseMedia(aPl) : null;
            if (vMedia.segments.isEmpty()) throw new IOException("Playlist has no segments");
            if (aMedia != null && aMedia.segments.isEmpty()) aMedia = null;

            tr.total1 = vMedia.totalBytes() + (aMedia != null ? aMedia.totalBytes() : 0);

            Kind vKind = detectKind(vMedia);
            vTemp = new File(outMp4.getPath() + ".part-v" + ext(vKind));
            tr.message = "Joining video segments";
            buildTemp(vMedia, vKind, vTemp, tr);
            if (aMedia != null) {
                Kind aKind = detectKind(aMedia);
                aTemp = new File(outMp4.getPath() + ".part-a" + ext(aKind));
                tr.message = "Joining audio segments";
                buildTemp(aMedia, aKind, aTemp, tr);
            }
            tr.done1 = tr.total1;

            long estimate = vTemp.length() + (aTemp != null ? aTemp.length() : 0);
            if (Build.VERSION.SDK_INT < 30 && estimate > MAX_32BIT_MP4) {
                throw new IOException("Video too large for MP4 conversion on this Android version");
            }
            tr.total2 = Math.max(1, estimate);
            tr.message = "Writing MP4";
            tr.report(tr.message, true);
            //noinspection ResultOfMethodCallIgnored
            outTmp.delete();
            long sampleBytes = mux(vTemp, aTemp, outTmp, tr);
            checkInterrupt();

            verify(outTmp, sampleBytes);
            if (outMp4.exists() && !outMp4.delete()) throw new IOException("Cannot replace " + outMp4.getName());
            if (!outTmp.renameTo(outMp4)) throw new IOException("Rename failed");
            ok = true;
            tr.done2 = tr.total2;
            tr.report("Done", true);
        } finally {
            if (vTemp != null) //noinspection ResultOfMethodCallIgnored
                vTemp.delete();
            if (aTemp != null) //noinspection ResultOfMethodCallIgnored
                aTemp.delete();
            if (!ok) //noinspection ResultOfMethodCallIgnored
                outTmp.delete();
        }
    }

    // ───────────────────────────────────────────── playlist model / parsing

    private static final class Key {
        final byte[] key;
        final byte[] iv; // null → derive from media sequence number
        Key(byte[] key, byte[] iv) { this.key = key; this.iv = iv; }
    }

    private static final class Segment {
        File file;
        long offset;
        long length;   // resolved (never -1 after parsing)
        long seq;
        Key key;
    }

    private static final class Media {
        File playlist;
        Segment init;  // EXT-X-MAP, may be null
        final List<Segment> segments = new ArrayList<>();

        long totalBytes() {
            long t = init != null ? init.length : 0;
            for (Segment s : segments) t += s.length;
            return t;
        }
    }

    private static String readText(File f) throws IOException {
        byte[] b = readAll(f, 16 * 1024 * 1024);
        String s = new String(b, StandardCharsets.UTF_8);
        if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1);
        return s;
    }

    private static byte[] readAll(File f, int max) throws IOException {
        long len = f.length();
        if (len > max) throw new IOException("File too large: " + f.getName());
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(16, len));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    /** @return {videoPlaylist, audioPlaylistOrNull} */
    private static File[] parseMaster(File master, String text) throws IOException {
        File dir = master.getParentFile();
        String[] lines = text.split("\r?\n");
        Map<String, String> audioByGroup = new HashMap<>();
        String firstAudio = null;
        String variant = null, audioGroup = null;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> a = attrs(l);
                if ("AUDIO".equals(a.get("TYPE")) && a.containsKey("URI")) {
                    String g = a.get("GROUP-ID");
                    if (g == null) g = "";
                    if (!audioByGroup.containsKey(g) || "YES".equals(a.get("DEFAULT"))) audioByGroup.put(g, a.get("URI"));
                    if (firstAudio == null) firstAudio = a.get("URI");
                }
            } else if (l.startsWith("#EXT-X-STREAM-INF") && variant == null) {
                int j = i + 1;
                while (j < lines.length && (lines[j].trim().isEmpty() || lines[j].trim().startsWith("#"))) j++;
                if (j < lines.length) {
                    variant = lines[j].trim();
                    audioGroup = attrs(l).get("AUDIO");
                }
            }
        }
        if (variant == null) throw new IOException("Master playlist has no variant");
        String audio = null;
        if (audioGroup != null) audio = audioByGroup.get(audioGroup);
        if (audio == null && audioGroup != null) audio = firstAudio;
        File v = resolve(dir, variant);
        File a = audio != null ? resolve(dir, audio) : null;
        if (!v.isFile()) throw new IOException("Missing variant playlist " + variant);
        if (a != null && !a.isFile()) {
            Log.w(TAG, "audio playlist missing: " + a);
            a = null;
        }
        return new File[]{v, a};
    }

    private static Media parseMedia(File playlist) throws Exception {
        File dir = playlist.getParentFile();
        Media m = new Media();
        m.playlist = playlist;
        Map<String, byte[]> keyCache = new HashMap<>();
        long seq = 0;
        Key cur = null;
        long brLen = -1, brOff = -1;
        boolean pendingBr = false, gap = false;
        File prevFile = null;
        long prevEnd = 0;
        for (String raw : readText(playlist).split("\r?\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#")) {
                if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    if (m.segments.isEmpty()) seq = parseLongSafe(l.substring(l.indexOf(':') + 1).trim(), 0);
                } else if (l.startsWith("#EXT-X-KEY:")) {
                    Map<String, String> a = attrs(l);
                    String method = a.get("METHOD");
                    method = method == null ? "NONE" : method.toUpperCase(Locale.US);
                    String fmt = a.get("KEYFORMAT");
                    boolean identity = fmt == null || fmt.isEmpty() || "identity".equalsIgnoreCase(fmt);
                    if (method.equals("NONE")) {
                        cur = null;
                    } else if (method.equals("AES-128")) {
                        if (!identity) {
                            Log.w(TAG, "ignoring AES-128 key with KEYFORMAT=" + fmt);
                            continue;
                        }
                        String uri = a.get("URI");
                        if (uri == null) throw new IOException("AES-128 key without URI");
                        byte[] k = keyCache.get(uri);
                        if (k == null) {
                            k = loadKey(dir, uri);
                            keyCache.put(uri, k);
                        }
                        String ivs = a.get("IV");
                        cur = new Key(k, ivs != null ? parseIv(ivs) : null);
                    } else {
                        throw new IOException("Unsupported HLS encryption: " + method);
                    }
                } else if (l.startsWith("#EXT-X-MAP:")) {
                    Map<String, String> a = attrs(l);
                    String uri = a.get("URI");
                    if (uri == null) continue;
                    Segment s = new Segment();
                    s.file = resolve(dir, uri);
                    requireFile(s.file);
                    String br = a.get("BYTERANGE");
                    if (br != null) {
                        long[] r = parseByteRange(br);
                        s.length = r[0];
                        s.offset = r[1] >= 0 ? r[1] : 0;
                    } else {
                        s.offset = 0;
                        s.length = s.file.length();
                    }
                    checkRange(s);
                    s.key = cur;
                    s.seq = seq;
                    if (m.init == null) {
                        m.init = s;
                    } else if (!sameRange(m.init, s) && !sameContent(m.init, s)) {
                        throw new IOException("Streams with changing init segments are not supported");
                    }
                } else if (l.startsWith("#EXT-X-BYTERANGE:")) {
                    long[] r = parseByteRange(l.substring(l.indexOf(':') + 1).trim());
                    brLen = r[0];
                    brOff = r[1];
                    pendingBr = true;
                } else if (l.equals("#EXT-X-GAP")) {
                    gap = true;
                }
                // #EXTINF, #EXT-X-DISCONTINUITY, #EXT-X-ENDLIST etc. need no handling here;
                // discontinuities are handled by timestamp repair while muxing.
                continue;
            }
            // segment URI
            File f = resolve(dir, l);
            long off, len;
            if (pendingBr) {
                off = brOff >= 0 ? brOff : (f.equals(prevFile) ? prevEnd : 0);
                len = brLen;
            } else {
                off = 0;
                len = -1;
            }
            pendingBr = false;
            long thisSeq = seq++;
            if (gap) {
                gap = false;
                prevFile = f;
                prevEnd = len >= 0 ? off + len : 0;
                continue;
            }
            requireFile(f);
            if (len < 0) len = f.length() - off;
            Segment s = new Segment();
            s.file = f;
            s.offset = off;
            s.length = len;
            s.seq = thisSeq;
            s.key = cur;
            checkRange(s);
            m.segments.add(s);
            prevFile = f;
            prevEnd = off + len;
        }
        return m;
    }

    private static void requireFile(File f) throws IOException {
        if (!f.isFile()) throw new IOException("Missing segment " + f.getName());
    }

    private static void checkRange(Segment s) throws IOException {
        if (s.offset < 0 || s.length < 0 || s.offset + s.length > s.file.length()) {
            throw new IOException("Segment " + s.file.getName() + " is truncated");
        }
    }

    private static boolean sameRange(Segment a, Segment b) {
        return a.file.equals(b.file) && a.offset == b.offset && a.length == b.length;
    }

    private static boolean sameContent(Segment a, Segment b) throws IOException {
        if (a.length != b.length || a.length > 16 * 1024 * 1024) return false;
        return Arrays.equals(readRange(a.file, a.offset, (int) a.length), readRange(b.file, b.offset, (int) b.length));
    }

    private static byte[] readRange(File f, long off, int len) throws IOException {
        byte[] b = new byte[len];
        try (FileInputStream in = new FileInputStream(f)) {
            in.getChannel().position(off);
            int p = 0;
            while (p < len) {
                int n = in.read(b, p, len - p);
                if (n <= 0) break;
                p += n;
            }
            return p == len ? b : Arrays.copyOf(b, p);
        }
    }

    /** "n[@o]" → {n, o or -1} */
    private static long[] parseByteRange(String s) throws IOException {
        s = s.trim();
        int at = s.indexOf('@');
        try {
            long n = Long.parseLong((at >= 0 ? s.substring(0, at) : s).trim());
            long o = at >= 0 ? Long.parseLong(s.substring(at + 1).trim()) : -1;
            if (n < 0) throw new NumberFormatException();
            return new long[]{n, o};
        } catch (NumberFormatException e) {
            throw new IOException("Bad BYTERANGE: " + s);
        }
    }

    private static long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    private static byte[] parseIv(String s) throws IOException {
        String h = s.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        if (h.isEmpty() || h.length() > 32) throw new IOException("Bad IV: " + s);
        StringBuilder sb = new StringBuilder();
        for (int i = h.length(); i < 32; i++) sb.append('0');
        h = sb.append(h).toString();
        byte[] iv = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = Character.digit(h.charAt(2 * i), 16), lo = Character.digit(h.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IOException("Bad IV: " + s);
            iv[i] = (byte) ((hi << 4) | lo);
        }
        return iv;
    }

    private static byte[] seqIv(long seq) {
        byte[] iv = new byte[16];
        for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (seq >>> (8 * i));
        return iv;
    }

    private static byte[] loadKey(File dir, String uri) throws IOException {
        byte[] k;
        if (uri.startsWith("data:")) {
            int c = uri.indexOf(',');
            if (c < 0) throw new IOException("Bad data: key URI");
            String meta = uri.substring(5, c), payload = uri.substring(c + 1);
            k = meta.endsWith(";base64") ? Base64.decode(payload, Base64.DEFAULT)
                    : URLDecoder.decode(payload, "ISO-8859-1").getBytes(StandardCharsets.ISO_8859_1);
        } else if (uri.contains("://") && !uri.startsWith("file://")) {
            throw new IOException("Key not available locally (" + uri.substring(0, uri.indexOf(':')) + ")");
        } else {
            File f = resolve(dir, uri);
            if (!f.isFile()) throw new IOException("Missing key file " + f.getName());
            k = readAll(f, 4096);
        }
        if (k.length == 16) return k;
        // some hosts serve the key as 32 hex chars
        String s = new String(k, StandardCharsets.ISO_8859_1).trim();
        if (s.length() == 32 && s.matches("[0-9a-fA-F]{32}")) return parseIv(s);
        throw new IOException("Invalid AES-128 key (" + k.length + " bytes)");
    }

    private static File resolve(File dir, String uri) throws IOException {
        String u = uri.trim();
        if (u.startsWith("file:")) {
            try { return new File(URI.create(u)); } catch (Exception e) { return new File(u.replaceFirst("^file:(//)?", "")); }
        }
        if (u.contains("://")) throw new IOException("Not a local file: " + u);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        File f = new File(u);
        if (!f.isAbsolute()) f = new File(dir, u);
        if (!f.exists() && u.indexOf('%') >= 0) {
            try {
                String d = URLDecoder.decode(u, "UTF-8");
                File g = new File(d);
                if (!g.isAbsolute()) g = new File(dir, d);
                if (g.exists()) return g;
            } catch (Exception ignored) {}
        }
        return f;
    }

    /** Parses an attribute list (handles quoted values containing commas). Keys are upper-cased. */
    private static Map<String, String> attrs(String line) {
        Map<String, String> m = new HashMap<>();
        int c = line.indexOf(':');
        if (c < 0) return m;
        String s = line.substring(c + 1);
        int i = 0, n = s.length();
        while (i < n) {
            int eq = s.indexOf('=', i);
            if (eq < 0) break;
            String k = s.substring(i, eq).trim().toUpperCase(Locale.US);
            int j = eq + 1;
            String v;
            if (j < n && s.charAt(j) == '"') {
                int e = s.indexOf('"', j + 1);
                if (e < 0) e = n;
                v = s.substring(j + 1, e);
                int comma = s.indexOf(',', Math.min(n, e + 1));
                i = comma < 0 ? n : comma + 1;
            } else {
                int comma = s.indexOf(',', j);
                int e = comma < 0 ? n : comma;
                v = s.substring(j, e).trim();
                i = e + 1;
            }
            m.put(k, v);
        }
        return m;
    }

    // ───────────────────────────────────────────── phase 1: joining segments

    private static String ext(Kind k) {
        switch (k) {
            case FMP4: return ".mp4";
            case ADTS: return ".aac";
            default: return ".ts";
        }
    }

    private static boolean isBoxType(byte[] h, int p) {
        if (p + 4 > h.length) return false;
        String t = new String(h, p, 4, StandardCharsets.ISO_8859_1);
        switch (t) {
            case "ftyp": case "styp": case "moov": case "moof": case "sidx": case "emsg":
            case "prft": case "free": case "skip": case "mdat": case "uuid": case "meta":
                return true;
            default:
                return false;
        }
    }

    private static boolean looksBmff(byte[] h, int n) {
        return n >= 8 && isBoxType(Arrays.copyOf(h, Math.max(8, n)), 4);
    }

    /** Reads up to 'max' bytes from the start of a segment, decrypted if needed. */
    private static byte[] headOf(Segment s, int max, boolean decrypt) throws Exception {
        int want = (int) Math.min(max, s.length);
        byte[] raw = readRange(s.file, s.offset, want);
        if (!decrypt || s.key == null) return raw;
        int blocks = raw.length / 16 * 16;
        if (blocks == 0) return new byte[0];
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(s.key.key, "AES"),
                new IvParameterSpec(s.key.iv != null ? s.key.iv : seqIv(s.seq)));
        return c.update(raw, 0, blocks);
    }

    private static Kind detectKind(Media m) throws Exception {
        if (m.init != null) {
            byte[] h = headOf(m.init, 4096, false);
            if (looksBmff(h, h.length)) return Kind.FMP4;
            if (h.length > 0 && h[0] == 0x47) return Kind.TS;
            if (m.init.key != null) {
                byte[] d = headOf(m.init, 4096, true);
                if (d.length > 0 && d[0] == 0x47) return Kind.TS;
            }
            return Kind.FMP4;
        }
        Segment first = m.segments.get(0);
        byte[] h = headOf(first, 4096, true);
        if (h.length >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') return Kind.ADTS;
        if (h.length >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xF6) == 0xF0) return Kind.ADTS;
        String n = first.file.getName().toLowerCase(Locale.US);
        if (n.endsWith(".aac") || n.endsWith(".adts")) return Kind.ADTS;
        if (looksBmff(h, h.length)) return Kind.FMP4; // fMP4 without EXT-X-MAP (self-initialising)
        return Kind.TS;
    }

    private static void buildTemp(Media m, Kind kind, File out, Tracker tr) throws Exception {
        byte[] buf = new byte[IO_BUF];
        byte[] dec = new byte[IO_BUF + 32];
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1024 * 1024)) {
            if (m.init != null) {
                Segment init = m.init;
                byte[] h = headOf(init, 64, false);
                boolean encrypted = init.key != null && !looksBmff(h, h.length) && !(h.length > 0 && h[0] == 0x47);
                copySegment(init, encrypted, kind, os, buf, dec, tr);
            }
            for (Segment s : m.segments) {
                checkInterrupt();
                copySegment(s, s.key != null, kind, os, buf, dec, tr);
            }
        }
    }

    private static void copySegment(Segment s, boolean decrypt, Kind kind, OutputStream os,
                                    byte[] buf, byte[] dec, Tracker tr) throws Exception {
        Stripper strip = new Stripper(kind, os);
        Decryptor d = decrypt ? new Decryptor(s.key.key, s.key.iv != null ? s.key.iv : seqIv(s.seq), dec) : null;
        try (FileInputStream in = new FileInputStream(s.file)) {
            in.getChannel().position(s.offset);
            long left = s.length;
            while (left > 0) {
                checkInterrupt();
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) throw new IOException("Unexpected end of " + s.file.getName());
                left -= n;
                if (d != null) d.update(buf, 0, n, strip);
                else strip.write(buf, 0, n);
                tr.add1(n);
            }
        }
        if (d != null) d.finish(strip);
        strip.end();
    }

    private interface Sink { void write(byte[] b, int off, int len) throws IOException; }

    /** AES-128-CBC decryption with manual PKCS7 removal (tolerates bad/missing padding). */
    private static final class Decryptor {
        private final Cipher c;
        private final byte[] out;
        private final byte[] hold = new byte[16];
        private int holdLen = 0;

        Decryptor(byte[] key, byte[] iv, byte[] out) throws Exception {
            c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            this.out = out;
        }

        void update(byte[] b, int off, int len, Sink sink) throws Exception {
            int k = c.update(b, off, len, out, 0);
            if (k < 16) {
                if (k > 0) { // cannot happen with CBC/NoPadding (k is a multiple of 16), kept for safety
                    if (holdLen > 0) sink.write(hold, 0, holdLen);
                    System.arraycopy(out, 0, hold, 0, k);
                    holdLen = k;
                }
                return;
            }
            if (holdLen > 0) sink.write(hold, 0, holdLen);
            sink.write(out, 0, k - 16);
            System.arraycopy(out, k - 16, hold, 0, 16);
            holdLen = 16;
        }

        void finish(Sink sink) throws IOException {
            try { c.doFinal(); } catch (Exception ignored) { /* trailing partial block: dropped */ }
            if (holdLen == 0) return;
            int keep = holdLen;
            if (holdLen == 16) {
                int pad = hold[15] & 0xFF;
                if (pad >= 1 && pad <= 16) {
                    boolean valid = true;
                    for (int i = 16 - pad; i < 16; i++) if ((hold[i] & 0xFF) != pad) { valid = false; break; }
                    if (valid) keep = 16 - pad;
                }
            }
            if (keep > 0) sink.write(hold, 0, keep);
            holdLen = 0;
        }
    }

    /**
     * Per-segment head inspection: skips junk (fake PNG/JPEG headers) before the first
     * real TS packet / ISO-BMFF box, and leading ID3 tags for packed ADTS audio.
     */
    private static final class Stripper implements Sink {
        private final Kind kind;
        private final OutputStream os;
        private byte[] head = new byte[8192];
        private int headLen = 0;
        private int scanPos = 0;
        private boolean decided = false;
        private long skipLeft = 0; // bytes still to drop after decision (ID3 larger than head)

        Stripper(Kind kind, OutputStream os) { this.kind = kind; this.os = os; }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (len <= 0) return;
            if (decided) { pass(b, off, len); return; }
            if (headLen + len > head.length) head = Arrays.copyOf(head, Math.max(head.length * 2, headLen + len));
            System.arraycopy(b, off, head, headLen, len);
            headLen += len;
            tryDecide(headLen >= HEAD_LIMIT);
        }

        void end() throws IOException {
            if (!decided) tryDecide(true);
        }

        private void pass(byte[] b, int off, int len) throws IOException {
            if (skipLeft > 0) {
                int s = (int) Math.min(skipLeft, len);
                skipLeft -= s;
                off += s;
                len -= s;
            }
            if (len > 0) os.write(b, off, len);
        }

        private void tryDecide(boolean force) throws IOException {
            long skip;
            switch (kind) {
                case TS: skip = findTs(force); break;
                case FMP4: skip = findBox(force); break;
                default: skip = findAdts(force); break;
            }
            if (skip == -2) return; // need more data
            if (skip < 0) {
                Log.w(TAG, "no " + kind + " sync found in segment head, copying as-is");
                skip = 0;
            } else if (skip > 0) {
                Log.d(TAG, "skipping " + skip + " junk bytes before " + kind + " data");
            }
            decided = true;
            skipLeft = skip;
            byte[] h = head;
            int n = headLen;
            head = null;
            headLen = 0;
            pass(h, 0, n);
        }

        /** @return offset of first TS packet, -1 none, -2 need more data */
        private long findTs(boolean eof) {
            byte[] h = head;
            int n = headLen;
            for (int i = scanPos; i < n; i++) {
                if (h[i] != 0x47) continue;
                if (i + TS_PACKET * TS_CHECK < n) {
                    boolean ok = true;
                    for (int k = 1; k <= TS_CHECK; k++) {
                        if (h[i + TS_PACKET * k] != 0x47) { ok = false; break; }
                    }
                    if (ok) return i;
                } else if (!eof) {
                    scanPos = i;
                    return -2;
                } else {
                    // short tail: verify whatever packets are present
                    boolean ok = true;
                    int cnt = 0;
                    for (int p = i + TS_PACKET; p < n; p += TS_PACKET) {
                        if (h[p] != 0x47) { ok = false; break; }
                        cnt++;
                    }
                    if (ok && (cnt >= 1 || i == 0)) return i;
                }
            }
            scanPos = n;
            return eof ? -1 : -2;
        }

        private long findBox(boolean eof) {
            byte[] h = head;
            int n = headLen;
            if (n < 8) return eof ? -1 : -2;
            if (isBoxType(h, 4) && boxSizeOk(h, 0)) return 0;
            for (int p = Math.max(4, scanPos); p + 4 <= n; p++) {
                if (isStartBox(h, p) && boxSizeOk(h, p - 4)) return p - 4;
            }
            scanPos = Math.max(4, n - 3);
            return eof ? -1 : -2;
        }

        private static boolean isStartBox(byte[] h, int p) {
            byte a = h[p], b = h[p + 1], c = h[p + 2], d = h[p + 3];
            return (a == 'm' && b == 'o' && c == 'o' && (d == 'f' || d == 'v'))
                    || (a == 's' && b == 't' && c == 'y' && d == 'p')
                    || (a == 'f' && b == 't' && c == 'y' && d == 'p')
                    || (a == 's' && b == 'i' && c == 'd' && d == 'x');
        }

        private static boolean boxSizeOk(byte[] h, int p) {
            long size = ((h[p] & 0xFFL) << 24) | ((h[p + 1] & 0xFFL) << 16) | ((h[p + 2] & 0xFFL) << 8) | (h[p + 3] & 0xFFL);
            return size == 1 || size >= 8;
        }

        private long findAdts(boolean eof) {
            byte[] h = head;
            int n = headLen;
            if (n < 10) return eof ? 0 : -2;
            if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
                long size = ((h[6] & 0x7FL) << 21) | ((h[7] & 0x7FL) << 14) | ((h[8] & 0x7FL) << 7) | (h[9] & 0x7FL);
                boolean footer = (h[5] & 0x10) != 0;
                return 10 + size + (footer ? 10 : 0);
            }
            return 0;
        }
    }

    // ───────────────────────────────────────────── phase 2: muxing

    private static final class TrackState {
        final int muxIndex;
        final boolean video;
        long base;
        long offset = 0;
        long last = 0;  // audio: last written ts, video: max written ts
        boolean any = false;
        long frameDur;

        TrackState(int muxIndex, boolean video) {
            this.muxIndex = muxIndex;
            this.video = video;
            this.frameDur = video ? 33_367 : 21_333;
        }

        private boolean isJump(long t) {
            long back = video ? VIDEO_BACK_TOL_US : AUDIO_BACK_TOL_US;
            return any && (t < last - back || t > last + FWD_JUMP_US);
        }

        long estimate(long raw) {
            long t = raw - base + offset;
            return isJump(t) ? last + frameDur : t;
        }

        long commit(long raw) {
            long t = raw - base + offset;
            if (isJump(t)) {
                long nt = last + frameDur;
                Log.d(TAG, (video ? "video" : "audio") + " discontinuity " + (t - last) + "us closed");
                offset += nt - t;
                t = nt;
            }
            if (!video && any && t <= last) t = last + 1;
            if (t < 0) t = 0;
            if (any && t > last) {
                long d = t - last;
                if (d < 1_000_000) frameDur = d;
            }
            if (!any || t > last) last = t;
            any = true;
            return t;
        }
    }

    private static final class Source {
        final MediaExtractor ex;
        final Map<Integer, TrackState> tracks = new HashMap<>();
        boolean ended = false;
        Source(MediaExtractor ex) { this.ex = ex; }
    }

    private static boolean muxerSupports(String mime) {
        switch (mime) {
            case "video/avc":
            case "video/mp4v-es":
            case "video/3gpp":
            case "audio/mp4a-latm":
            case "audio/3gpp":
            case "audio/amr-wb":
                return true;
            case "video/hevc":
                return Build.VERSION.SDK_INT >= 24;
            case "video/av01":
                return Build.VERSION.SDK_INT >= 34;
            default:
                return false;
        }
    }

    private static String mimeOf(MediaFormat f) {
        String m = f.getString(MediaFormat.KEY_MIME);
        return m == null ? "" : m;
    }

    private static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            if (mimeOf(ex.getTrackFormat(i)).startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int maxInput(MediaFormat f) {
        try {
            if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                int v = f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                if (v > 0) return v;
            }
        } catch (Exception ignored) {}
        return DEFAULT_MAX_SAMPLE;
    }

    /** Minimum sample time per selected track over the first samples (handles B-frame reordering). */
    private static Map<Integer, Long> prescan(File f, int[] tracks) {
        Map<Integer, Long> min = new HashMap<>();
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getPath());
            for (int t : tracks) ex.selectTrack(t);
            for (int i = 0; i < PRESCAN_SAMPLES; i++) {
                long ts = ex.getSampleTime();
                if (ts < 0) break;
                int ti = ex.getSampleTrackIndex();
                Long cur = min.get(ti);
                if (cur == null || ts < cur) min.put(ti, ts);
                if (!ex.advance()) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "prescan failed", e);
        } finally {
            ex.release();
        }
        return min;
    }

    private static long mux(File vFile, File aFile, File outTmp, Tracker tr) throws Exception {
        MediaExtractor vEx = new MediaExtractor();
        MediaExtractor aEx = null;
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            vEx.setDataSource(vFile.getPath());
            int vTrack = findTrack(vEx, "video/");
            if (vTrack < 0) throw new IOException("No video track found");
            int aTrack = -1;
            if (aFile != null) {
                aEx = new MediaExtractor();
                try {
                    aEx.setDataSource(aFile.getPath());
                    aTrack = findTrack(aEx, "audio/");
                } catch (Exception e) {
                    Log.w(TAG, "audio rendition unreadable", e);
                    aTrack = -1;
                }
                if (aTrack < 0) { aEx.release(); aEx = null; }
            }
            int aTrackInV = aEx == null ? findTrack(vEx, "audio/") : -1;

            MediaFormat vFmt = vEx.getTrackFormat(vTrack);
            MediaFormat aFmt = aEx != null ? aEx.getTrackFormat(aTrack) : (aTrackInV >= 0 ? vEx.getTrackFormat(aTrackInV) : null);
            String vMime = mimeOf(vFmt);
            if (!muxerSupports(vMime)) throw new IOException("Unsupported video codec: " + vMime);
            if (aFmt != null && !muxerSupports(mimeOf(aFmt))) throw new IOException("Unsupported audio codec: " + mimeOf(aFmt));

            muxer = new MediaMuxer(outTmp.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            try {
                if (vFmt.containsKey(MediaFormat.KEY_ROTATION)) {
                    int r = ((vFmt.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360;
                    if (r == 90 || r == 180 || r == 270) muxer.setOrientationHint(r);
                }
            } catch (Exception ignored) {}

            int bufSize = maxInput(vFmt);
            Source vSrc = new Source(vEx);
            vSrc.tracks.put(vTrack, new TrackState(muxer.addTrack(vFmt), true));
            vEx.selectTrack(vTrack);
            Source aSrc = null;
            if (aEx != null) {
                aSrc = new Source(aEx);
                aSrc.tracks.put(aTrack, new TrackState(muxer.addTrack(aFmt), false));
                aEx.selectTrack(aTrack);
                bufSize = Math.max(bufSize, maxInput(aFmt));
            } else if (aTrackInV >= 0) {
                vSrc.tracks.put(aTrackInV, new TrackState(muxer.addTrack(aFmt), false));
                vEx.selectTrack(aTrackInV);
                bufSize = Math.max(bufSize, maxInput(aFmt));
            }
            List<Source> sources = new ArrayList<>();
            sources.add(vSrc);
            if (aSrc != null) sources.add(aSrc);

            // timestamp bases: common base keeps A/V sync; separate bases if timelines are unrelated
            long[] srcBase = new long[sources.size()];
            for (int s = 0; s < sources.size(); s++) {
                Source src = sources.get(s);
                int[] ids = new int[src.tracks.size()];
                int k = 0;
                for (Integer id : src.tracks.keySet()) ids[k++] = id;
                Map<Integer, Long> mins = prescan(s == 0 ? vFile : aFile, ids);
                long b = Long.MAX_VALUE;
                for (Long v : mins.values()) b = Math.min(b, v);
                if (b == Long.MAX_VALUE) b = Math.max(0, src.ex.getSampleTime());
                srcBase[s] = b;
            }
            long common = Long.MAX_VALUE, maxB = Long.MIN_VALUE;
            for (long b : srcBase) { common = Math.min(common, b); maxB = Math.max(maxB, b); }
            boolean separate = maxB - common > SYNC_BASE_TOL_US;
            if (separate) Log.w(TAG, "audio/video timelines differ by " + (maxB - common) + "us, aligning both to 0");
            for (int s = 0; s < sources.size(); s++) {
                for (TrackState ts : sources.get(s).tracks.values()) ts.base = separate ? srcBase[s] : common;
            }

            muxer.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocateDirect(bufSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long written = 0;
            long samples = 0;
            for (Source src : sources) if (src.ex.getSampleTime() < 0) src.ended = true;

            while (true) {
                if ((++samples & 63) == 0) checkInterrupt();
                // interleave: next sample with the smallest output timestamp
                Source pick = null;
                long best = Long.MAX_VALUE;
                for (Source src : sources) {
                    if (src.ended) continue;
                    TrackState ts = src.tracks.get(src.ex.getSampleTrackIndex());
                    long raw = src.ex.getSampleTime();
                    long est = ts != null ? ts.estimate(raw) : Long.MIN_VALUE;
                    if (pick == null || est < best) { pick = src; best = est; }
                }
                if (pick == null) break;
                MediaExtractor ex = pick.ex;
                TrackState ts = pick.tracks.get(ex.getSampleTrackIndex());
                if (ts == null) {
                    if (!ex.advance()) pick.ended = true;
                    continue;
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    long need = ex.getSampleSize();
                    if (need > buf.capacity()) buf = grow(buf, need);
                }
                int size;
                while (true) {
                    try {
                        buf.clear();
                        size = ex.readSampleData(buf, 0);
                        break;
                    } catch (IllegalArgumentException tooSmall) {
                        buf = grow(buf, (long) buf.capacity() * 2);
                    }
                }
                if (size < 0) { pick.ended = true; continue; }
                int flags = ex.getSampleFlags();
                if ((flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw new IOException("Encrypted samples (DRM) are not supported");
                long t = ts.commit(ex.getSampleTime());
                if (size > 0) {
                    int out = 0;
                    if ((flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) out |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    if (Build.VERSION.SDK_INT >= 26 && (flags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                        out |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
                    }
                    buf.position(0);
                    buf.limit(size);
                    info.set(0, size, t, out);
                    muxer.writeSampleData(ts.muxIndex, buf, info);
                    written += size;
                    tr.add2(size);
                }
                if (!ex.advance()) pick.ended = true;
            }
            if (written == 0) throw new IOException("No samples could be read");
            muxer.stop();
            started = false;
            return written;
        } finally {
            if (muxer != null) {
                if (started) { try { muxer.stop(); } catch (Exception ignored) {} }
                try { muxer.release(); } catch (Exception ignored) {}
            }
            try { vEx.release(); } catch (Exception ignored) {}
            if (aEx != null) { try { aEx.release(); } catch (Exception ignored) {} }
        }
    }

    private static ByteBuffer grow(ByteBuffer old, long need) throws IOException {
        if (need > MAX_SAMPLE_LIMIT) throw new IOException("Sample too large (" + need + " bytes)");
        int cap = old.capacity();
        while (cap < need) cap = (int) Math.min((long) cap * 2, MAX_SAMPLE_LIMIT);
        return ByteBuffer.allocateDirect(cap);
    }

    private static void verify(File out, long sampleBytes) throws IOException {
        if (!out.isFile() || out.length() < sampleBytes) {
            throw new IOException("MP4 output incomplete (" + out.length() + " of " + sampleBytes + " bytes)");
        }
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(out.getPath());
            if (ex.getTrackCount() < 1) throw new IOException("MP4 output has no tracks");
        } finally {
            ex.release();
        }
    }

    // ───────────────────────────────────────────── misc

    private static void checkInterrupt() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Remux cancelled");
    }

    /** 0–50 %: joining segments (bytes read), 50–100 %: muxing (sample bytes written). */
    private static final class Tracker {
        final Progress p;
        long total1 = 1, done1 = 0, total2 = 1, done2 = 0;
        String message = "";
        long lastT = 0;
        int lastPct = -1;
        String lastMsg = null;

        Tracker(Progress p) { this.p = p; }

        void add1(long n) { done1 += n; report(message, false); }
        void add2(long n) { done2 += n; report(message, false); }

        void report(String msg, boolean force) {
            if (p == null) return;
            double f1 = Math.min(1.0, done1 / (double) Math.max(1, total1));
            double f2 = Math.min(1.0, done2 / (double) Math.max(1, total2));
            int pct = (int) Math.min(100, Math.floor(f1 * 50 + f2 * 50));
            long now = System.currentTimeMillis();
            if (!force && (now - lastT < 300 || (pct == lastPct && msg.equals(lastMsg)))) return;
            lastT = now;
            lastPct = pct;
            lastMsg = msg;
            try { p.onProgress(pct, msg); } catch (Exception e) { Log.w(TAG, "progress callback failed", e); }
        }
    }
}
