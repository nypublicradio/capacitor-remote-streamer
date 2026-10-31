package co.broadcastapp.muckabout;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import org.json.JSONException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import co.broadcastapp.muckabout.library.PlayableItem;

@CapacitorPlugin(name = "RemoteStreamer")
public class RemoteStreamerPlugin extends Plugin {
    private RemoteStreamerService service = null;
    private boolean foregroundInitialized = false;
    /** The app's activity is stopped: in the background, or the screen is off. */
    private boolean activityStopped = false;

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
            if (service.play(url)) {
                call.resolve();
            } else {
                call.reject("Nothing to play at " + url);
            }
        } else {
            call.reject("Service failed to start");
        }
    }

    @PluginMethod
    public void pause(PluginCall call) {
        if (service != null) {
            service.pause();
        }
        call.resolve();
    }

    @PluginMethod
    public void resume(PluginCall call) {
        if (service != null) {
            service.resume();
        }
        call.resolve();
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
        if (service != null) {
            service.seekTo(position);
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (service != null) {
            service.stop(false);
        }
        call.resolve();
    }

    @PluginMethod
    public void setNowPlayingInfo(PluginCall call) {
        String title = call.getString("title", "");
        String artist = call.getString("artist", "");
        String album = call.getString("album", "");
        String imageUrl = call.getString("imageUrl", "");

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

        if (service == null) {
            call.reject("Service is not initialized");
            return;
        }

        service.setNowPlayingMetadata(
            title != null ? title : "",
            artist != null ? artist : "",
            album != null ? album : "",
            imageUrl == null || imageUrl.isEmpty() ? null : imageUrl,
            imageUrl == null || imageUrl.isEmpty() ? null : getImage(imageUrl),
            durationMs
        );
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
        if (service != null) {
            service.releasePlayer();
        }
        call.resolve();
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
                PlayableItem item = service.getCurrentItem();
                state.put("currentMediaId", item != null ? item.mediaId : null);
                // Metadata of the current item, so the app can rebuild its UI when it
                // opens after playback was started natively (e.g. from Android Auto).
                if (item != null) {
                    if (dur <= 0 && item.durationSeconds > 0) state.put("duration", item.durationSeconds);
                    if (!item.title.isEmpty()) state.put("title", item.title);
                    if (!item.artist.isEmpty()) state.put("artist", item.artist);
                    if (item.artworkUrl != null) state.put("imageUrl", item.artworkUrl);
                    state.put("streamUrl", item.getReportedUrl());
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

    @Override
    protected void handleOnStart() {
        super.handleOnStart();
        activityStopped = false;
    }

    @Override
    protected void handleOnStop() {
        super.handleOnStop();
        activityStopped = true;
        // The WebView hides itself as the activity stops: wait for that, then undo it
        bridge.getWebView().postDelayed(this::keepWebViewRunning, 1000);
    }

    /** The playback service entered or left the foreground. Main thread. */
    void onForegroundChanged(boolean foreground) {
        if (!activityStopped) return;
        if (foreground) {
            keepWebViewRunning();
        } else {
            // Nothing plays any more: the WebView may sleep like any hidden one
            bridge.getWebView().dispatchWindowVisibilityChanged(View.GONE);
        }
    }

    /**
     * Android stops the JavaScript of a WebView that isn't visible (screen off, app in the
     * background), even while the playback service keeps the app running. The app then can't
     * react to the player, e.g. start the next item when one ends. While playback keeps the
     * service in the foreground, tell the WebView it is still visible so its JS keeps running.
     */
    private void keepWebViewRunning() {
        if (activityStopped && service != null && service.isInForeground()) {
            bridge.getWebView().dispatchWindowVisibilityChanged(View.VISIBLE);
        }
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        Double volume = call.getDouble("volume");
        if (volume == null) {
            call.reject("volume is required");
            return;
        }
        if (service != null) {
            service.setVolume(volume.floatValue());
        }
        call.resolve();
    }

    public void onPlayerEvent(String event, JSObject data) {
        notifyListeners(event, data);
    }
}
