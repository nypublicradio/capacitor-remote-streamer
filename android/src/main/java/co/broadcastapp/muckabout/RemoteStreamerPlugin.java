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

import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.net.Uri;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

@CapacitorPlugin(name = "RemoteStreamer")
public class RemoteStreamerPlugin extends Plugin {
    private boolean isLiveStream = false;
    private RemoteStreamerService service = null;
    private boolean foregroundInitialized = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            RemoteStreamerService.LocalBinder binder = (RemoteStreamerService.LocalBinder) iBinder;
            service = binder.getService();
            if (foregroundInitialized) {
                Intent intent = new Intent(getActivity(), getActivity().getClass());
                service.connectAndInitialize(RemoteStreamerPlugin.this, intent);
            } else {
                service.connectPlugin(RemoteStreamerPlugin.this);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d("Stream", "Disconnected from MediaSessionService");
        }
    };


    @Override
    public void load() {
        super.load();
        boolean carEnabled = getConfig().getBoolean("carExperienceEnabled", false);
        RemoteStreamerService.carExperienceEnabled = carEnabled;
        Intent intent = new Intent(getActivity(), RemoteStreamerService.class);
        getContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    public void startMediaService() {
        foregroundInitialized = true;
        Intent intent = new Intent(getActivity(), RemoteStreamerService.class);
        ContextCompat.startForegroundService(getContext(), intent);
        if (service != null) {
            Intent activityIntent = new Intent(getActivity(), getActivity().getClass());
            service.connectAndInitialize(RemoteStreamerPlugin.this, activityIntent);
        } else {
            getContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }


    @PluginMethod
    public void setMediaItems(PluginCall call) {
        JSONArray items = call.getArray("items");
        if (items == null) {
            call.reject("items array is required");
            return;
        }

        if (service != null) {
            List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                try {
                    JSObject item = JSObject.fromJSONObject(items.getJSONObject(i));
                    String id = item.getString("id");
                    String title = item.getString("title");
                    String artist = item.getString("artist");
                    String imageUrl = item.getString("imageUrl");
                    String streamUrl = item.getString("streamUrl");

                    MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                            .setMediaId(id)
                            .setTitle(title)
                            .setSubtitle(artist)
                            .setIconUri(Uri.parse(imageUrl))
                            .setMediaUri(Uri.parse(streamUrl))
                            .build();

                    mediaItems.add(new MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
                } catch (JSONException e) {
                    Log.e("streamer", "Error parsing media item at index " + i, e);
                }
            }
            service.setMediaItems(mediaItems);
            call.resolve();
        } else {
            call.reject("Service not initialized");
        }
    }

    @PluginMethod
    public void play(PluginCall call) {
        String url = call.getString("url");
        if (url == null) {
            call.reject("URL is required");
            return;
        }

        if (!foregroundInitialized) {
            startMediaService();
        }

        if (service == null) {
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
            service.setCurrentMediaId(null);
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
        
        long durationMs = -1;
        try {
            if (call.hasOption("duration")) {
                Object durationObj = call.getData().get("duration");
                if (durationObj instanceof Number) {
                    durationMs = (long) (((Number) durationObj).doubleValue() * 1000);
                } else if (durationObj instanceof String) {
                    try {
                        durationMs = (long) (Double.parseDouble((String) durationObj) * 1000);
                    } catch (NumberFormatException e) {
                        Log.e("streamer", "Failed to parse duration string: " + durationObj);
                    }
                }
            }
        } catch (Exception ignored) {}

        if (service != null) {
            service.setTitle(title);
            service.setArtist(artist);
            service.setAlbum(album);
            service.setArtwork(getImage(artwork));
            if (durationMs > 0) service.setDuration(durationMs);
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

    @PluginMethod
    public void getCurrentState(PluginCall call) {
        new Handler(Looper.getMainLooper()).post(() -> {
            JSObject state = new JSObject();
            if (service != null) {
                state.put("isPlaying", service.isCurrentlyPlaying());
                state.put("currentUrl", service.getCurrentUrl());
                state.put("currentTime", service.getCurrentPosition() / 1000.0);
                long dur = service.getDuration();
                state.put("duration", dur > 0 ? dur / 1000.0 : 0);
                state.put("isLiveStream", service.isLiveStream());
                state.put("currentMediaId", service.getCurrentMediaId());
                String mediaId = service.getCurrentMediaId();
                if (mediaId != null) {
                    String[] meta = service.getMetadataForMediaId(mediaId);
                    if (meta != null) {
                        state.put("title", meta[0]);
                        state.put("artist", meta[1]);
                        state.put("imageUrl", meta[2]);
                        if (meta.length > 3) {
                            try { state.put("duration", Integer.parseInt(meta[3])); } catch (NumberFormatException ignored) {}
                        }
                    }
                    String streamUrl = service.getStreamUrlForMediaId(mediaId);
                    if (streamUrl != null) {
                        state.put("streamUrl", streamUrl);
                    }
                }
            } else {
                state.put("isPlaying", false);
                state.put("currentUrl", null);
                state.put("currentTime", 0);
                state.put("duration", 0);
                state.put("isLiveStream", false);
                state.put("currentMediaId", null);
            }
            call.resolve(state);
        });
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