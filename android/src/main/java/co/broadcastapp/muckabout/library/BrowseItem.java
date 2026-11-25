package co.broadcastapp.muckabout.library;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** One row in a media browser (Android Auto list or grid). */
public final class BrowseItem {

    /** How a browser should lay out an item's children. */
    public enum DisplayStyle {
        LIST,
        GRID
    }

    @NonNull
    public final String id;

    @NonNull
    public final String title;

    @Nullable
    public final String subtitle;

    @Nullable
    public final String artworkUri;

    public final boolean browsable;
    public final boolean playable;

    @NonNull
    public final DisplayStyle displayStyle;

    /**
     * Extra description extras for media browsers, added last so they win, e.g. Android Auto's
     * {@code android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT} for a section heading.
     */
    @Nullable
    public final Bundle extras;

    public BrowseItem(
        @NonNull String id,
        @NonNull String title,
        @Nullable String subtitle,
        @Nullable String artworkUri,
        boolean browsable,
        boolean playable,
        @NonNull DisplayStyle displayStyle
    ) {
        this(id, title, subtitle, artworkUri, browsable, playable, displayStyle, null);
    }

    public BrowseItem(
        @NonNull String id,
        @NonNull String title,
        @Nullable String subtitle,
        @Nullable String artworkUri,
        boolean browsable,
        boolean playable,
        @NonNull DisplayStyle displayStyle,
        @Nullable Bundle extras
    ) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.artworkUri = artworkUri;
        this.browsable = browsable;
        this.playable = playable;
        this.displayStyle = displayStyle;
        this.extras = extras;
    }

    /** A folder the user can open. */
    @NonNull
    public static BrowseItem browsable(@NonNull String id, @NonNull String title, @Nullable String subtitle, @Nullable String artworkUri) {
        return new BrowseItem(id, title, subtitle, artworkUri, true, false, DisplayStyle.LIST);
    }

    /** Something the user can play. */
    @NonNull
    public static BrowseItem playable(@NonNull String id, @NonNull String title, @Nullable String subtitle, @Nullable String artworkUri) {
        return new BrowseItem(id, title, subtitle, artworkUri, false, true, DisplayStyle.LIST);
    }

    /** Same item, with its children shown in the given style. */
    @NonNull
    public BrowseItem withDisplayStyle(@NonNull DisplayStyle style) {
        return new BrowseItem(id, title, subtitle, artworkUri, browsable, playable, style, extras);
    }

    /** Same item, with extra description extras (see {@link #extras}). */
    @NonNull
    public BrowseItem withExtras(@Nullable Bundle extras) {
        return new BrowseItem(id, title, subtitle, artworkUri, browsable, playable, displayStyle, extras);
    }
}
