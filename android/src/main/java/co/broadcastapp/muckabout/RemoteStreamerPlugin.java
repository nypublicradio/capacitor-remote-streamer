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

import android.util.Log;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.Set;
import java.util.stream.Stream;
import org.json.JSONException;
import java.io.IOException;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.io.InputStream;

@CapacitorPlugin(name = "RemoteStreamer")
public class RemoteStreamerPlugin extends Plugin {
    private boolean isLiveStream = false;
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

        if (service == null) {
            startMediaService();
            // Wait for service to connect? Ideally we should queue the command or wait.
            // The original code waited with sleep loop.
            int retries = 0;
            while (service == null && retries < 20) {
                try {
                    Thread.sleep(100);
                    retries++;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        if (service != null) {
            service.play(url);
            call.resolve();
        } else {
            call.reject("Service failed to start");
        }
    }



    @PluginMethod
    public void pause(PluginCall call) {
        pause();
        call.resolve();
    }

    private void pause() {
        if (service != null) {
            service.pause();
        }
    }

    @PluginMethod
    public void resume(PluginCall call) {
        resume();
        call.resolve();
    }

    private void resume() {
        if (service != null) {
            service.resume();
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
        if (service != null) {
            service.seekTo(position);
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stop();
        call.resolve();
    }

    public void stop() {
        stop(false);
    }

    public void stop(final boolean ended) {
        if (service != null) {
            service.stop(ended);
        }
    }

    @PluginMethod
    public void setNowPlayingInfo(PluginCall call) throws JSONException, IOException {
        String title = call.getData().getString("title", "WNYC");
        String artist = call.getString("artist", "");
        String album = call.getString("album", "");
        String artwork = call.getString("imageUrl", "");

        if (service != null) {
            service.setTitle(title);
            service.setArtist(artist);
            service.setAlbum(album);
            service.setArtwork(getImage(artwork));
            service.update();
        } else {
            call.reject("Service is not initialized");
            return;
        }

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

    @PluginMethod
    public void releasePlayer(PluginCall call) {
        releasePlayer();
        call.resolve();
    }

    private void releasePlayer() {
        if (service != null) {
            service.releasePlayer();
        }
    }



    @Override
    protected void handleOnDestroy() {
        // Do not stop player here to allow background playback
        super.handleOnDestroy();
    }



    @PluginMethod
    public void setVolume(PluginCall call) {
        Float volume;
        try {
            volume = (float) call.getData().getDouble("volume");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        if (service != null) {
            service.setVolume(volume);
        }
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
                if (service != null) {
                    seekTo(service.getCurrentPosition() + 10000);
                }
                break;

            case "previoustrack":
                if (service != null) {
                    seekTo(service.getCurrentPosition() - 10000);
                }
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

    public void onPlayerEvent(String event, JSObject data) {
        notifyListeners(event, data);
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