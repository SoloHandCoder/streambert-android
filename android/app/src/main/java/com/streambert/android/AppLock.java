package com.streambert.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

/**
 * Optional app lock: fingerprint / face, or the phone's own PIN / pattern /
 * password. Locks on start and when the app comes back after the chosen timeout.
 */
final class AppLock {
    static final String PREFS = "sb_lock";
    static final int AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK
            | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    private final AppCompatActivity activity;
    private final SharedPreferences prefs;
    private View overlay;
    private long backgroundAt = 0;
    private boolean prompting = false;
    /** set while the app itself opened another screen (folder picker, installer …) */
    static volatile long suppressUntil = 0;

    AppLock(AppCompatActivity a) {
        this.activity = a;
        this.prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean enabled() { return prefs.getBoolean("enabled", false); }
    long timeoutMs() { return prefs.getLong("timeoutMs", 60_000L); }

    static int availability(Context ctx) {
        return BiometricManager.from(ctx).canAuthenticate(AUTH);
    }

    void onCreate() {
        if (enabled()) showOverlay();
    }

    void onStop() {
        backgroundAt = System.currentTimeMillis();
    }

    void onStart() {
        if (!enabled()) return;
        if (overlay != null) { prompt(); return; }
        long away = System.currentTimeMillis() - backgroundAt;
        if (backgroundAt > 0 && away >= timeoutMs() && System.currentTimeMillis() > suppressUntil
                && !activity.isInPictureInPictureMode()) {
            showOverlay();
        }
    }

    void onResume() {
        if (overlay != null) prompt();
    }

    private void showOverlay() {
        if (overlay != null) return;
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Color.parseColor("#0A0A0A"));
        root.setClickable(true);
        root.setFocusable(true);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView logo = new ImageView(activity);
        logo.setImageResource(R.mipmap.ic_launcher_round);
        int s = dp(96);
        col.addView(logo, new LinearLayout.LayoutParams(s, s));
        TextView t = new TextView(activity);
        t.setText("Vidra is locked");
        t.setTextColor(Color.parseColor("#F0F0F0"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setPadding(0, dp(20), 0, dp(6));
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        TextView sub = new TextView(activity);
        sub.setText("Unlock with your fingerprint or screen lock");
        sub.setTextColor(Color.parseColor("#909090"));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setGravity(Gravity.CENTER);
        col.addView(sub);
        Button b = new Button(activity);
        b.setText("Unlock");
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E50914"));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(32), dp(12), dp(32), dp(12));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(28);
        b.setOnClickListener(v -> prompt());
        col.addView(b, bl);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(col, cl);
        ((FrameLayout) activity.getWindow().getDecorView()).addView(root,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = root;
    }

    private void unlock() {
        if (overlay != null) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
            overlay = null;
        }
    }

    boolean isLocked() { return overlay != null; }

    void prompt() {
        if (prompting) return;
        if (availability(activity) != BiometricManager.BIOMETRIC_SUCCESS) {
            // screen lock was removed from the phone → can't verify, don't lock the user out
            unlock();
            return;
        }
        prompting = true;
        authenticate(activity, "Unlock Vidra", new Callback() {
            @Override public void done(boolean ok) {
                prompting = false;
                if (ok) unlock();
            }
        });
    }

    interface Callback { void done(boolean ok); }

    static void authenticate(AppCompatActivity a, String title, Callback cb) {
        BiometricPrompt p = new BiometricPrompt(a, ContextCompat.getMainExecutor(a), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { cb.done(true); }
            @Override public void onAuthenticationError(int code, CharSequence msg) { cb.done(false); }
        });
        p.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Use your fingerprint or screen lock")
                .setAllowedAuthenticators(AUTH)
                .build());
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
