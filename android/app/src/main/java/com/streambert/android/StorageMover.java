package com.streambert.android;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Moves finished MP4 downloads (+ subtitles) to the save location chosen in
 * Settings: public Movies/Streambert (shows up in gallery / VLC) or a folder the
 * user picked with the system folder picker (any storage, incl. SD cards).
 * Paths of picked-folder files are content:// URIs.
 */
final class StorageMover {
    private StorageMover() {}

    static File moviesDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Streambert");
    }

    /** @return {filePath, subtitlePaths, location, bytes} or null when the move failed (file stays in app storage). */
    static JSONObject move(Context ctx, Downloads dl, File mp4, JSONArray subPaths, String name) {
        try {
            String loc = dl.location();
            if ("movies".equals(loc)) return toMovies(ctx, mp4, subPaths);
            if ("custom".equals(loc) && dl.treeUri() != null) return toTree(ctx, Uri.parse(dl.treeUri()), mp4, subPaths, name);
        } catch (Exception e) {
            android.util.Log.w("Streambert", "move to save location failed", e);
        }
        return null;
    }

    private static JSONObject toMovies(Context ctx, File mp4, JSONArray subPaths) throws Exception {
        File dir = moviesDir();
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);
        File dest = unique(dir, mp4.getName());
        moveFile(mp4, dest);
        JSONArray subs = new JSONArray();
        java.util.List<String> scan = new java.util.ArrayList<>();
        scan.add(dest.getAbsolutePath());
        if (subPaths != null) for (int i = 0; i < subPaths.length(); i++) {
            JSONObject sp = subPaths.optJSONObject(i);
            if (sp == null) continue;
            File src = new File(sp.optString("path"));
            if (!src.exists()) continue;
            File sd = unique(dir, src.getName());
            moveFile(src, sd);
            JSONObject n = new JSONObject(sp.toString());
            n.put("path", sd.getAbsolutePath());
            subs.put(n);
        }
        MediaScannerConnection.scanFile(ctx, scan.toArray(new String[0]), null, null);
        JSONObject r = new JSONObject();
        r.put("filePath", dest.getAbsolutePath());
        r.put("subtitlePaths", subs);
        r.put("location", "movies");
        r.put("bytes", dest.length());
        return r;
    }

    private static JSONObject toTree(Context ctx, Uri tree, File mp4, JSONArray subPaths, String name) throws Exception {
        DocumentFile root = DocumentFile.fromTreeUri(ctx, tree);
        if (root == null || !root.canWrite()) throw new java.io.IOException("No write access to the chosen folder");
        DocumentFile video = root.createFile("video/mp4", mp4.getName().replaceAll("\\.mp4$", ""));
        if (video == null) throw new java.io.IOException("Could not create file in the chosen folder");
        copy(ctx, mp4, video.getUri());
        JSONArray subs = new JSONArray();
        if (subPaths != null) for (int i = 0; i < subPaths.length(); i++) {
            JSONObject sp = subPaths.optJSONObject(i);
            if (sp == null) continue;
            File src = new File(sp.optString("path"));
            if (!src.exists()) continue;
            String fn = src.getName();
            String mime = fn.endsWith(".srt") ? "application/x-subrip" : "text/vtt";
            DocumentFile sd = root.createFile(mime, fn);
            if (sd == null) continue;
            copy(ctx, src, sd.getUri());
            JSONObject n = new JSONObject(sp.toString());
            n.put("path", sd.getUri().toString());
            subs.put(n);
        }
        JSONObject r = new JSONObject();
        r.put("filePath", video.getUri().toString());
        r.put("subtitlePaths", subs);
        r.put("location", "custom");
        r.put("bytes", mp4.length());
        return r;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
    }

    private static void moveFile(File src, File dest) throws Exception {
        if (src.renameTo(dest)) return;
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dest)) {
            pipe(in, out);
        }
        //noinspection ResultOfMethodCallIgnored
        src.delete();
    }

    private static void copy(Context ctx, File src, Uri dest) throws Exception {
        try (InputStream in = new FileInputStream(src); OutputStream out = ctx.getContentResolver().openOutputStream(dest, "w")) {
            if (out == null) throw new java.io.IOException("cannot write " + dest);
            pipe(in, out);
        }
    }

    private static void pipe(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[1 << 20];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            out.write(buf, 0, n);
        }
    }

    // ───────────── helpers for paths that may be content:// URIs ─────────────

    static boolean isContent(String p) { return p != null && p.startsWith("content://"); }

    static boolean exists(Context ctx, String p) {
        if (p == null || p.isEmpty()) return false;
        if (isContent(p)) {
            try {
                DocumentFile f = DocumentFile.fromSingleUri(ctx, Uri.parse(p));
                return f != null && f.exists();
            } catch (Exception e) { return false; }
        }
        return new File(p).exists();
    }

    static boolean deletePath(Context ctx, String p) {
        if (p == null || p.isEmpty() || "null".equals(p)) return false;
        if (isContent(p)) {
            try { return DocumentsContract.deleteDocument(ctx.getContentResolver(), Uri.parse(p)); } catch (Exception e) { return false; }
        }
        File f = new File(p);
        boolean ok = f.delete();
        if (ok && f.getAbsolutePath().startsWith(moviesDir().getAbsolutePath())) {
            MediaScannerConnection.scanFile(ctx, new String[]{f.getAbsolutePath()}, null, null);
        }
        return ok;
    }

    static long sizeOfPath(Context ctx, String p) {
        if (isContent(p)) {
            try {
                DocumentFile f = DocumentFile.fromSingleUri(ctx, Uri.parse(p));
                return f != null ? f.length() : 0;
            } catch (Exception e) { return 0; }
        }
        return Downloads.sizeOf(new File(p));
    }

    static boolean needsLegacyPermission() { return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P; }

    static String displayName(Context ctx, Uri tree) {
        try {
            DocumentFile f = DocumentFile.fromTreeUri(ctx, tree);
            if (f != null && f.getName() != null) return f.getName();
        } catch (Exception ignored) {}
        String last = tree.getLastPathSegment();
        return last != null ? last.replaceFirst("^[^:]*:", "") : "Folder";
    }

    static InputStream open(ContentResolver cr, Uri u) throws Exception { return cr.openInputStream(u); }
}
