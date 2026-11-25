package co.broadcastapp.muckabout.library;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Something the player can play, plus the metadata shown in the notification, on the
 * lock screen and in Android Auto. Mirrors the iOS {@code PlayableItem}.
 */
public final class PlayableItem {

    /** Stable identifier for the content (reported to JS as {@code mediaId}). */
    @Nullable
    public final String mediaId;

    /** The URL the player actually requests. */
    @NonNull
    public final String url;

    /**
     * The URL reported back to JS. Use this when {@link #url} carries extra per-request
     * parameters the app adds on its own. Null means "same as url".
     */
    @Nullable
    public final String canonicalUrl;

    @NonNull
    public final String title;

    @NonNull
    public final String artist;

    @NonNull
    public final String album;

    @Nullable
    public final String artworkUrl;

    public final boolean isLive;

    /** Known duration in seconds, or 0 when unknown (live streams). */
    public final double durationSeconds;

    public PlayableItem(
        @Nullable String mediaId,
        @NonNull String url,
        @Nullable String canonicalUrl,
        @NonNull String title,
        @NonNull String artist,
        @NonNull String album,
        @Nullable String artworkUrl,
        boolean isLive,
        double durationSeconds
    ) {
        this.mediaId = mediaId;
        this.url = url;
        this.canonicalUrl = canonicalUrl;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.artworkUrl = artworkUrl;
        this.isLive = isLive;
        this.durationSeconds = durationSeconds;
    }

    /** An item known only by URL (playback requested from JS). */
    @NonNull
    public static PlayableItem fromUrl(@NonNull String url, boolean isLive) {
        return new PlayableItem(null, url, null, "", "", "", null, isLive, 0);
    }

    /** The URL reported to JS ({@code streamUrl}). */
    @NonNull
    public String getReportedUrl() {
        return canonicalUrl != null ? canonicalUrl : url;
    }

    /** Same item with new display metadata (JS {@code setNowPlayingInfo}). */
    @NonNull
    public PlayableItem withMetadata(@NonNull String title, @NonNull String artist, @NonNull String album, @Nullable String artworkUrl, double durationSeconds) {
        return new PlayableItem(mediaId, url, canonicalUrl, title, artist, album, artworkUrl, isLive, durationSeconds);
    }
}
