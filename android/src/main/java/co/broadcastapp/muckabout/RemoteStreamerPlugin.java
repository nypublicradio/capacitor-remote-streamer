package co.broadcastapp.muckabout;

import static java.util.Set.of;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.content.ContextCompat;

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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Set;
import java.util.stream.Stream;

@CapacitorPlugin(name = "RemoteStreamer")
public class RemoteStreamerPlugin extends Plugin implements AudioManager.OnAudioFocusChangeListener {
    private ExoPlayer player;
    private DefaultDataSource.Factory dataSourceFactory;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private Handler handler;
    private Runnable updateTimeTask;
    private boolean isLiveStream = false;

    private MediaSessionCompat mediaSession;
    private RemoteStreamerService service = null;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            RemoteStreamerService.LocalBinder binder = (RemoteStreamerService.LocalBinder) iBinder;
            service = binder.getService();
            Intent intent = new Intent(getActivity(), getActivity().getClass());
            service.connectAndInitialize(RemoteStreamerPlugin.this, intent);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d("Stream", "Disconnected from MediaSessionService");
        }
    };


    @Override
    public void load() {
        super.load();
        Context context = getContext();
        handler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        dataSourceFactory = new DefaultDataSource.Factory(context);
        mediaSession = new MediaSessionCompat(context, "wnyc");

        startMediaService();

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

    public void startMediaService() {
        Intent intent = new Intent(getActivity(), RemoteStreamerService.class);
        ContextCompat.startForegroundService(getContext(), intent);
        getContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
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
                this.isLiveStream = true;
                service.setDuration(0);
                service.setPosition(0);
            } else {
                mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(url));
                this.isLiveStream = false;
            }

            player.setMediaSource(mediaSource);
            player.prepare();

            setupPlayerListeners();

            int focusResult = audioManager.requestAudioFocus(focusRequest);
            if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                player.play();
            }
            Log.d("stream", "playing");
            service.setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
            service.update();

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
                        if (!isLiveStream) startUpdatingTime();
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
        pause();
        call.resolve();
    }

    private void pause() {
        if (player != null) {
            Log.d("RemoteStreamerPlugin", "pausing playback");
            handler.post(() -> {
                service.setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                service.update();
                if (player != null) {
                    player.pause();
                }
            });
            notifyListeners("pause", new JSObject());
        }
    }

    @PluginMethod
    public void resume(PluginCall call) {
        resume();
        call.resolve();
    }

    private void resume() {
        if (player != null) {
            Log.d("RemoteStreamerPlugin", "resuming playback");
            int focusResult = audioManager.requestAudioFocus(focusRequest);
            if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                handler.post(() -> {
                    if (player != null) {
                        player.play();
                    }
                });
                service.setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                service.update();
                notifyListeners("play", new JSObject());
            }
        }
    }

    @PluginMethod
    public void seekTo(PluginCall call) {
        Long position = null;
        try {
            position = call.getData().getLong("position") * 1000; // s to ms
        } catch (JSONException e) {
            call.reject("Can't parse position " + call.getData().toString());
            return;
        }
        seekTo(position);
        call.resolve();
    }

    private void seekTo(Long position) {
        if (player != null) {
            handler.post(() -> player.seekTo(position));
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        releasePlayer();
        service.setPlaybackState(PlaybackStateCompat.STATE_NONE);
        service.update();
        notifyListeners("stop", new JSObject());
        call.resolve();
    }

    @PluginMethod
    public void setNowPlayingInfo(PluginCall call) throws JSONException, IOException {
        String title = call.getData().getString("title", "WNYC");
        String artist = call.getString("artist", "");
        String album = call.getString("album", "");
        String artwork = call.getString("imageUrl", "");

        service.setTitle(title);
        service.setArtist(artist);
        service.setAlbum(album);
        service.setArtwork(getImage(artwork));
        service.update();

        call.resolve();
    }

    private Bitmap getImage(String url) {
        try {
            URL imageUrl = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) imageUrl.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            return BitmapFactory.decodeStream(input);
        } catch (MalformedURLException mfue) {
            Log.e("streamer", "bad image URL " + url);
        } catch (IOException ioe) {
            Log.e("streamer", "could not parse image " + url);
        }
        return null;
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
                    service.setDuration(duration);
                    service.setPosition(currentTime);
                    service.update();
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

    private boolean resumeOnFocusLossTransient = false;

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (player == null) {
            return;
        }

        handler.post(() -> {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    player.setVolume(1.0f);
                    if (resumeOnFocusLossTransient) {
                        player.play();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    resumeOnFocusLossTransient = player.isPlaying();
                    player.pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    player.setVolume(0.1f);
                    break;
            }
        });
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        handler.post(() -> {
            Float volume;
            try {
                volume = (float) call.getData().getDouble("volume");
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            player.setVolume(volume);
        });
    }

    public void actionCallback(String action) {
        actionCallback(action, new JSObject());
    }
    public void actionCallback(String action, JSObject data) {
        Log.d("streamer", "action: " + action);
        switch (action) {
            case "pause":
                pause();
                break;

            case "play":
                resume();
                break;

            case "nexttrack":
                seekTo(player.getCurrentPosition() + 10000);
                break;

            case "previoustrack":
                seekTo(player.getCurrentPosition() - 10000);
                break;

            case "seekto":
                try {
                    long pos = data.getLong("seekTime");
                    seekTo(pos);
                } catch (JSONException e) {
                    Log.e("streamer", "Can't parse position " + data.toString());
                }
                break;
        }
    }

    private final Set<String> lsactions = Set.of("pause", "play");
    private final Set<String> odactions = Set.of("pause", "play", "nexttrack", "previoustrack","seekto");
    public boolean hasActionHandler(String actionName) {
        if (isLiveStream) {
            return this.lsactions.contains(actionName);
        }
        return this.odactions.contains(actionName);
    }
}