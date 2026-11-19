package com.streambert.android;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;

/**
 * "Continue watching" home-screen widget: the last 3 titles from the current
 * profile's history, with progress. Tapping one opens it in the app.
 * Data is pushed by the app via StreambertPlugin.updateWidget.
 */
public class ContinueWidget extends AppWidgetProvider {
    static final String PREFS = "sb_widget";
    private static final int[] POSTER = {R.id.w_poster0, R.id.w_poster1, R.id.w_poster2};
    private static final int[] PROGRESS = {R.id.w_progress0, R.id.w_progress1, R.id.w_progress2};
    private static final int[] TITLE = {R.id.w_title0, R.id.w_title1, R.id.w_title2};
    private static final int[] SUB = {R.id.w_sub0, R.id.w_sub1, R.id.w_sub2};
    private static final int[] ITEM = {R.id.w_item0, R.id.w_item1, R.id.w_item2};

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        render(ctx, mgr, ids);
    }

    static File posterFile(Context ctx, String key) {
        File d = new File(ctx.getFilesDir(), "widget");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, key.replaceAll("[^A-Za-z0-9_]", "_") + ".jpg");
    }

    /** Stores the items, downloads missing posters, then refreshes all widgets (call off the main thread). */
    static void save(Context ctx, JSONArray items) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("items", items.toString()).apply();
        refresh(ctx); // show text right away
        boolean fetched = false;
        for (int i = 0; i < items.length() && i < 3; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String poster = it.optString("poster", "");
            if (poster.isEmpty()) continue;
            File f = posterFile(ctx, it.optString("type") + "_" + it.optString("id"));
            if (f.exists() && f.length() > 0) continue;
            try {
                Net.Response r = Net.open("https://image.tmdb.org/t/p/w185" + poster, "GET", new HashMap<>(), null, 15000);
                if (r.status >= 400) { r.conn.disconnect(); continue; }
                byte[] b = r.bytes(2L * 1024 * 1024);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
                fetched = true;
            } catch (Exception ignored) {}
        }
        if (fetched) refresh(ctx);
    }

    static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, ContinueWidget.class));
        if (ids.length > 0) render(ctx, mgr, ids);
    }

    static void render(Context ctx, AppWidgetManager mgr, int[] ids) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray items;
        try { items = new JSONArray(p.getString("items", "[]")); } catch (Exception e) { items = new JSONArray(); }
        for (int wid : ids) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_continue);
            Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            v.setOnClickPendingIntent(R.id.w_header, PendingIntent.getActivity(ctx, 9000, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            boolean empty = items.length() == 0;
            v.setViewVisibility(R.id.w_empty, empty ? View.VISIBLE : View.GONE);
            v.setViewVisibility(R.id.w_items, empty ? View.GONE : View.VISIBLE);
            if (empty) v.setOnClickPendingIntent(R.id.w_empty, PendingIntent.getActivity(ctx, 9001, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            for (int i = 0; i < 3; i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) { v.setViewVisibility(ITEM[i], View.INVISIBLE); continue; }
                v.setViewVisibility(ITEM[i], View.VISIBLE);
                v.setTextViewText(TITLE[i], it.optString("title"));
                v.setTextViewText(SUB[i], it.optString("subtitle"));
                int prog = it.optInt("progress", 0);
                v.setProgressBar(PROGRESS[i], 100, prog, false);
                v.setViewVisibility(PROGRESS[i], prog > 0 ? View.VISIBLE : View.INVISIBLE);
                File f = posterFile(ctx, it.optString("type") + "_" + it.optString("id"));
                Bitmap bm = null;
                if (f.exists()) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 1;
                    bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                }
                if (bm != null) v.setImageViewBitmap(POSTER[i], bm);
                else v.setImageViewResource(POSTER[i], R.mipmap.ic_launcher_foreground);
                String link = "streambert://open/" + it.optString("type") + "/" + it.optString("id")
                        + (it.has("season") ? "?s=" + it.optInt("season") + "&e=" + it.optInt("episode") : "");
                Intent go = new Intent(Intent.ACTION_VIEW, Uri.parse(link), ctx, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                v.setOnClickPendingIntent(ITEM[i], PendingIntent.getActivity(ctx, 9100 + i, go,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            mgr.updateAppWidget(wid, v);
        }
    }
}
