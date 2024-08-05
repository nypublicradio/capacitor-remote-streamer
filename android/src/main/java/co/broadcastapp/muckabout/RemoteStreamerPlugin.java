package co.broadcastapp.muckabout;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;

import org.json.JSONException;

import java.io.IOException;

@CapacitorPlugin(name = "RemoteStreamer")
public class RemoteStreamerPlugin extends Plugin implements AudioManager.OnAudioFocusChangeListener {
    private ExoPlayer player;
    private DefaultDataSource.Factory dataSourceFactory;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private Handler handler;
    private Runnable updateTimeTask;

    private MediaSessionCompat mediaSession;

    @Override
    public void load() {
        super.load();
        Context context = getContext();
        handler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        dataSourceFactory = new DefaultDataSource.Factory(context);
        mediaSession = new MediaSessionCompat(context, "wnyc");

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build();
    }

    @PluginMethod
    public void play(PluginCall call) {
        String url = call.getString("url");
        if (url == null) {
            call.reject("URL is required");
            return;
        }

        handler.post(() -> {
            releasePlayer();
            player = new ExoPlayer.Builder(getContext()).build();

            MediaSource mediaSource;
            if (url.contains(".m3u8")) {
                mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(url));
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(url));
            }

            player.setMediaSource(mediaSource);
            player.prepare();

            setupPlayerListeners();

            int focusResult = audioManager.requestAudioFocus(focusRequest);
            if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                player.play();
            }

            notifyListeners("play", new JSObject());
            call.resolve();
        });
    }

    private void setupPlayerListeners() {
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        notifyListeners("buffering", new JSObject().put("isBuffering", true));
                        break;
                    case Player.STATE_READY:
                        notifyListeners("buffering", new JSObject().put("isBuffering", false));
                        startUpdatingTime();
                        break;
                    case Player.STATE_ENDED:
                        stopUpdatingTime();
                        notifyListeners("stop", new JSObject());
                        break;
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    notifyListeners("play", new JSObject());
                } else {
                    notifyListeners("pause", new JSObject());
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                notifyListeners("error", new JSObject().put("message", error.getMessage()));
            }
        });
    }

    @PluginMethod
    public void pause(PluginCall call) {
        if (player != null) {
            Log.d("RemoteStreamerPlugin", "pausing playback");
                handler.post(() -> {
                    if (player != null) {
                        player.pause();
                    }
                });
            notifyListeners("pause", new JSObject());
        }
        call.resolve();
    }

    @PluginMethod
    public void resume(PluginCall call) {
        if (player != null) {
            Log.d("RemoteStreamerPlugin", "resuming playback");
            int focusResult = audioManager.requestAudioFocus(focusRequest);
            if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                handler.post(() -> {
                    if (player != null) {
                        player.play();
                    }
                });
                notifyListeners("play", new JSObject());
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void seekTo(PluginCall call) {
        Long position = call.getLong("position");
        if (position == null) {
            call.reject("Position is required");
            return;
        }
        if (player != null) {
            handler.post(() -> player.seekTo(position));
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        releasePlayer();
        notifyListeners("stop", new JSObject());
        call.resolve();
    }

    @PluginMethod
    public void setNowPlayingInfo(PluginCall call) throws JSONException, IOException {
        String title = call.getString("title");
        String artist = call.getString("artist");
        String album = call.getString("album");

        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album);
        mediaSession.setMetadata(builder.build());

        call.resolve();
    }

    private void releasePlayer() {
        if (player != null) {
            handler.post(() -> {
                Log.d("RemoteStreamerPlugin", "releaseing player");
                stopUpdatingTime();
                player.release();
                player = null;
                audioManager.abandonAudioFocusRequest(focusRequest);
            });
        }
    }

    private void startUpdatingTime() {
        stopUpdatingTime();
        updateTimeTask = new Runnable() {
            @Override
            public void run() {
                if (player != null && player.isPlaying()) {
                    long currentTime = player.getCurrentPosition();
                    long duration = player.getDuration();
                    JSObject timeData = new JSObject()
                            .put("currentTime", currentTime / 1000.0)
                            .put("duration", duration == C.TIME_UNSET ? 0 : duration / 1000.0);
                    notifyListeners("timeUpdate", timeData);
                    handler.postDelayed(this, 500);
                } else {
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.post(updateTimeTask);
    }

    private void stopUpdatingTime() {
        if (updateTimeTask != null) {
            handler.removeCallbacks(updateTimeTask);
            updateTimeTask = null;
        }
    }

    @Override
    protected void handleOnDestroy() {
        releasePlayer();
        super.handleOnDestroy();
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (player == null) {
            return;
        }

        handler.post(() -> {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    player.setVolume(1.0f);
                    player.play();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    player.pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    player.pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    player.setVolume(0.1f);
                    break;
            }
        });
    }
}