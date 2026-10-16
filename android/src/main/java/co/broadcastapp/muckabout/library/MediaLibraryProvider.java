package co.broadcastapp.muckabout.library;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * Supplies browsable content (for Android Auto, Google Assistant and other media
 * browsers) to the player's media session.
 *
 * <p>An app opts in by naming its implementation in application-level manifest meta-data:
 *
 * <pre>{@code
 * <meta-data
 *     android:name="co.broadcastapp.muckabout.MEDIA_LIBRARY_PROVIDER"
 *     android:value="com.example.MyMediaLibraryProvider" />
 * }</pre>
 *
 * The player service creates the provider itself (public no-arg constructor), so this
 * works when a car connects with the app closed. Without a provider, media browsers
 * are refused.
 *
 * <p>Only plain data types cross this interface so it stays stable when the player's
 * internals change.
 */
public interface MediaLibraryProvider {
    /** Called once when the player service is created (main thread). Keep it cheap. */
    void onCreate(@NonNull Context context);

    /**
     * Root id for a connecting media browser, or null to refuse it (main thread).
     *
     * @param clientPackageName package of the connecting app (e.g. Android Auto)
     * @param recent true when the system asks for recently played content (media resumption)
     */
    @Nullable
    String getRootId(@NonNull String clientPackageName, boolean recent);

    /** Children of a browsable item. Called on a background thread; may block on network. */
    @NonNull
    List<BrowseItem> getChildren(@NonNull String parentId) throws Exception;

    /** Resolves a playable media id. Called on a background thread; may block on network. */
    @Nullable
    PlayableItem resolve(@NonNull String mediaId) throws Exception;

    /** Whether {@link #search} and {@link #resolveSearch} are implemented. */
    default boolean supportsSearch() {
        return false;
    }

    /** Search results to browse. Background thread. */
    @NonNull
    default List<BrowseItem> search(@NonNull String query) throws Exception {
        return Collections.emptyList();
    }

    /**
     * Voice request ("play X"). An empty query means "play something sensible".
     * Background thread.
     */
    @Nullable
    default PlayableItem resolveSearch(@Nullable String query) throws Exception {
        return null;
    }

    /** What to play when the user presses play with nothing loaded. Background thread. */
    @Nullable
    default PlayableItem getDefaultItem() throws Exception {
        return null;
    }

    /** Called when the player service is destroyed. */
    default void onDestroy() {}
}
