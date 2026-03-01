package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;
import android.util.Pair;
import androidx.annotation.NonNull;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.playback.base.PlaybackServiceMediaPlayer;
import de.danoeh.antennapod.playback.base.PlayerStatus;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocalPSMP extends PlaybackServiceMediaPlayer {
    private static final String TAG = "LocalPSMP";

    private volatile PlayerStatus statusBeforeSeeking;
    private volatile android.media.MediaPlayer mediaPlayer;
    private volatile Playable media;
    private volatile boolean stream;
    private volatile MediaType mediaType;
    private volatile AtomicBoolean startWhenPrepared;
    private volatile boolean pausedBecauseOfTransientFocusLoss;
    private volatile CountDownLatch seekLatch;

    private final AudioManager audioManager;

    public LocalPSMP(@NonNull Context context,
                     @NonNull PSMPCallback callback) {
        super(context, callback);
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.startWhenPrepared = new AtomicBoolean(false);
    }

    @Override
    public void seekTo(int t) {
        if (t < 0) {
            t = 0;
        }

        if (t >= getDuration()) {
            Log.d(TAG, "Seek reached end of file, skipping to next episode");
            endPlayback(true, true, true, true);
            return;
        }

        if (playerStatus == PlayerStatus.PLAYING
                || playerStatus == PlayerStatus.PAUSED
                || playerStatus == PlayerStatus.PREPARED) {
            if(seekLatch != null && seekLatch.getCount() > 0) {
                try {
                    seekLatch.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Log.e(TAG, Log.getStackTraceString(e));
                }
            }
            seekLatch = new CountDownLatch(1);
            statusBeforeSeeking = playerStatus;
            setPlayerStatus(PlayerStatus.SEEKING, media, getPosition());
            mediaPlayer.seekTo(t);
            if (statusBeforeSeeking == PlayerStatus.PREPARED) {
                media.setPosition(t);
            }
            try {
                seekLatch.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, Log.getStackTraceString(e));
            }
            // After seek completes, update the stored position to the actual player position.
            // This is especially important for streamed content where the player may settle
            // at a keyframe-aligned position different from the requested position 't'.
            if (media != null) {
                media.setPosition(getPosition());
            }
        } else if (playerStatus == PlayerStatus.INITIALIZED) {
            media.setPosition(t);
            startWhenPrepared.set(false);
            prepare();
        }
    }
}
