package com.monochrome.app;

import android.app.Application;
import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

public class MonoApplication extends Application {
    private Object audioManagerWrapper;
    private AudioManager realAudioManager;
    private AudioManager.OnAudioFocusChangeListener emptyFocusListener;
    private AudioFocusRequest focusRequest;

    @Override
    public void onCreate() {
        super.onCreate();

        realAudioManager = (AudioManager) super.getSystemService(Context.AUDIO_SERVICE);
        emptyFocusListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {
                // Ignore all focus changes
            }
        };

        try {
            audioManagerWrapper = new AudioManager(getBaseContext()) {
                @Override
                public int requestAudioFocus(OnAudioFocusChangeListener l, int streamType, int durationHint) {
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }

                @Override
                public int requestAudioFocus(AudioFocusRequest request) {
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }

                @Override
                public int abandonAudioFocus(OnAudioFocusChangeListener l) {
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }

                @Override
                public int abandonAudioFocusRequest(AudioFocusRequest request) {
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }
            };
        } catch (Exception e) {
            audioManagerWrapper = null;
            // Fallback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setOnAudioFocusChangeListener(emptyFocusListener)
                        .build();
                realAudioManager.requestAudioFocus(focusRequest);
            } else {
                realAudioManager.requestAudioFocus(emptyFocusListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
        }
    }

    @Override
    public Object getSystemService(String name) {
        if (Context.AUDIO_SERVICE.equals(name)) {
            if (audioManagerWrapper != null) {
                return audioManagerWrapper;
            }
            return realAudioManager;
        }
        return super.getSystemService(name);
    }
}
