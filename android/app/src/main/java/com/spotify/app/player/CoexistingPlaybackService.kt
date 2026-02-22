package com.spotify.app.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.spotify.app.MainActivity
import com.spotify.app.R

/**
 * Servizio di riproduzione che usa [CoexistingAudioFocusHelper]:
 * - Richiede focus con MAY_DUCK così le altre app non si fermano.
 * - In perdita di focus fa solo duck del volume, non mette in pausa.
 * Così questa app e altre possono convivere con audio simultaneo.
 */
class CoexistingPlaybackService : Service() {

    private var exoPlayer: ExoPlayer? = null
    private var audioFocusHelper: CoexistingAudioFocusHelper? = null

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): CoexistingPlaybackService = this@CoexistingPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            volume = 1f
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
        }
        audioFocusHelper = CoexistingAudioFocusHelper(
            context = this,
            onVolumeDuck = { exoPlayer?.volume = DUCK_VOLUME },
            onVolumeRestore = { exoPlayer?.volume = 1f }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        audioFocusHelper?.abandonFocus()
        exoPlayer?.release()
        exoPlayer = null
        audioFocusHelper = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    fun play(url: String) {
        val player = exoPlayer ?: return
        if (audioFocusHelper?.requestFocus() != true) return
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
    }

    fun pause() = exoPlayer?.pause()
    fun playPause() {
        exoPlayer?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }
    fun stop() {
        audioFocusHelper?.abandonFocus()
        exoPlayer?.stop()
    }

    fun getPlayer(): ExoPlayer? = exoPlayer

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.playing))
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    companion object {
        const val DUCK_VOLUME = 0.2f
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
    }
}
