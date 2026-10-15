package co.broadcastapp.muckabout;

import android.os.Bundle;
import android.support.v4.media.session.MediaSessionCompat;

/**
 * Transport commands from the notification, lock screen, headsets, Android Auto and
 * Google Assistant. Handled natively by the service, so they work without the WebView.
 */
public class MediaSessionCallback extends MediaSessionCompat.Callback {
    private static final long SEEK_INCREMENT_MS = 10000; // 10 seconds

    private final RemoteStreamerService service;

    MediaSessionCallback(RemoteStreamerService service) {
        this.service = service;
    }

    @Override
    public void onPlayFromMediaId(String mediaId, Bundle extras) {
        if (mediaId != null) service.playFromMediaId(mediaId);
    }

    @Override
    public void onPlayFromSearch(String query, Bundle extras) {
        service.playFromSearch(query);
    }

    @Override
    public void onPlay() {
        service.playOrResume();
    }

    @Override
    public void onPause() {
        service.pause();
    }

    @Override
    public void onSeekTo(long pos) {
        service.seekTo(pos);
    }

    @Override
    public void onRewind() {
        service.seekBy(-SEEK_INCREMENT_MS);
    }

    @Override
    public void onFastForward() {
        service.seekBy(SEEK_INCREMENT_MS);
    }

    @Override
    public void onSkipToPrevious() {
        if (!service.isLiveStream()) {
            service.seekBy(-SEEK_INCREMENT_MS);
        }
    }

    @Override
    public void onSkipToNext() {
        if (!service.isLiveStream()) {
            service.seekBy(SEEK_INCREMENT_MS);
        }
    }

    @Override
    public void onStop() {
        service.stop();
    }
}
