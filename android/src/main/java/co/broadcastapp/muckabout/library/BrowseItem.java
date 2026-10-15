package co.broadcastapp.muckabout.library;

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

    public BrowseItem(
        @NonNull String id,
        @NonNull String title,
        @Nullable String subtitle,
        @Nullable String artworkUri,
        boolean browsable,
        boolean playable,
        @NonNull DisplayStyle displayStyle
    ) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.artworkUri = artworkUri;
        this.browsable = browsable;
        this.playable = playable;
        this.displayStyle = displayStyle;
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
        return new BrowseItem(id, title, subtitle, artworkUri, browsable, playable, style);
    }
}
