package com.spotify.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import com.spotify.app.player.CoexistingPlaybackService

class MainActivity : AppCompatActivity() {

    private var playbackService: CoexistingPlaybackService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playbackService = (service as CoexistingPlaybackService.LocalBinder).getService()
            bound = true
            updatePlayPauseVisibility()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val intent = Intent(this, CoexistingPlaybackService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        findViewById<Button>(R.id.btn_play).setOnClickListener {
            if (bound) {
                playbackService?.play(SAMPLE_AUDIO_URL)
                Toast.makeText(this, "Riproduzione avviata. Altre app possono continuare a suonare.", Toast.LENGTH_SHORT).show()
                updatePlayPauseVisibility()
            }
        }
        findViewById<Button>(R.id.btn_play_pause).setOnClickListener {
            playbackService?.playPause()
            updatePlayPauseVisibility()
        }
    }

    private fun updatePlayPauseVisibility() {
        val isPlaying = playbackService?.getPlayer()?.isPlaying == true
        findViewById<Button>(R.id.btn_play_pause).apply {
            visibility = if (isPlaying || (playbackService?.getPlayer()?.playbackState ?: Player.STATE_IDLE) != Player.STATE_IDLE) View.VISIBLE else View.GONE
            text = if (isPlaying) getString(R.string.pause) else getString(R.string.play)
        }
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    companion object {
        private const val SAMPLE_AUDIO_URL = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
    }
}
