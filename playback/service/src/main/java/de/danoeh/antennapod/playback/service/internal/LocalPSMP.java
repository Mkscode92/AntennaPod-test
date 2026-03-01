package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.media.AudioManager;
import android.os.PowerManager;
import android.util.Log;
import android.util.Pair;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.ExoPlayer;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.playback.base.PlaybackServiceMediaPlayer;
import de.danoeh.antennapod.playback.base.PlayerStatus;
import de.danoeh.antennapod.playback.service.R;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages media playback. The PodcastManager should be the only class that is allowed to
 * instantiate an object of this class.
 */
public class LocalPSMP extends PlaybackServiceMediaPlayer {
    private static final String TAG = "LocalPSMP";

    private final Context context;
    private final AudioManager audioManager;

    private volatile PlayerStatus statusBeforeSeeking;
    private volatile android.media.MediaPlayer mediaPlayer;
    private volatile Playable media;

    private volatile boolean stream;
    private volatile MediaType mediaType;
    private volatile AtomicBoolean startWhenPrepared;
    private volatile boolean pausedBecauseOfTransientFocusLoss;
    private volatile CountDownLatch seekLatch;

    public LocalPSMP(Context context, PSMPCallback callback) {
        super(callback);
        this.context = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.startWhenPrepared = new AtomicBoolean(false);
    }

    @Override
    public void seekTo(int t) {
        if (t < 0) {
            t = 0;
        }

        // Only check duration-based end-of-file for locally downloaded media.
        // For streams, getDuration() may be unreliable (the full file isn't buffered yet),
        // so we must not use it to gate seeks — doing so causes premature playback termination
        // and skipping of large portions of streamed podcasts.
        boolean isLocalFile = (media instanceof FeedMedia) && ((FeedMedia) media).localFileAvailable();
        if (isLocalFile && t >= getDuration()) {
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
        } else if (playerStatus == PlayerStatus.INITIALIZED) {
            media.setPosition(t);
            startWhenPrepared.set(false);
            prepare();
        }
    }
}
