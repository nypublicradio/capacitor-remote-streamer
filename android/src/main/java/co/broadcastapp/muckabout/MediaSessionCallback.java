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
        String streamUrl = null;
        if (service != null) {
            streamUrl = service.getStreamUrlForMediaId(mediaId);
            if (streamUrl != null) {
                service.setCurrentMediaId(mediaId);
                service.updateMetadataForMediaId(mediaId);
                service.play(streamUrl);
            }
        }
        // Notify JS layer with full metadata so the app UI can load the content
        if (plugin != null) {
            boolean isLive = mediaId.startsWith("live_");
            JSObject data = new JSObject();
            data.put("mediaId", mediaId);
            data.put("isLive", isLive);
            if (streamUrl != null) data.put("streamUrl", streamUrl);
            if (service != null) {
                String[] meta = service.getMetadataForMediaId(mediaId);
                if (meta != null) {
                    data.put("title", meta[0]);
                    data.put("artist", meta[1]);
                    data.put("imageUrl", meta[2]);
                    if (meta.length > 3) {
                        try { data.put("duration", Integer.parseInt(meta[3])); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            plugin.onPlayerEvent("playFromMediaId", data);
        }
    }

    @Override
    public void onPlay() {
        // Use a single path to avoid duplicate service calls.
        // actionCallback("play") already calls service.resume() internally.
        if (plugin != null) {
            plugin.actionCallback("play");
        } else if (service != null) {
            service.resume();
        }
    }

    @Override
    public void onPause() {
        if (plugin != null) {
            plugin.actionCallback("pause");
        } else if (service != null) {
            service.pause();
        }
    }

    @Override
    public void onSeekTo(long pos) {
        if (plugin != null) {
            JSObject data = new JSObject();
            data.put("seekTime", pos);
            plugin.actionCallback("seekto", data);
        } else if (service != null) {
            service.seekTo(pos);
        }
    }

    @Override
    public void onRewind() {
        if (service != null) service.seekBy(-SEEK_INCREMENT_MS);
    }

    @Override
    public void onFastForward() {
        if (service != null) service.seekBy(SEEK_INCREMENT_MS);
    }

    @Override
    public void onSkipToPrevious() {
        if (service != null && !service.isLiveStream()) {
            service.seekBy(-SEEK_INCREMENT_MS);
        }
    }

    @Override
    public void onSkipToNext() {
        if (service != null && !service.isLiveStream()) {
            service.seekBy(SEEK_INCREMENT_MS);
        }
    }

    @Override
    public void onStop() {
        if (service != null) service.stop();
    }
}