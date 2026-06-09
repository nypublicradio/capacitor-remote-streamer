package co.broadcastapp.muckabout;

import android.support.v4.media.session.MediaSessionCompat;

import com.getcapacitor.JSObject;

public class MediaSessionCallback extends MediaSessionCompat.Callback {
    private static final String TAG = "MediaSessionCallback";

    private final RemoteStreamerPlugin plugin;
    private final RemoteStreamerService service;

    MediaSessionCallback(RemoteStreamerPlugin plugin, RemoteStreamerService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public void onPlayFromMediaId(String mediaId, android.os.Bundle extras) {
        // Look up the stream URL from stored media items and start playback
        if (service != null) {
            String streamUrl = service.getStreamUrlForMediaId(mediaId);
            if (streamUrl != null) {
                service.play(streamUrl);
            }
        }
        // Also notify JS layer
        JSObject data = new JSObject();
        data.put("mediaId", mediaId);
        plugin.onPlayerEvent("playFromMediaId", data);
    }

    @Override
    public void onPlay() {
        plugin.actionCallback("play");
    }

    @Override
    public void onPause() {
        plugin.actionCallback("pause");
    }

    @Override
    public void onSeekTo(long pos) {
        JSObject data = new JSObject();
        data.put("seekTime", pos);
        plugin.actionCallback("seekto", data);
    }

    @Override
    public void onRewind() {
        plugin.actionCallback("seekbackward");
    }

    @Override
    public void onFastForward() {
        plugin.actionCallback("seekforward");
    }

    @Override
    public void onSkipToPrevious() {
        plugin.actionCallback("previoustrack");
    }

    @Override
    public void onSkipToNext() {
        plugin.actionCallback("nexttrack");
    }

    @Override
    public void onStop() {
        plugin.actionCallback("stop");
    }
}