package co.broadcastapp.muckabout;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.media.session.MediaSessionCompat;
import android.view.KeyEvent;

/**
 * Transport commands from the notification, lock screen, headsets, Android Auto and
 * Google Assistant. Handled natively by the service, so they work without the WebView.
 */
public class MediaSessionCallback extends MediaSessionCompat.Callback {
    private static final long SEEK_INCREMENT_MS = 10000; // 10 seconds

    // Seeking is offered as custom actions: media controllers (Android Auto, the Android 13+
    // media controls) draw ACTION_SKIP_TO_PREVIOUS/NEXT as track-skip buttons
    static final String ACTION_SEEK_BACKWARD = "co.broadcastapp.muckabout.SEEK_BACKWARD";
    static final String ACTION_SEEK_FORWARD = "co.broadcastapp.muckabout.SEEK_FORWARD";

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
    public void onCustomAction(String action, Bundle extras) {
        if (ACTION_SEEK_BACKWARD.equals(action)) {
            onSkipToPrevious();
        } else if (ACTION_SEEK_FORWARD.equals(action)) {
            onSkipToNext();
        }
    }

    @Override
    public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
        // Skip-to-next/previous aren't advertised (see ACTION_SEEK_*), so the default handling
        // would drop these keys; keep headset and steering-wheel buttons seeking
        KeyEvent event = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    onSkipToNext();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    onSkipToPrevious();
                    return true;
            }
        }
        return super.onMediaButtonEvent(mediaButtonEvent);
    }

    @Override
    public void onStop() {
        service.stop();
    }
}
