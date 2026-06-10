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
        if (service != null) {
            String streamUrl = service.getStreamUrlForMediaId(mediaId);
            if (streamUrl != null) {
                service.updateMetadataForMediaId(mediaId);
                service.play(streamUrl);
            }
        }
        // Also notify JS layer if plugin is connected
        if (plugin != null) {
            JSObject data = new JSObject();
            data.put("mediaId", mediaId);
            plugin.onPlayerEvent("playFromMediaId", data);
        }
    }

    @Override
    public void onPlay() {
        if (plugin != null) plugin.actionCallback("play");
        if (service != null) service.resume();
    }

    @Override
    public void onPause() {
        if (plugin != null) plugin.actionCallback("pause");
        if (service != null) service.pause();
    }

    @Override
    public void onSeekTo(long pos) {
        if (plugin != null) {
            JSObject data = new JSObject();
            data.put("seekTime", pos);
            plugin.actionCallback("seekto", data);
        }
        if (service != null) service.seekTo(pos);
    }

    @Override
    public void onRewind() {
        if (plugin != null) plugin.actionCallback("seekbackward");
    }

    @Override
    public void onFastForward() {
        if (plugin != null) plugin.actionCallback("seekforward");
    }

    @Override
    public void onSkipToPrevious() {
        if (plugin != null) plugin.actionCallback("previoustrack");
    }

    @Override
    public void onSkipToNext() {
        if (plugin != null) plugin.actionCallback("nexttrack");
    }

    @Override
    public void onStop() {
        if (plugin != null) plugin.actionCallback("stop");
        if (service != null) service.stop();
    }
}