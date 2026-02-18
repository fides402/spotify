package com.monochrome.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service that keeps the process alive while audio plays.
 *
 * Android aggressively kills background processes to save battery. A foreground
 * service with FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK tells the OS "this app is
 * actively playing media" — it gets a much higher priority and is essentially
 * immune to background kills as long as the notification is visible.
 *
 * This is the standard, correct way to implement background audio on Android.
 * Without this, even with PARTIAL_WAKE_LOCK the OS can still kill the process
 * on low-memory situations or after a few minutes of inactivity.
 */
public class AudioService extends Service {

    static final String CHANNEL_ID = "monochrome_audio";
    static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notif = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notif);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Monochrome Audio",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Riproduzione audio in background");
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.enableLights(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent tap = new Intent(this, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, piFlags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Monochrome")
                    .setContentText("Riproduzione in corso")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build();
        } else {
            return new Notification.Builder(this)
                    .setContentTitle("Monochrome")
                    .setContentText("Riproduzione in corso")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(Notification.PRIORITY_LOW)
                    .build();
        }
    }
}
