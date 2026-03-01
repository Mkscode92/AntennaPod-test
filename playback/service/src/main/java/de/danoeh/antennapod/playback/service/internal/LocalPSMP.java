package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.media.AudioManager;
import android.os.PowerManager;
import android.util.Log;
import android.util.Pair;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.MediaType;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.playback.base.PlaybackServiceMediaPlayer;
import de.danoeh.antennapod.playback.base.PlayerStatus;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public class LocalPSMP extends PlaybackServiceMediaPlayer {

    private static final String TAG = "LocalPSMP";

    private final Context context;
    private volatile PlayerStatus statusBeforeSeeking;
    private volatile ExoPlayer mediaPlayer;
    private volatile Playable media;
    private volatile PlayerStatus playerStatus;
    private volatile boolean stream;
    private volatile MediaType mediaType;
    private volatile AtomicBoolean startWhenPrepared;
    private volatile boolean pausedBecauseOfTransientFocusLoss;
    private volatile CountDownLatch seekLatch;
    private final AudioManager audioManager;

    public LocalPSMP(Context context, PSMPCallback callback) {
        super(context, callback);
        this.context = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.startWhenPrepared = new AtomicBoolean(false);
        this.mediaType = MediaType.UNKNOWN;
        this.playerStatus = PlayerStatus.STOPPED;
    }

    @Override
    public void playMediaObject(final Playable playable, final boolean stream,
                                final boolean startWhenPrepared, final boolean prepareImmediately) {
        Log.d(TAG, "playMediaObject called");
        this.stream = stream;
        this.startWhenPrepared.set(startWhenPrepared);
        setPlayerStatus(PlayerStatus.INITIALIZING, null);
        this.media = playable;
        this.mediaType = media.getMediaType();
        this.mediaPlayer = createMediaPlayer();
        setMediaPlayerDataSource(playable, stream);
        setPlayerStatus(PlayerStatus.INITIALIZED, media);
        if (prepareImmediately) {
            prepare();
        }
    }

    private ExoPlayer createMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        ExoPlayer player = new ExoPlayer.Builder(context).build();
        player.addListener(componentListener);
        player.setWakeMode(PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioAttributes(
                new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                false);
        return player;
    }

    private void setMediaPlayerDataSource(Playable playable, boolean stream) {
        try {
            String source = stream ? playable.getStreamUrl() : playable.getLocalMediaUrl();
            MediaItem mediaItem = MediaItem.fromUri(source);
            MediaSource mediaSource;
            if (source != null && (source.startsWith("http://") || source.startsWith("https://"))) {
                DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory();
                DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpFactory);
                if (source.contains(".m3u8")) {
                    mediaSource = new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem);
                } else {
                    mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem);
                }
            } else {
                DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context);
                mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem);
            }
            mediaPlayer.setMediaSource(mediaSource);
        } catch (Exception e) {
            Log.e(TAG, "Error setting data source", e);
        }
    }

    public void seekTo(int t) {
        if (t < 0) {
            t = 0;
        }

        // Only apply the "seek past end" check for locally downloaded media.
        // For streamed media, getDuration() may be unreliable (under-reported or
        // unavailable during buffering), so skipping to endPlayback() based on it
        // causes large audio skips and premature truncation of streamed podcasts.
        boolean isStreaming = (media instanceof FeedMedia) && !((FeedMedia) media).isDownloaded();
        if (!isStreaming && t >= getDuration()) {
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

    @Override
    public boolean canSetSpeed() {
        return true;
    }

    @Override
    public boolean canDownmix() {
        return false;
    }

    @Override
    public void setSpeed(float speed) {
        PlaybackParameters params = new PlaybackParameters(speed);
        if (mediaPlayer != null) {
            mediaPlayer.setPlaybackParameters(params);
        }
        callback.playbackSpeedChanged(speed);
    }

    @Override
    public float getPlaybackSpeed() {
        if (mediaPlayer != null) {
            return mediaPlayer.getPlaybackParameters().speed;
        }
        return 1;
    }

    @Override
    public void setVolume(float volumeLeft, float volumeRight) {
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(volumeLeft);
        }
    }

    @Override
    public void setDownmix(boolean enable) {
        // not supported
    }

    @Override
    public MediaType getCurrentMediaType() {
        return mediaType;
    }

    @Override
    public boolean isStreamingMedia() {
        return stream;
    }

    @Override
    public void setSkipSilence(boolean skipSilence) {
        if (mediaPlayer != null) {
            mediaPlayer.setSkipSilenceEnabled(skipSilence);
        }
    }

    @Override
    public boolean getSkipSilence() {
        if (mediaPlayer != null) {
            return mediaPlayer.getSkipSilenceEnabled();
        }
        return false;
    }

    @Override
    public PlayerStatus getPlayerStatus() {
        return playerStatus;
    }

    @Override
    public Playable getPlayable() {
        return media;
    }

    @Override
    protected void setPlayable(Playable playable) {
        media = playable;
    }

    @Override
    public List<String> getAudioTracks() {
        return List.of();
    }

    @Override
    public int getSelectedAudioTrack() {
        return -1;
    }

    @Override
    public void setAudioTrack(int track) {
        // not supported
    }

    @Override
    public int getBufferedPercentage() {
        if (mediaPlayer != null) {
            return mediaPlayer.getBufferedPercentage();
        }
        return 0;
    }

    @Override
    protected void startImpl() {
        if (mediaPlayer != null) {
            mediaPlayer.play();
        }
    }

    @Override
    protected void pauseImpl() {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
        }
    }

    @Override
    protected void resumeImpl() {
        if (mediaPlayer != null) {
            mediaPlayer.play();
        }
    }

    @Override
    protected void prepareImpl() {
        if (mediaPlayer != null) {
            mediaPlayer.prepare();
        }
    }

    @Override
    protected void reinitImpl() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    protected void stopImpl() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
        }
    }

    @Override
    public int getDuration() {
        if (mediaPlayer == null) {
            return Playable.INVALID_TIME;
        }
        long duration = mediaPlayer.getDuration();
        if (duration == C.TIME_UNSET) {
            return media != null ? media.getDuration() : Playable.INVALID_TIME;
        }
        return (int) duration;
    }

    @Override
    public int getPosition() {
        if (mediaPlayer == null || playerStatus == PlayerStatus.INITIALIZED) {
            return media != null ? media.getPosition() : Playable.INVALID_TIME;
        }
        return (int) mediaPlayer.getCurrentPosition();
    }

    @Override
    public boolean isPlaying() {
        return playerStatus == PlayerStatus.PLAYING;
    }

    private final Player.Listener componentListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (mediaPlayer == null) {
                return;
            }
            switch (playbackState) {
                case Player.STATE_IDLE:
                    break;
                case Player.STATE_BUFFERING:
                    break;
                case Player.STATE_READY:
                    if (playerStatus == PlayerStatus.PREPARING) {
                        setPlayerStatus(PlayerStatus.PREPARED, media);
                        if (media.getDuration() <= 0) {
                            Log.d(TAG, "Duration is not set, using getDuration()");
                            media.setDuration(getDuration());
                        }
                        if (startWhenPrepared.get()) {
                            setPlayerStatus(PlayerStatus.PLAYING, media);
                            mediaPlayer.play();
                        }
                    } else if (playerStatus == PlayerStatus.SEEKING) {
                        if (seekLatch != null) {
                            seekLatch.countDown();
                        }
                        setPlayerStatus(statusBeforeSeeking, media);
                    }
                    break;
                case Player.STATE_ENDED:
                    if (playerStatus == PlayerStatus.PLAYING) {
                        endPlayback(true, true, true, true);
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (isPlaying && playerStatus != PlayerStatus.PLAYING) {
                setPlayerStatus(PlayerStatus.PLAYING, media);
            } else if (!isPlaying && playerStatus == PlayerStatus.PLAYING) {
                setPlayerStatus(PlayerStatus.PAUSED, media);
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            Log.e(TAG, "ExoPlayer error: " + error.getMessage(), error);
            setPlayerStatus(PlayerStatus.ERROR, media);
            callback.onMediaPlayerError(this, 0, 0);
        }
    };

    @Override
    protected void setPlayerStatus(PlayerStatus newStatus, Playable newMedia) {
        this.playerStatus = newStatus;
        callback.statusChanged(new PSMPInfo(newStatus, newMedia));
    }

    protected void setPlayerStatus(PlayerStatus newStatus, Playable newMedia, int position) {
        this.playerStatus = newStatus;
        callback.statusChanged(new PSMPInfo(newStatus, newMedia));
    }
}
