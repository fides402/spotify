package com.monochrome.app;

import android.app.Application;
import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

public class MonoApplication extends Application {
    private AudioManager dummyAudioManager;

    @Override
    public Object getSystemService(String name) {
        if (Context.AUDIO_SERVICE.equals(name)) {
            if (dummyAudioManager == null) {
                dummyAudioManager = new AudioManager(getBaseContext()) {
                    @Override
                    public int requestAudioFocus(OnAudioFocusChangeListener l, int streamType, int durationHint) {
                        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                    }

                    @Override
                    public int requestAudioFocus(AudioFocusRequest focusRequest) {
                        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                    }

                    @Override
                    public int abandonAudioFocus(OnAudioFocusChangeListener l) {
                        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                    }

                    @Override
                    public int abandonAudioFocusRequest(AudioFocusRequest focusRequest) {
                        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                    }
                };
            }
            return dummyAudioManager;
        }
        return super.getSystemService(name);
    }
}
