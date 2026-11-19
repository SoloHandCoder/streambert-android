package com.streambert.android;

import android.content.Context;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import com.google.android.gms.cast.framework.media.CastMediaOptions;
import com.google.android.gms.cast.framework.media.MediaIntentReceiver;
import com.google.android.gms.cast.framework.media.NotificationOptions;

import java.util.Arrays;
import java.util.List;

/** Cast framework configuration (referenced from AndroidManifest meta-data). */
public class CastOptionsProvider implements OptionsProvider {
    @Override
    public CastOptions getCastOptions(Context context) {
        NotificationOptions notification = new NotificationOptions.Builder()
                .setActions(Arrays.asList(
                        MediaIntentReceiver.ACTION_REWIND,
                        MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
                        MediaIntentReceiver.ACTION_FORWARD,
                        MediaIntentReceiver.ACTION_STOP_CASTING), new int[]{1, 2})
                .setSkipStepMs(10_000)
                .setTargetActivityClassName(MainActivity.class.getName())
                .build();
        CastMediaOptions media = new CastMediaOptions.Builder()
                .setNotificationOptions(notification)
                .setMediaSessionEnabled(true) // lock-screen / system media controls
                .build();
        return new CastOptions.Builder()
                .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                .setCastMediaOptions(media)
                .setStopReceiverApplicationWhenEndingSession(true)
                .setResumeSavedSession(true)
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        return null;
    }
}
