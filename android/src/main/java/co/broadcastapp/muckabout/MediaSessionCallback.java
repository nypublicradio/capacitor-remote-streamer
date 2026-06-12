package co.broadcastapp.muckabout;

import android.support.v4.media.session.MediaSessionCompat;

import com.getcapacitor.JSObject;

public class MediaSessionCallback extends MediaSessionCompat.Callback {
    private static final String TAG = "MediaSessionCallback";
    private static final long SEEK_INCREMENT_MS = 10000; // 10 seconds

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
        // Native seek for Android Auto
        if (service != null) service.seekBy(-SEEK_INCREMENT_MS);
    }

    @Override
    public void onFastForward() {
        if (plugin != null) plugin.actionCallback("seekforward");
        // Native seek for Android Auto
        if (service != null) service.seekBy(SEEK_INCREMENT_MS);
    }

    @Override
    public void onSkipToPrevious() {
        if (service != null && !service.isLiveStream()) {
            // On-demand: seek backward 10 seconds
            service.seekBy(-SEEK_INCREMENT_MS);
        }
        if (plugin != null) plugin.actionCallback("previoustrack");
    }

    @Override
    public void onSkipToNext() {
        if (service != null && !service.isLiveStream()) {
            // On-demand: seek forward 10 seconds
            service.seekBy(SEEK_INCREMENT_MS);
        }
        if (plugin != null) plugin.actionCallback("nexttrack");
    }

    @Override
    public void onStop() {
        if (plugin != null) plugin.actionCallback("stop");
        if (service != null) service.stop();
    }
}