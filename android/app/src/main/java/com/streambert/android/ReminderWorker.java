package com.streambert.android;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/** Release-time reminder: "New episode today" on the air date, at the hour the user picked. */
public class ReminderWorker extends Worker {
    static final String TAG = "sb_reminder";

    public ReminderWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    /** Replaces all scheduled reminders with these: [{ key, showId, title, text, at (ms), airDate }] */
    static void scheduleAll(Context ctx, JSONArray items) {
        WorkManager wm = WorkManager.getInstance(ctx);
        wm.cancelAllWorkByTag(TAG);
        long now = System.currentTimeMillis();
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r == null) continue;
            long at = r.optLong("at", 0);
            if (at <= now) continue;
            Data d = new Data.Builder()
                    .putString("showId", r.optString("showId"))
                    .putString("title", r.optString("title", "New episode"))
                    .putString("text", r.optString("text", ""))
                    .putString("airDate", r.optString("airDate", ""))
                    .build();
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(ReminderWorker.class)
                    .setInitialDelay(at - now, TimeUnit.MILLISECONDS)
                    .setInputData(d)
                    .addTag(TAG)
                    .build();
            wm.enqueue(req);
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        String showId = getInputData().getString("showId");
        if (showId == null || showId.isEmpty()) return Result.success();
        // the same notification id as the daily check, so it never shows twice
        int notifId = Integer.parseInt(showId.replaceAll("\\D", "0")) % 100000 + 1000;
        EpisodeCheckWorker.notify(ctx, notifId, showId, getInputData().getString("title"), getInputData().getString("text"));
        // tell the daily check this episode was already announced
        try {
            String air = getInputData().getString("airDate");
            SharedPreferences p = ctx.getSharedPreferences(EpisodeCheckWorker.PREFS, Context.MODE_PRIVATE);
            JSONObject state = new JSONObject(p.getString("state", "{}"));
            JSONObject st = state.optJSONObject(showId);
            if (st == null) st = new JSONObject();
            String prev = st.optString("lastEpDate", "");
            if (air != null && !air.isEmpty() && (prev.isEmpty() || "null".equals(prev) || air.compareTo(prev) > 0)) {
                st.put("lastEpDate", air);
                state.put(showId, st);
                p.edit().putString("state", state.toString()).apply();
            }
        } catch (Exception ignored) {}
        return Result.success();
    }
}
