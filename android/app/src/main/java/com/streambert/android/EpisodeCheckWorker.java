package com.streambert.android;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Daily background check (works while the app is closed): for every saved TV
 * show, asks TMDB for the latest aired episode and posts a notification when a
 * new one came out in the last 7 days. Same rules as the desktop's startup check.
 * The app syncs token + saved shows here via StreambertPlugin.syncEpisodeWatch.
 */
public class EpisodeCheckWorker extends Worker {
    static final String PREFS = "sb_episodes";
    static final String CHANNEL = "episodes";

    public EpisodeCheckWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    static void schedule(Context ctx) {
        Constraints c = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(EpisodeCheckWorker.class, 24, TimeUnit.HOURS, 2, TimeUnit.HOURS)
                .setConstraints(c)
                .setInitialDelay(6, TimeUnit.HOURS)
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("episodeCheck", ExistingPeriodicWorkPolicy.KEEP, req);
    }

    static void runNow(Context ctx) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(EpisodeCheckWorker.class).build();
        WorkManager.getInstance(ctx).enqueueUniqueWork("episodeCheckNow", ExistingWorkPolicy.REPLACE, req);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("enabled", true)) return Result.success();
        String token = p.getString("token", null);
        if (token == null || token.isEmpty()) return Result.success();
        JSONArray shows;
        JSONObject state;
        try {
            shows = new JSONArray(p.getString("shows", "[]"));
            state = new JSONObject(p.getString("state", "{}"));
        } catch (Exception e) {
            return Result.success();
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String today = fmt.format(new java.util.Date());
        String weekAgo = fmt.format(new java.util.Date(System.currentTimeMillis() - 7L * 86400000L));
        int failures = 0;
        for (int i = 0; i < shows.length(); i++) {
            if (isStopped()) break;
            JSONObject s = shows.optJSONObject(i);
            if (s == null) continue;
            String id = String.valueOf(s.opt("id"));
            try {
                Map<String, String> h = new HashMap<>();
                h.put("Authorization", "Bearer " + token);
                h.put("Accept", "application/json");
                Net.Response r = Net.open("https://api.themoviedb.org/3/tv/" + id, "GET", h, null, 15000);
                if (r.status == 401) { r.conn.disconnect(); return Result.success(); } // token revoked
                if (r.status >= 400) { r.conn.disconnect(); failures++; continue; }
                JSONObject d = new JSONObject(r.text());
                JSONObject last = d.optJSONObject("last_episode_to_air");
                String lastDate = last != null ? last.optString("air_date", "") : "";
                JSONObject prev = state.optJSONObject(id);
                String prevDate = prev != null ? prev.optString("lastEpDate", "") : "";
                boolean firstCheck = prev == null;
                // ISO yyyy-MM-dd dates compare correctly as strings
                boolean valid = lastDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}");
                boolean recent = valid && lastDate.compareTo(weekAgo) >= 0 && lastDate.compareTo(today) <= 0;
                boolean newer = valid && !lastDate.equals(prevDate)
                        && (prevDate.isEmpty() || "null".equals(prevDate) || lastDate.compareTo(prevDate) > 0);
                if (recent && (firstCheck || newer)) {
                    String title = s.optString("title", d.optString("name", "New episode"));
                    String ep = String.format(Locale.US, "S%02d E%02d", last.optInt("season_number"), last.optInt("episode_number"));
                    String epName = last.optString("name", "");
                    notify(ctx, Integer.parseInt(id.replaceAll("\\D", "0")) % 100000 + 1000, id, title,
                            "New episode: " + ep + (epName.isEmpty() || epName.startsWith("Episode") ? "" : " · " + epName));
                }
                JSONObject st = new JSONObject();
                st.put("lastEpDate", lastDate.isEmpty() ? JSONObject.NULL : lastDate);
                st.put("checkedAt", System.currentTimeMillis());
                st.put("nextEpDate", d.optJSONObject("next_episode_to_air") != null
                        ? d.optJSONObject("next_episode_to_air").optString("air_date", null) : JSONObject.NULL);
                state.put(id, st);
                Thread.sleep(250);
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                failures++;
            }
        }
        p.edit().putString("state", state.toString()).putLong("lastRun", System.currentTimeMillis()).apply();
        return failures > 0 && failures == shows.length() ? Result.retry() : Result.success();
    }

    static void notify(Context ctx, int notifId, String showId, String title, String text) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "New episodes", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse("streambert://open/tv/" + showId), ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, notifId, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(notifId, new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_streambert)
                .setColor(0xFFE50914)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setGroup("streambert_episodes")
                .build());
    }

    /** Merge state from the app (keeps the newest lastEpDate per show). */
    static void mergeState(SharedPreferences p, JSONObject incoming) {
        try {
            JSONObject state = new JSONObject(p.getString("state", "{}"));
            for (Iterator<String> it = incoming.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject in = incoming.optJSONObject(id);
                if (in == null) continue;
                JSONObject cur = state.optJSONObject(id);
                String a = cur != null ? cur.optString("lastEpDate", "") : "";
                String b = in.optString("lastEpDate", "");
                if (cur == null || (!b.isEmpty() && !"null".equals(b) && b.compareTo(a) > 0)) state.put(id, in);
            }
            p.edit().putString("state", state.toString()).apply();
        } catch (Exception ignored) {}
    }
}
