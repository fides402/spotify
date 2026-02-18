package com.monochrome.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground media service with interactive player notification.
 *
 * Features:
 *  - MediaSession: exposes controls to the OS (lock screen, Bluetooth, headphones, Google Assistant)
 *  - MediaStyle notification: shows prev / play-pause / next buttons in the shade
 *  - Bidirectional: notification buttons → broadcast → MainActivity → WebView JS
 *                   WebView JS → AndroidBridge → startService(ACTION_UPDATE) → notification refresh
 */
public class AudioService extends Service {

    static final String CHANNEL_ID      = "monochrome_audio";
    static final int    NOTIFICATION_ID = 1;

    // Notification button actions (sent to this service as start-intents)
    static final String ACTION_PLAY_PAUSE = "com.monochrome.app.PLAY_PAUSE";
    static final String ACTION_NEXT       = "com.monochrome.app.NEXT";
    static final String ACTION_PREV       = "com.monochrome.app.PREV";

    // State update action: web app → JS bridge → MainActivity → service
    static final String ACTION_UPDATE    = "com.monochrome.app.UPDATE_PLAYBACK";
    static final String EXTRA_IS_PLAYING = "is_playing";
    static final String EXTRA_TITLE      = "title";
    static final String EXTRA_ARTIST     = "artist";

    private MediaSession        mediaSession;
    private NotificationManager notifManager;
    private boolean             foregroundStarted = false;
    private boolean             isPlaying         = false;
    private String              trackTitle        = "Monochrome";
    private String              trackArtist       = "";

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        createMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : null;

        if (ACTION_PLAY_PAUSE.equals(action) ||
                ACTION_NEXT.equals(action) ||
                ACTION_PREV.equals(action)) {
            // Notification button tapped → relay to MainActivity via broadcast
            Intent relay = new Intent(action);
            relay.setPackage(getPackageName());
            sendBroadcast(relay);

        } else if (ACTION_UPDATE.equals(action)) {
            // State reported by the web app via AndroidBridge.updatePlayback()
            isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, isPlaying);
            String t  = intent.getStringExtra(EXTRA_TITLE);
            String a  = intent.getStringExtra(EXTRA_ARTIST);
            if (t != null && !t.isEmpty()) trackTitle  = t;
            if (a != null)                 trackArtist = a;
            updateMediaSession();
        }
        // (re-)post notification; first call → startForeground, subsequent → notify()
        postForeground();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }

    // ── MediaSession ──────────────────────────────────────────────────────────

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "MonochromeSession");

        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay()           { relay(ACTION_PLAY_PAUSE); }
            @Override public void onPause()          { relay(ACTION_PLAY_PAUSE); }
            @Override public void onSkipToNext()     { relay(ACTION_NEXT); }
            @Override public void onSkipToPrevious() { relay(ACTION_PREV); }
        });

        mediaSession.setActive(true);
        updateMediaSession();
    }

    /** Broadcast a media-control action to MainActivity. */
    private void relay(String action) {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void updateMediaSession() {
        if (mediaSession == null) return;

        String title  = (trackTitle != null && !trackTitle.isEmpty()) ? trackTitle : "Monochrome";
        String artist = (trackArtist != null) ? trackArtist : "";

        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE,  title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .build());

        int stateVal = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(
                        PlaybackState.ACTION_PLAY            |
                        PlaybackState.ACTION_PAUSE           |
                        PlaybackState.ACTION_PLAY_PAUSE      |
                        PlaybackState.ACTION_SKIP_TO_NEXT    |
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(stateVal, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build());
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Monochrome Audio", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Riproduzione audio in background");
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.enableLights(false);
            notifManager.createNotificationChannel(ch);
        }
    }

    private void postForeground() {
        Notification notif = buildNotification();
        if (!foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notif);
            }
            foregroundStarted = true;
        } else {
            notifManager.notify(NOTIFICATION_ID, notif);
        }
    }

    /** PendingIntent that starts this service with a control action. */
    private PendingIntent makeServicePi(String action) {
        Intent i = new Intent(this, AudioService.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT |
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, action.hashCode(), i, flags);
    }

    private Notification buildNotification() {
        // Content intent: tap notification → open app
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT |
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openApp = PendingIntent.getActivity(this, 0, tapIntent, piFlags);

        PendingIntent prevPi = makeServicePi(ACTION_PREV);
        PendingIntent ppPi   = makeServicePi(ACTION_PLAY_PAUSE);
        PendingIntent nextPi = makeServicePi(ACTION_NEXT);

        String title  = (trackTitle != null && !trackTitle.isEmpty()) ? trackTitle : "Monochrome";
        String subText = (trackArtist != null && !trackArtist.isEmpty())
                ? trackArtist : "Riproduzione in corso";

        int ppIcon = isPlaying ? android.R.drawable.ic_media_pause
                               : android.R.drawable.ic_media_play;
        String ppLabel = isPlaying ? "Pausa" : "Riproduci";

        Notification.Action prevAction = buildAction(android.R.drawable.ic_media_previous, "Precedente", prevPi);
        Notification.Action ppAction   = buildAction(ppIcon, ppLabel, ppPi);
        Notification.Action nextAction = buildAction(android.R.drawable.ic_media_next,     "Successivo", nextPi);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            //noinspection deprecation
            builder = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_LOW);
        }

        return builder
                .setContentTitle(title)
                .setContentText(subText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(prevAction)
                .addAction(ppAction)
                .addAction(nextAction)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    @SuppressWarnings("deprecation")
    private Notification.Action buildAction(int iconRes, String label, PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new Notification.Action.Builder(
                    Icon.createWithResource(this, iconRes), label, pi).build();
        } else {
            return new Notification.Action.Builder(iconRes, label, pi).build();
        }
    }
}
