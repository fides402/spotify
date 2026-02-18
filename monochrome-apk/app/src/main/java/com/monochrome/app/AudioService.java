package com.monochrome.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Foreground media service with Spotify-style interactive player notification.
     *
     * Features:
 * - MediaSession: exposes controls to the OS (lock screen, Bluetooth, headphones, Google Assistant)
     * - MediaStyle notification: shows artwork + prev/play-pause/next like Spotify
     * - Artwork: fetched asynchronously from URL provided by injected JS
     * - Bidirectional: notification buttons -> broadcast -> MainActivity -> WebView JS
     *   WebView JS -> AndroidBridge -> startService(ACTION_UPDATE) -> notification refresh
     */
public class AudioService extends Service {

    static final String CHANNEL_ID = "monochrome_audio";
        static final int NOTIFICATION_ID = 1;

    // Notification button actions (sent to this service as start-intents)
    static final String ACTION_PLAY_PAUSE = "com.monochrome.app.PLAY_PAUSE";
        static final String ACTION_NEXT       = "com.monochrome.app.NEXT";
        static final String ACTION_PREV       = "com.monochrome.app.PREV";

    // State update action: web app -> JS bridge -> MainActivity -> service
    static final String ACTION_UPDATE     = "com.monochrome.app.UPDATE_PLAYBACK";
        static final String EXTRA_IS_PLAYING  = "is_playing";
        static final String EXTRA_TITLE       = "title";
        static final String EXTRA_ARTIST      = "artist";
        static final String EXTRA_ARTWORK_URL = "artwork_url";

    private MediaSession mediaSession;
        private NotificationManager notifManager;
        private boolean foregroundStarted = false;

    private boolean isPlaying  = false;
        private String  trackTitle  = "Monochrome";
        private String  trackArtist = "";
        private String  artworkUrl  = "";
        private Bitmap  artworkBitmap = null;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ───────────────────────────────────────────────────────────

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

            if (ACTION_PLAY_PAUSE.equals(action) || ACTION_NEXT.equals(action) || ACTION_PREV.equals(action)) {
                            // Notification button tapped -> relay to MainActivity via broadcast
                        Intent relay = new Intent(action);
                            relay.setPackage(getPackageName());
                            sendBroadcast(relay);

            } else if (ACTION_UPDATE.equals(action)) {
                            // State reported by the web app via AndroidBridge.updatePlayback()
                        isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, isPlaying);
                            String t = intent.getStringExtra(EXTRA_TITLE);
                            String a = intent.getStringExtra(EXTRA_ARTIST);
                            String u = intent.getStringExtra(EXTRA_ARTWORK_URL);
                            if (t != null && !t.isEmpty()) trackTitle  = t;
                            if (a != null)                 trackArtist = a;

                        // Fetch artwork only if URL changed
                        if (u != null && !u.isEmpty() && !u.equals(artworkUrl)) {
                                            artworkUrl = u;
                                            fetchArtwork(u);
                                            return START_STICKY; // notification will be posted after fetch
                        }
                            updateMediaSession();
            }

            // (re-)post notification; first call -> startForeground, subsequent -> notify()
            postForeground();
                    return START_STICKY;
        }

    @Override
        public IBinder onBind(Intent intent) { return null; }

    @Override
        public void onDestroy() {
                    if (mediaSession != null) {
                                    mediaSession.setActive(false);
                                    mediaSession.release();
                    }
                    super.onDestroy();
        }

    // ── Artwork fetch ────────────────────────────────────────────────────────

    private void fetchArtwork(final String urlStr) {
                new Thread(new Runnable() {
                                @Override
                                public void run() {
                                                    Bitmap bmp = null;
                                                    try {
                                                                            URL url = new URL(urlStr);
                                                                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                                                                            conn.setConnectTimeout(5000);
                                                                            conn.setReadTimeout(8000);
                                                                            conn.setRequestProperty("User-Agent",
                                                                                                                                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36");
                                                                            conn.connect();
                                                                            if (conn.getResponseCode() == 200) {
                                                                                                        InputStream is = conn.getInputStream();
                                                                                                        Bitmap raw = BitmapFactory.decodeStream(is);
                                                                                                        is.close();
                                                                                                        if (raw != null) {
                                                                                                                                        // Square-crop to match Spotify style
                                                                                                            int side = Math.min(raw.getWidth(), raw.getHeight());
                                                                                                                                        int x = (raw.getWidth()  - side) / 2;
                                                                                                                                        int y = (raw.getHeight() - side) / 2;
                                                                                                                                        bmp = Bitmap.createBitmap(raw, x, y, side, side);
                                                                                                                                        if (bmp != raw) raw.recycle();
                                                                                                            }
                                                                                }
                                                                            conn.disconnect();
                                                    } catch (Exception e) {
                                                                            bmp = null;
                                                    }

                                    final Bitmap result = bmp;
                                                    mainHandler.post(new Runnable() {
                                                                            @Override
                                                                            public void run() {
                                                                                                        artworkBitmap = result;
                                                                                                        updateMediaSession();
                                                                                                        postForeground();
                                                                                }
                                                    });
                                }
                }).start();
    }

    // ── MediaSession ──────────────────────────────────────────────────────────

    private void createMediaSession() {
                mediaSession = new MediaSession(this, "MonochromeSession");
                mediaSession.setCallback(new MediaSession.Callback() {
                                @Override public void onPlay()            { relay(ACTION_PLAY_PAUSE); }
                                @Override public void onPause()           { relay(ACTION_PLAY_PAUSE); }
                                @Override public void onSkipToNext()      { relay(ACTION_NEXT); }
                                @Override public void onSkipToPrevious()  { relay(ACTION_PREV); }
                });
                mediaSession.setActive(true);
                updateMediaSession();
    }

    private void relay(String action) {
                Intent i = new Intent(action);
                i.setPackage(getPackageName());
                sendBroadcast(i);
    }

    private void updateMediaSession() {
                if (mediaSession == null) return;

            String title  = (trackTitle  != null && !trackTitle.isEmpty())  ? trackTitle  : "Monochrome";
                String artist = (trackArtist != null)                            ? trackArtist : "";

            MediaMetadata.Builder metaBuilder = new MediaMetadata.Builder()
                                .putString(MediaMetadata.METADATA_KEY_TITLE,  title)
                                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                                .putString(MediaMetadata.METADATA_KEY_ALBUM,  "Monochrome");

            if (artworkBitmap != null) {
                            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,  artworkBitmap);
                            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART,        artworkBitmap);
            }

            mediaSession.setMetadata(metaBuilder.build());

            int stateVal = isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
                mediaSession.setPlaybackState(new PlaybackState.Builder()
                                                              .setActions(
                                                                                          PlaybackState.ACTION_PLAY |
                                                                                          PlaybackState.ACTION_PAUSE |
                                                                                          PlaybackState.ACTION_PLAY_PAUSE |
                                                                                          PlaybackState.ACTION_SKIP_TO_NEXT |
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

    private PendingIntent makeServicePi(String action) {
                Intent i = new Intent(this, AudioService.class);
                i.setAction(action);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT |
                                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
                return PendingIntent.getService(this, action.hashCode(), i, flags);
    }

    private Notification buildNotification() {
                // Content intent: tap notification -> open app
            Intent tapIntent = new Intent(this, MainActivity.class);
                tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT |
                                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
                PendingIntent openApp = PendingIntent.getActivity(this, 0, tapIntent, piFlags);

            PendingIntent prevPi = makeServicePi(ACTION_PREV);
                PendingIntent ppPi   = makeServicePi(ACTION_PLAY_PAUSE);
                PendingIntent nextPi = makeServicePi(ACTION_NEXT);

            String title    = (trackTitle  != null && !trackTitle.isEmpty())   ? trackTitle  : "Monochrome";
                String subTitle = (trackArtist != null && !trackArtist.isEmpty())  ? trackArtist : "Riproduzione in corso";

            int ppIcon  = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
                String ppLabel = isPlaying ? "Pausa" : "Riproduci";

            Notification.Action prevAction = buildAction(android.R.drawable.ic_media_previous, "Precedente", prevPi);
                Notification.Action ppAction   = buildAction(ppIcon, ppLabel, ppPi);
                Notification.Action nextAction = buildAction(android.R.drawable.ic_media_next, "Successivo", nextPi);

            Notification.Builder builder;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                builder = new Notification.Builder(this, CHANNEL_ID);
                } else {
                                //noinspection deprecation
                    builder = new Notification.Builder(this)
                                            .setPriority(Notification.PRIORITY_LOW);
                }

            builder
                                .setContentTitle(title)
                                .setContentText(subTitle)
                                .setSmallIcon(R.mipmap.ic_launcher)
                                .setContentIntent(openApp)
                                .setOngoing(true)
                                .setOnlyAlertOnce(true)
                                .addAction(prevAction)
                                .addAction(ppAction)
                                .addAction(nextAction)
                                .setStyle(new Notification.MediaStyle()
                                                                  .setMediaSession(mediaSession.getSessionToken())
                                                                  .setShowActionsInCompactView(0, 1, 2));

            // Large icon (album art) — this is what makes it look like Spotify
            if (artworkBitmap != null) {
                            builder.setLargeIcon(artworkBitmap);
            }

            // On Android 12+ the OS renders a proper "media controls" card
            // with the artwork from MediaSession metadata automatically.
            // The setLargeIcon() ensures it also shows in the compact view.

            return builder.build();
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
