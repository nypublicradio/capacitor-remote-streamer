package co.broadcastapp.muckabout;


import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Binder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.session.MediaButtonReceiver;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media3.common.util.UnstableApi;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import android.media.AudioFocusRequest;
import androidx.media3.common.AudioAttributes;
import android.media.AudioManager;
import com.getcapacitor.JSObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.IOException;
import java.io.InputStream;
import android.graphics.BitmapFactory;

import androidx.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import co.broadcastapp.muckabout.library.BrowseItem;
import co.broadcastapp.muckabout.library.MediaLibraryProvider;
import co.broadcastapp.muckabout.library.PlayableItem;
import co.broadcastapp.muckabout.library.ProviderLoader;

    // Media3 marks the explicit media source factories (HLS vs progressive) as
    // unstable API; opting in keeps the same playback setup as ExoPlayer 2.
    @OptIn(markerClass = UnstableApi.class)
    public class RemoteStreamerService extends MediaBrowserServiceCompat implements AudioManager.OnAudioFocusChangeListener {
        private static final String TAG = "RemoteStreamerService";

        private static final String EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
        private static final String EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
        private static final String EXTRA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED";
        private static final int CONTENT_STYLE_LIST_ITEM = 1;
        private static final int CONTENT_STYLE_GRID_ITEM = 2;

        private static final String NOTIFICATION_CHANNEL_ID = "playback";

        /** Transport actions offered in the notification, by content type. */
        private static final Set<String> LIVE_ACTIONS = new HashSet<>(Arrays.asList("play", "pause"));
        private static final Set<String> ON_DEMAND_ACTIONS = new HashSet<>(Arrays.asList("play", "pause", "seekbackward", "seekforward", "seekto"));

        private MediaSessionCompat mediaSession;
        private PlaybackStateCompat.Builder playbackStateBuilder;
        private MediaMetadataCompat.Builder mediaMetadataBuilder;
        private NotificationManager notificationManager;
        private NotificationCompat.Builder notificationBuilder;
        private MediaStyle notificationStyle;
        private final Map<String, NotificationCompat.Action> notificationActions = new HashMap<>();
        private final Map<String, Long> playbackStateActions = new HashMap<>();
        private final String[] possibleActions = {"previoustrack", "seekbackward", "play", "pause", "seekforward", "nexttrack", "seekto", "stop"};
        final Set<String> possibleCompactViewActions = new HashSet<>(Arrays.asList("seekbackward", "play", "pause", "seekforward", "stop", "seekto"));
        private static final int NOTIFICATION_ID = 1;

        private int playbackState = PlaybackStateCompat.STATE_NONE;
        private String title = "";
        private String artist = "";
        private String album = "";
        private Bitmap artwork = null;
        private long duration = 0;
        private long position = 0;
        private float playbackSpeed = 1.0F;

        private boolean possibleActionsUpdate = true;
        private boolean playbackStateUpdate = false;
        private boolean mediaMetadataUpdate = false;
        private boolean notificationUpdate = false;

        private ExoPlayer player;
        private DefaultDataSource.Factory dataSourceFactory;
        private AudioManager audioManager;
        private AudioFocusRequest focusRequest;
        private Handler handler;
        private Runnable updateTimeTask;
        private boolean isLiveStream = false;
        private boolean resumeOnFocusLossTransient = false;

        // Reconnection state
        private String currentUrl;
        private int reconnectAttempts = 0;
        private static final int MAX_RECONNECT_ATTEMPTS = 3;
        private static final long STALL_TIMEOUT_MS = 30000; // 30 seconds
        private Runnable reconnectRunnable;
        private Runnable stallWatchdog;
        private boolean isReconnecting = false;
        private boolean wasPlayingBeforeStall = false;
        private long savedPosition = 0; // saved playback position for on-demand recovery
        private ConnectivityManager connectivityManager;
        private AudioAttributes playerAudioAttributes;
        private ConnectivityManager.NetworkCallback networkCallback;

        /** How long the service stays in the foreground after an item ends, so the app can start the next one. */
        private static final long END_OF_ITEM_FOREGROUND_MS = 30000;
        private boolean isForeground = false;
        private final Runnable leaveForegroundAfterEnd = this::leaveForeground;

        /** Assets the host app bundles with its web code; {@code bundle:<path>} URLs name a file in here. */
        private static final String BUNDLE_SCHEME = "bundle:";
        private static final String BUNDLED_WEB_ASSETS = "public/";

        /** What is currently loaded, with its display metadata. */
        private volatile PlayableItem currentItem;

        /** Host-controlled branding (capacitor.config → plugins.RemoteStreamer). */
        private PlayerSettings settings;

        /** Supplies browsable content to Android Auto etc.; null when the app has none. */
        private MediaLibraryProvider libraryProvider;

        /** Background work: browse requests, item resolution, artwork downloads. */
        private ExecutorService executor;

        private RemoteStreamerPlugin plugin;

        private final IBinder binder = new LocalBinder();

        public final class LocalBinder extends Binder {
            public RemoteStreamerService getService() {
                return RemoteStreamerService.this;
            }
        }

        // --- Media browser (Android Auto, Assistant, ...) ---

        @Override
        public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
            MediaLibraryProvider provider = libraryProvider;
            if (provider == null) {
                return null;
            }
            boolean recent = rootHints != null && rootHints.getBoolean(BrowserRoot.EXTRA_RECENT);
            String rootId;
            try {
                rootId = provider.getRootId(clientPackageName, recent);
            } catch (Exception e) {
                Log.w(TAG, "Media library provider failed in getRootId", e);
                return null;
            }
            if (rootId == null) {
                return null;
            }
            Bundle extras = new Bundle();
            extras.putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM);
            extras.putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM);
            if (provider.supportsSearch()) {
                extras.putBoolean(EXTRA_SEARCH_SUPPORTED, true);
            }
            return new BrowserRoot(rootId, extras);
        }

        @Override
        public void onLoadChildren(@NonNull final String parentMediaId, @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
            final MediaLibraryProvider provider = libraryProvider;
            if (provider == null) {
                result.sendResult(new ArrayList<>());
                return;
            }
            // Detach so we can load asynchronously
            result.detach();
            executor().execute(() -> {
                List<BrowseItem> children = new ArrayList<>();
                try {
                    children = provider.getChildren(parentMediaId);
                } catch (Exception e) {
                    Log.w(TAG, "Media library provider failed to load " + parentMediaId, e);
                }
                result.sendResult(toMediaItems(children));
            });
        }

        @Override
        public void onSearch(@NonNull final String query, Bundle extras, @NonNull final Result<List<MediaBrowserCompat.MediaItem>> result) {
            final MediaLibraryProvider provider = libraryProvider;
            if (provider == null || !provider.supportsSearch()) {
                result.sendResult(null);
                return;
            }
            result.detach();
            executor().execute(() -> {
                List<BrowseItem> matches = new ArrayList<>();
                try {
                    matches = provider.search(query);
                } catch (Exception e) {
                    Log.w(TAG, "Media library provider failed to search for " + query, e);
                }
                result.sendResult(toMediaItems(matches));
            });
        }

        private List<MediaBrowserCompat.MediaItem> toMediaItems(List<BrowseItem> items) {
            List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
            for (BrowseItem item : items) {
                int style = item.displayStyle == BrowseItem.DisplayStyle.GRID ? CONTENT_STYLE_GRID_ITEM : CONTENT_STYLE_LIST_ITEM;
                Bundle extras = new Bundle();
                extras.putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, style);
                extras.putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, style);
                if (item.extras != null) {
                    extras.putAll(item.extras);
                }
                MediaDescriptionCompat.Builder description = new MediaDescriptionCompat.Builder()
                        .setMediaId(item.id)
                        .setTitle(item.title)
                        .setSubtitle(item.subtitle)
                        .setExtras(extras);
                if (item.artworkUri != null && !item.artworkUri.trim().isEmpty()) {
                    description.setIconUri(Uri.parse(item.artworkUri));
                }
                int flags = (item.browsable ? MediaBrowserCompat.MediaItem.FLAG_BROWSABLE : 0)
                        | (item.playable ? MediaBrowserCompat.MediaItem.FLAG_PLAYABLE : 0);
                mediaItems.add(new MediaBrowserCompat.MediaItem(description.build(), flags));
            }
            return mediaItems;
        }

        // --- Playback started natively (media session commands) ---

        /** A media browser asked to play a browse item (e.g. a tap in Android Auto). */
        void playFromMediaId(@NonNull final String mediaId) {
            final MediaLibraryProvider provider = libraryProvider;
            if (provider == null) return;
            resolveAndPlay(() -> provider.resolve(mediaId), "media id " + mediaId);
        }

        /** Voice request, e.g. "Hey Google, play ...". An empty query means "play something". */
        void playFromSearch(@Nullable final String query) {
            final MediaLibraryProvider provider = libraryProvider;
            if (provider == null) return;
            resolveAndPlay(() -> provider.resolveSearch(query), "search '" + query + "'");
        }

        /** Play pressed (media session, headset, car). Restarts or picks a default when nothing is loaded. */
        void playOrResume() {
            if (player != null) {
                resume();
                return;
            }
            PlayableItem item = currentItem;
            if (item != null) {
                handler.post(() -> playItem(item));
                return;
            }
            final MediaLibraryProvider provider = libraryProvider;
            if (provider != null) {
                resolveAndPlay(provider::getDefaultItem, "default item");
            }
        }

        private void resolveAndPlay(@NonNull final Callable<PlayableItem> resolver, @NonNull final String description) {
            executor().execute(() -> {
                PlayableItem item = null;
                try {
                    item = resolver.call();
                } catch (Exception e) {
                    Log.w(TAG, "Could not resolve " + description, e);
                }
                if (item == null) {
                    Log.w(TAG, "Nothing to play for " + description);
                    return;
                }
                final PlayableItem resolved = item;
                handler.post(() -> playItem(resolved));
            });
        }

        /**
         * Plays an item started from native code (Android Auto, Assistant, a media button).
         * Shows its metadata, keeps the service in the foreground like app-started playback
         * and tells JS through the {@code playFromMediaId} event. Call on the main thread.
         */
        public void playItem(@NonNull PlayableItem item) {
            currentItem = item;
            ensureForeground();
            setTitle(item.title);
            setArtist(item.artist);
            setAlbum(item.album);
            setArtwork(null);
            setDuration(item.durationSeconds > 0 ? (long) (item.durationSeconds * 1000) : 0);
            update();
            loadArtwork(item);
            playUrl(item.url, item.isLive);
            if (plugin != null && item.mediaId != null) {
                plugin.onPlayerEvent("playFromMediaId", playFromMediaIdPayload(item));
            }
        }

        private JSObject playFromMediaIdPayload(PlayableItem item) {
            JSObject data = new JSObject();
            data.put("mediaId", item.mediaId);
            data.put("id", item.mediaId);
            data.put("isLive", item.isLive);
            data.put("streamUrl", item.getReportedUrl());
            data.put("title", item.title);
            data.put("artist", item.artist);
            if (item.artworkUrl != null) data.put("imageUrl", item.artworkUrl);
            if (item.durationSeconds > 0) data.put("duration", item.durationSeconds);
            return data;
        }

        private void loadArtwork(final PlayableItem item) {
            final String imageUrl = item.artworkUrl;
            if (imageUrl == null || imageUrl.trim().isEmpty()) return;
            executor().execute(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(imageUrl).openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    InputStream is = conn.getInputStream();
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    is.close();
                    conn.disconnect();
                    if (bmp != null) {
                        handler.post(() -> {
                            // Ignore artwork that arrives after a different item started
                            if (currentItem == item) {
                                setArtwork(bmp);
                                update();
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load artwork " + imageUrl, e);
                }
            });
        }

        /** Updates the current item's display metadata (JS {@code setNowPlayingInfo}). */
        public void setNowPlayingMetadata(@NonNull String title, @NonNull String artist, @NonNull String album,
                                          @Nullable String artworkUrl, @Nullable Bitmap artwork, long durationMs) {
            setTitle(title);
            setArtist(artist);
            setAlbum(album);
            setArtwork(artwork);
            if (durationMs > 0) setDuration(durationMs);
            update();

            PlayableItem base = currentItem != null
                    ? currentItem
                    : PlayableItem.fromUrl(currentUrl != null ? currentUrl : "", isLiveStream);
            currentItem = base.withMetadata(title, artist, album, artworkUrl,
                    durationMs > 0 ? durationMs / 1000.0 : base.durationSeconds);
        }

        @Nullable
        public PlayableItem getCurrentItem() {
            return currentItem;
        }

        public String getCurrentUrl() {
            return currentUrl;
        }

        public boolean isCurrentlyPlaying() {
            return playbackState == PlaybackStateCompat.STATE_PLAYING;
        }

        public long getDuration() {
            if (player != null) {
                long d = player.getDuration();
                if (d != C.TIME_UNSET && d > 0) {
                    return d;
                }
            }
            return duration;
        }

        private synchronized ExecutorService executor() {
            // Recreated if destroy() shut it down while a media browser is still bound
            if (executor == null || executor.isShutdown()) {
                executor = Executors.newFixedThreadPool(3);
            }
            return executor;
        }

        @Override
        public void onCreate() {
            super.onCreate();
            handler = new Handler(Looper.getMainLooper());
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            settings = PlayerSettings.load(this);
            setupNetworkCallback();

            // Initialize MediaSession immediately so media browsers (Android Auto) can
            // connect without waiting for the WebView plugin to bind.
            mediaSession = new MediaSessionCompat(this, "RemoteStreamer");
            mediaSession.setCallback(new MediaSessionCallback(this));
            mediaSession.setActive(true);
            setSessionToken(mediaSession.getSessionToken());

            libraryProvider = ProviderLoader.load(this);

            playbackStateBuilder = new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                            | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO
                            | PlaybackStateCompat.ACTION_STOP
                            | PlaybackStateCompat.ACTION_FAST_FORWARD | PlaybackStateCompat.ACTION_REWIND
                            | browseActions())
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f);
            mediaSession.setPlaybackState(playbackStateBuilder.build());

            mediaMetadataBuilder = new MediaMetadataCompat.Builder()
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0);
            mediaSession.setMetadata(mediaMetadataBuilder.build());

            DefaultHttpDataSource.Factory httpDataSourceFactory =
                new DefaultHttpDataSource.Factory().setUserAgent(settings.userAgent);
            dataSourceFactory = new DefaultDataSource.Factory(this, httpDataSourceFactory);

            this.playerAudioAttributes = new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build();

            // Create system audio attributes for audio focus request
            android.media.AudioAttributes systemAudioAttributes = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(systemAudioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build();
        }

        /** Actions that let media browsers start playback; always advertised when a provider exists. */
        private long browseActions() {
            MediaLibraryProvider provider = libraryProvider;
            if (provider == null) return 0;
            long actions = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID;
            if (provider.supportsSearch()) {
                actions |= PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH;
            }
            return actions;
        }

        @Override
        public IBinder onBind(Intent intent) {
            // If the intent has the MediaBrowserService action, let the superclass handle it
            // so Android Auto can connect via the media browser protocol.
            if (intent != null && "android.media.browse.MediaBrowserService".equals(intent.getAction())) {
                return super.onBind(intent);
            }
            return binder;
        }

        @Override
        public boolean onUnbind(Intent intent) {
            if (intent == null || !"android.media.browse.MediaBrowserService".equals(intent.getAction())) {
                // Local binder (app activity) disconnected.
                // Clear the plugin reference to avoid sending events to a dead WebView.
                // (A media browser disconnecting leaves the plugin alone: the app is still alive.)
                this.plugin = null;
            }
            return super.onUnbind(intent);
        }

        /**
         * Lightweight connection: sets the plugin reference so native events reach JS.
         * Called on plugin load() before any playback occurs.
         */
        public void connectPlugin(RemoteStreamerPlugin plugin) {
            this.plugin = plugin;
        }

        /**
         * Full initialization: sets up the foreground notification for active playback.
         * Called when startForegroundService has been invoked (first play() call).
         */
        public void connectAndInitialize(RemoteStreamerPlugin plugin, Intent intent) {
            handler.post(() -> {
                connectPlugin(plugin);
                buildNotification(intent);
                startForegroundCompat();
            });
        }

        /**
         * Keeps the service in the foreground for playback, whoever started it, so it keeps
         * playing (with a notification) while the app is in the background or was never
         * opened. Cancels a pending end-of-item demotion. Call on the main thread.
         */
        private void ensureForeground() {
            handler.removeCallbacks(leaveForegroundAfterEnd);
            if (isForeground) return;
            if (notificationBuilder == null) {
                buildNotification(null);
            }
            try {
                ContextCompat.startForegroundService(this, new Intent(this, RemoteStreamerService.class));
            } catch (Exception e) {
                Log.w(TAG, "Could not start the playback service in the foreground", e);
            }
            startForegroundCompat();
        }

        private void startForegroundCompat() {
            if (notificationBuilder == null) return;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                } else {
                    startForeground(NOTIFICATION_ID, notificationBuilder.build());
                }
                isForeground = true;
                if (plugin != null) plugin.onForegroundChanged(true);
            } catch (Exception e) {
                Log.w(TAG, "Could not enter the foreground", e);
            }
        }

        /** Leaves the foreground and lets the service stop once nothing is bound to it. */
        private void leaveForeground() {
            handler.removeCallbacks(leaveForegroundAfterEnd);
            isForeground = false;
            stopForeground(true);
            stopSelf();
            if (plugin != null) plugin.onForegroundChanged(false);
        }

        /** Whether playback keeps the service in the foreground (also briefly after an item ends). */
        public boolean isInForeground() {
            return isForeground;
        }

        /**
         * Builds the playback notification using the host's branding.
         *
         * @param activityIntent what tapping the notification opens; defaults to the app's launch activity
         */
        private void buildNotification(@Nullable Intent activityIntent) {
            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, settings.channelName, NotificationManager.IMPORTANCE_LOW);
                notificationManager.createNotificationChannel(channel);
            }

            notificationStyle = new MediaStyle().setMediaSession(mediaSession.getSessionToken());
            notificationBuilder = new NotificationCompat.Builder(RemoteStreamerService.this, NOTIFICATION_CHANNEL_ID)
                    .setStyle(notificationStyle)
                    .setSmallIcon(settings.smallIconResId)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            if (settings.iconColor != null) {
                notificationBuilder.setColor(settings.iconColor);
            }
            Intent launchIntent = activityIntent != null ? activityIntent : getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launchIntent != null) {
                notificationBuilder.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, launchIntent, PendingIntent.FLAG_IMMUTABLE));
            }

            if (notificationActions.isEmpty()) {
                notificationActions.put("play", new NotificationCompat.Action(
                        R.drawable.ic_baseline_play_arrow_24, "Play", MediaButtonReceiver.buildMediaButtonPendingIntent(RemoteStreamerService.this, (PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY))
                ));
                notificationActions.put("pause", new NotificationCompat.Action(
                        R.drawable.ic_baseline_pause_24, "Pause", MediaButtonReceiver.buildMediaButtonPendingIntent(RemoteStreamerService.this, (PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE))
                ));
                notificationActions.put("seekbackward", new NotificationCompat.Action(
                        R.drawable.ic_baseline_previous10, "Skip back 10 seconds", MediaButtonReceiver.buildMediaButtonPendingIntent(RemoteStreamerService.this, PlaybackStateCompat.ACTION_REWIND)
                ));
                notificationActions.put("seekforward", new NotificationCompat.Action(
                        R.drawable.ic_baseline_next10, "Skip forward 10 seconds", MediaButtonReceiver.buildMediaButtonPendingIntent(RemoteStreamerService.this, PlaybackStateCompat.ACTION_FAST_FORWARD)
                ));
                notificationActions.put("previoustrack", new NotificationCompat.Action(
                        R.drawable.ic_baseline_previous10, "Skip back 10 seconds", MediaButtonReceiver.buildMediaButtonPendingIntent(RemoteStreamerService.this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                ));
                notificationActions.put("nexttrack", new NotificationCompat.Action(
                        R.drawable.ic_baseline_next10, "Skip forward 10 seconds", MediaButtonReceiver.buildMediaButtonPendingIntent(RemoteStreamerService.this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
                ));
                notificationActions.put("stop", new NotificationCompat.Action(
                        R.drawable.ic_baseline_stop_24, "Stop", MediaButtonReceiver.buildMediaButtonPendingIntent(RemoteStreamerService.this, PlaybackStateCompat.ACTION_STOP)
                ));

                playbackStateActions.put("previoustrack", PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
                playbackStateActions.put("seekbackward", PlaybackStateCompat.ACTION_REWIND);
                playbackStateActions.put("play", (PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY));
                playbackStateActions.put("pause", (PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE));
                playbackStateActions.put("seekforward", PlaybackStateCompat.ACTION_FAST_FORWARD);
                playbackStateActions.put("nexttrack", PlaybackStateCompat.ACTION_SKIP_TO_NEXT);
                playbackStateActions.put("seekto", PlaybackStateCompat.ACTION_SEEK_TO);
                playbackStateActions.put("stop", PlaybackStateCompat.ACTION_STOP);
            }

            // Attach the actions and current metadata on the next update()
            possibleActionsUpdate = true;
            notificationUpdate = true;
        }

        public void destroy() {
            cancelReconnect();
            unregisterNetworkCallback();
            releasePlayer();
            if (executor != null) {
                executor.shutdownNow();
            }
            handler.removeCallbacks(leaveForegroundAfterEnd);
            isForeground = false;
            stopForeground(true);
            //mediaSession.setActive(false);
            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
            stopSelf();
        }

        @Override
        public void onDestroy() {
            cancelReconnect();
            stopUpdatingTime();
            unregisterNetworkCallback();
            if (player != null) {
                player.release();
                player = null;
            }
            if (libraryProvider != null) {
                libraryProvider.onDestroy();
            }
            if (executor != null) {
                executor.shutdownNow();
            }
            mediaSession.release();
            super.onDestroy();
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            // When the app is swiped away from recents, stop the service
            // if it's not playing.
            if (player == null || !player.isPlaying()) {
                destroy();
            }
            super.onTaskRemoved(rootIntent);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            MediaButtonReceiver.handleIntent(mediaSession, intent);
            super.onStartCommand(intent, flags, startId);
            return Service.START_NOT_STICKY;
        }

        public void setPlaybackState(int playbackState) {
            if (playbackState != this.playbackState) {
                this.playbackState = playbackState;
                playbackStateUpdate = true;
                possibleActionsUpdate = true;
            }
        }

        public void setTitle(String title)  {
            if (!title.equals(this.title)) {
                this.title = title;
                mediaMetadataUpdate = true;
                notificationUpdate = true;
            }
        }

        public void setArtist(String artist) {
            if (!artist.equals(this.artist)) {
                this.artist = artist;
                mediaMetadataUpdate = true;
                notificationUpdate = true;
            }
        }

        public void setAlbum(String album) {
            if (!album.equals(this.album)) {
                this.album = album;
                mediaMetadataUpdate = true;
                notificationUpdate = true;
            }
        }

        public void setArtwork(Bitmap artwork) {
            this.artwork = artwork;
            mediaMetadataUpdate = true;
            notificationUpdate = true;
        }

        public void setDuration(long duration) {
            if (this.duration != duration) {
                this.duration = duration;
                mediaMetadataUpdate = true;
                notificationUpdate = true;
            }
        }

        public void setPosition(long position) {
            if (this.position != position) {
                this.position = position;
                playbackStateUpdate = true;
            }
        }

        public void setPlaybackSpeed(float playbackSpeed) {
            if (this.playbackSpeed != playbackSpeed) {
                this.playbackSpeed = playbackSpeed;
                playbackStateUpdate = true;
            }
        }

        /** Whether a transport action is offered for the current content (no seeking in live streams). */
        private boolean isActionAvailable(String actionName) {
            return isLiveStream ? LIVE_ACTIONS.contains(actionName) : ON_DEMAND_ACTIONS.contains(actionName);
        }

        @SuppressLint("RestrictedApi")
        public void update() {
            if (possibleActionsUpdate) {
                if (notificationBuilder != null) {
                    notificationBuilder.mActions.clear();
                }

                long activePlaybackStateActions = 0;
                int[] activeCompactViewActionIndices = new int[3];

                // Always advertise basic transport actions for Android Auto since
                // playback is handled natively by the service
                if (playbackStateActions.isEmpty()) {
                    // The notification hasn't been built yet (a media browser is playing before JS init)
                    // Hardcode the essential actions so the car shows proper controls
                    activePlaybackStateActions = PlaybackStateCompat.ACTION_PLAY
                            | PlaybackStateCompat.ACTION_PAUSE
                            | PlaybackStateCompat.ACTION_PLAY_PAUSE
                            | PlaybackStateCompat.ACTION_STOP;
                    if (!isLiveStream) {
                        activePlaybackStateActions |= PlaybackStateCompat.ACTION_SEEK_TO
                                | PlaybackStateCompat.ACTION_REWIND
                                | PlaybackStateCompat.ACTION_FAST_FORWARD;
                    }
                } else {
                    String[] nativeActions = isLiveStream
                            ? new String[]{"play", "pause", "stop"}
                            : new String[]{"play", "pause", "seekto", "stop", "seekbackward", "seekforward"};
                    for (String nativeAction : nativeActions) {
                        if (playbackStateActions.containsKey(nativeAction)) {
                            activePlaybackStateActions = activePlaybackStateActions | playbackStateActions.get(nativeAction);
                        }
                    }
                }

                // Media browsers must always be able to start playback of browse items
                activePlaybackStateActions |= browseActions();

                int notificationActionIndex = 0;
                int compactNotificationActionIndicesIndex = 0;
                for (String actionName : possibleActions) {
                    if (isActionAvailable(actionName)) {
                        if (actionName.equals("play") && playbackState != PlaybackStateCompat.STATE_PAUSED) {
                            continue;
                        }
                        if (actionName.equals("pause") && playbackState != PlaybackStateCompat.STATE_PLAYING) {
                            continue;
                        }

                        if (playbackStateActions.containsKey(actionName)) {
                            activePlaybackStateActions = activePlaybackStateActions | playbackStateActions.get(actionName);
                        }

                        if (notificationActions.containsKey(actionName) && notificationBuilder != null) {
                            notificationBuilder.addAction(notificationActions.get(actionName));
                            if (possibleCompactViewActions.contains(actionName) && compactNotificationActionIndicesIndex < 3) {
                                activeCompactViewActionIndices[compactNotificationActionIndicesIndex] = notificationActionIndex;
                                compactNotificationActionIndicesIndex++;
                            }
                            notificationActionIndex++;
                        }
                    }
                }

                if (playbackStateBuilder != null) {
                    // A builder can't drop custom actions, so start a fresh one
                    playbackStateBuilder = new PlaybackStateCompat.Builder().setActions(activePlaybackStateActions);
                    if (!isLiveStream) {
                        playbackStateBuilder
                                .addCustomAction(MediaSessionCallback.ACTION_SEEK_BACKWARD, "Skip back 10 seconds", R.drawable.ic_baseline_previous10)
                                .addCustomAction(MediaSessionCallback.ACTION_SEEK_FORWARD, "Skip forward 10 seconds", R.drawable.ic_baseline_next10);
                    }
                }
                if (notificationStyle != null) {
                    if (compactNotificationActionIndicesIndex > 0) {
                        notificationStyle.setShowActionsInCompactView(Arrays.copyOfRange(activeCompactViewActionIndices, 0, compactNotificationActionIndicesIndex));
                    } else {
                        notificationStyle.setShowActionsInCompactView();
                    }
                }

                possibleActionsUpdate = false;
                playbackStateUpdate = true;
                notificationUpdate = true;
            }

            if (playbackStateUpdate && playbackStateBuilder != null) {
                if (isLiveStream) {
                    // No position, and nothing advancing: media controllers (Android Auto, the
                    // notification) show live content instead of a seek bar, as Media3 does
                    playbackStateBuilder.setState(this.playbackState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f);
                } else {
                    playbackStateBuilder.setState(this.playbackState, this.position, this.playbackSpeed);
                }
                mediaSession.setPlaybackState(playbackStateBuilder.build());
                playbackStateUpdate = false;
            }

            String displayTitle = title.isEmpty() ? settings.defaultTitle : title;

            if (mediaMetadataUpdate && mediaMetadataBuilder != null) {
                mediaMetadataBuilder
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                        // A negative duration means unknown/infinite: a live stream
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, isLiveStream ? -1 : duration);
                mediaSession.setMetadata(mediaMetadataBuilder.build());
                mediaMetadataUpdate = false;
            }

            if (notificationUpdate && notificationBuilder != null) {
                notificationBuilder
                        .setContentTitle(displayTitle)
                        .setContentText(joinNonEmpty(artist, album))
                        .setLargeIcon(artwork);
                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
                notificationUpdate = false;
            }
        }

        private static String joinNonEmpty(String first, String second) {
            if (first.isEmpty()) return second;
            if (second.isEmpty()) return first;
            return first + " - " + second;
        }

        public void updatePossibleActions() {
            this.possibleActionsUpdate = true;
            this.update();
        }

        /**
         * Plays a URL requested by JS. JS sends display metadata separately via setNowPlayingInfo.
         * A {@code bundle:<path>} URL plays a file the host app bundles with its web code.
         *
         * @return false when the URL names a bundled file that doesn't exist
         */
        public boolean play(String url) {
            if (url == null) return false;
            String playableUrl = playableUrl(url);
            if (playableUrl == null) {
                Log.w(TAG, "No bundled file for " + url);
                return false;
            }
            boolean isLive = playableUrl.contains(".m3u8");
            currentItem = PlayableItem.fromUrl(playableUrl, isLive);
            // Every item, not just the first: the app may be in the background between items
            handler.post(this::ensureForeground);
            playUrl(playableUrl, isLive);
            return true;
        }

        /** {@code url}, or for {@code bundle:<path>} the bundled asset's URI; null when that asset doesn't exist. */
        @Nullable
        private String playableUrl(@NonNull String url) {
            if (!url.startsWith(BUNDLE_SCHEME)) return url;
            String path = BUNDLED_WEB_ASSETS + url.substring(BUNDLE_SCHEME.length()).replaceFirst("^/+", "");
            try (InputStream ignored = getAssets().open(path)) {
                return "asset:///" + path;
            } catch (IOException e) {
                return null;
            }
        }

        private void playUrl(final String url, final boolean isLive) {
            // Cancel any pending reconnect
            cancelReconnect();
            reconnectAttempts = 0;
            isReconnecting = false;
            currentUrl = url;

            handler.post(() -> {
                // Release old player synchronously (we're already on the handler thread)
                if (player != null) {
                    stopUpdatingTime();
                    player.release();
                    player = null;
                }

                player = new ExoPlayer.Builder(this).build();

                MediaSource mediaSource;
                if (url.contains(".m3u8")) {
                    mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url));
                } else {
                    mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url));
                }
                this.isLiveStream = isLive;
                if (isLive) {
                    setDuration(0);
                    setPosition(0);
                }

                player.setMediaSource(mediaSource);
                player.prepare();

                setupPlayerListeners();

                int focusResult = audioManager.requestAudioFocus(focusRequest);
                if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    player.play();
                }
                setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                // Live/on-demand may have changed, so the available actions and the
                // published duration may have too
                possibleActionsUpdate = true;
                mediaMetadataUpdate = true;
                update();
                if (plugin != null) plugin.onPlayerEvent("play", new JSObject());
            });
        }

        public void pause() {
            if (player != null) {
                handler.post(() -> {
                    setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                    update();
                    if (player != null) {
                        player.pause();
                    }
                });
                if (plugin != null) plugin.onPlayerEvent("pause", new JSObject());
            }
        }

        public void resume() {
            if (player != null) {
                int focusResult = audioManager.requestAudioFocus(focusRequest);
                if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    handler.post(() -> {
                        if (player != null) {
                            if (isLiveStream) {
                                player.seekToDefaultPosition();
                            }
                            player.play();
                        }
                    });
                    setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    update();
                    if (plugin != null) plugin.onPlayerEvent("play", new JSObject());
                }
            }
        }

        public void seekTo(Long position) {
            if (player != null) {
                handler.post(() -> player.seekTo(position));
            }
        }

        public void seekBy(long offsetMs) {
            if (player != null) {
                handler.post(() -> {
                    if (player != null) {
                        long newPos = player.getCurrentPosition() + offsetMs;
                        if (newPos < 0) newPos = 0;
                        long duration = player.getDuration();
                        if (duration > 0 && newPos > duration) newPos = duration;
                        player.seekTo(newPos);
                    }
                });
            }
        }

        public boolean isLiveStream() {
            return isLiveStream;
        }

        public void stop() {
            stop(false);
        }

        public void stop(final boolean ended) {
            cancelReconnect();
            reconnectAttempts = 0;
            isReconnecting = false;
            if (plugin != null) plugin.onPlayerEvent("stop", new JSObject().put("ended", ended));
            setPlaybackState(PlaybackStateCompat.STATE_STOPPED);
            setPosition(0);
            update();
            releasePlayer();
            if (ended) {
                // Stay in the foreground for a while: the app may be in the background with the
                // screen off, and needs to run to start the next item before Android freezes it
                handler.removeCallbacks(leaveForegroundAfterEnd);
                handler.postDelayed(leaveForegroundAfterEnd, END_OF_ITEM_FOREGROUND_MS);
            } else {
                handler.post(this::leaveForeground);
            }
        }

        public void releasePlayer() {
            if (player != null) {
                if (plugin != null) plugin.onPlayerEvent("stop", new JSObject());
                handler.post(() -> {
                    Log.d("RemoteStreamerService", "releasing player");
                    stopUpdatingTime();
                    if (player != null) {
                            player.release();
                            player = null;
                    }
                    audioManager.abandonAudioFocusRequest(focusRequest);
                });
            }
        }

        private void setupPlayerListeners() {
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    switch (state) {
                        case Player.STATE_BUFFERING:
                            if (plugin != null) plugin.onPlayerEvent("buffering", new JSObject().put("isBuffering", true));
                            break;
                        case Player.STATE_READY:
                            if (plugin != null) plugin.onPlayerEvent("buffering", new JSObject().put("isBuffering", false));
                            if (!isLiveStream) startUpdatingTime();
                            {
                                long dur = player.getDuration();
                                JSObject readyData = new JSObject()
                                        .put("duration", (dur != C.TIME_UNSET && dur > 0) ? dur / 1000.0 : 0)
                                        .put("currentTime", player.getCurrentPosition() / 1000.0)
                                        .put("isLiveStream", isLiveStream);
                                if (plugin != null) plugin.onPlayerEvent("ready", readyData);
                            }
                            break;
                        case Player.STATE_ENDED:
                            stopUpdatingTime();
                            stop(true);
                            break;
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (isPlaying) {
                        if (plugin != null) plugin.onPlayerEvent("play", new JSObject());
                    } else {
                        // Don't fire pause during buffering — it's a transient state, not a user action.
                        // Nor at the end of an item: that is reported as stop {ended: true}
                        if (player != null && player.getPlaybackState() != Player.STATE_BUFFERING
                                && player.getPlaybackState() != Player.STATE_ENDED) {
                            if (plugin != null) plugin.onPlayerEvent("pause", new JSObject());
                        }
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    if (error.getCause() != null && error.getCause().getClass().equals(java.net.ConnectException.class)) {
                        pause();
                    }
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        player.seekToDefaultPosition();
                        player.prepare();
                        player.play();
                    }
                    if (plugin != null) plugin.onPlayerEvent("error", new JSObject().put("message", error.getMessage()));
                }
            });
        }

        // --- Reconnection logic ---

        private void reconnect() {
            if (currentUrl == null || !isLiveStream) return;
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                Log.w(TAG, "Max reconnect attempts (" + MAX_RECONNECT_ATTEMPTS + ") reached, pausing");
                isReconnecting = false;
                // Use PAUSED instead of ERROR so handleNetworkRestored can still revive
                setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                update();
                if (plugin != null) plugin.onPlayerEvent("pause", new JSObject());
                return;
            }

            isReconnecting = true;
            reconnectAttempts++;

            // Exponential backoff: 2s, 4s, 8s
            long delay = (long) (Math.pow(2, reconnectAttempts) * 1000);
            Log.d(TAG, "Reconnect attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + " in " + delay + "ms");

            setPlaybackState(PlaybackStateCompat.STATE_BUFFERING);
            update();
            if (plugin != null) plugin.onPlayerEvent("buffering", new JSObject().put("isBuffering", true));

            reconnectRunnable = () -> performReconnect(currentUrl);
            handler.postDelayed(reconnectRunnable, delay);
        }

        private void performReconnect(String url) {
            if (!isReconnecting) return;
            Log.d(TAG, "Performing reconnect to: " + url);

            handler.post(() -> {
                // Release old player
                if (player != null) {
                    stopUpdatingTime();
                    player.release();
                    player = null;
                }

                player = new ExoPlayer.Builder(RemoteStreamerService.this).build();
                player.setAudioAttributes(this.playerAudioAttributes, false);

                MediaSource mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(url));

                player.setMediaSource(mediaSource);
                player.prepare();
                setupPlayerListeners();

                int focusResult = audioManager.requestAudioFocus(focusRequest);
                if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    player.play();
                }
            });
        }

        private void performOnDemandResume() {
            if (currentUrl == null) return;
            handler.post(() -> {
                Log.d(TAG, "performOnDemandResume: rebuilding at position " + savedPosition + "ms");
                cancelReconnect();
                cancelStallWatchdog();

                if (player != null) {
                    stopUpdatingTime();
                    player.release();
                    player = null;
                }

                player = new ExoPlayer.Builder(RemoteStreamerService.this).build();
                player.setAudioAttributes(this.playerAudioAttributes, false);

                MediaSource mediaSource;
                if (currentUrl.contains(".m3u8")) {
                    mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(currentUrl));
                } else {
                    mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(currentUrl));
                }

                player.setMediaSource(mediaSource);
                setupPlayerListeners();
                player.prepare();

                if (savedPosition > 0) {
                    player.seekTo(savedPosition);
                }

                int focusResult = audioManager.requestAudioFocus(focusRequest);
                player.play();
                setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                update();
                if (plugin != null) plugin.onPlayerEvent("play", new JSObject());

                wasPlayingBeforeStall = false;
                isReconnecting = false;
            });
        }

        private void cancelReconnect() {
            if (reconnectRunnable != null) {
                handler.removeCallbacks(reconnectRunnable);
                reconnectRunnable = null;
            }
            cancelStallWatchdog();
        }

        private void startStallWatchdog() {
            cancelStallWatchdog();
            wasPlayingBeforeStall = true;
            // Save position for on-demand before we might lose it
            if (!isLiveStream && player != null) {
                long pos = player.getCurrentPosition();
                if (pos > 0) savedPosition = pos;
            }
            stallWatchdog = () -> {
                if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING) {
                    if (isLiveStream) {
                        // If already reconnecting, don't start another cycle
                        if (isReconnecting) {
                            Log.d(TAG, "Stall watchdog fired but already reconnecting, skipping");
                            return;
                        }
                        Log.d(TAG, "Stall watchdog fired after " + STALL_TIMEOUT_MS + "ms, attempting reconnect");
                        reconnect();
                    } else {
                        // On-demand: just pause and wait for network
                        Log.d(TAG, "Stall watchdog fired for on-demand, pausing at position " + savedPosition + "ms");
                        setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                        update();
                    }
                }
            };
            handler.postDelayed(stallWatchdog, STALL_TIMEOUT_MS);
        }

        private void cancelStallWatchdog() {
            if (stallWatchdog != null) {
                handler.removeCallbacks(stallWatchdog);
                stallWatchdog = null;
            }
        }

        private void setupNetworkCallback() {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    // Network came back
                    handler.post(() -> {
                        if (currentUrl != null && wasPlayingBeforeStall) {
                            if (isLiveStream) {
                                Log.d(TAG, "Network restored, rebuilding live stream player");
                                cancelReconnect();
                                reconnectAttempts = 0;
                                isReconnecting = true;
                                performReconnect(currentUrl);
                            } else {
                                // On-demand: rebuild and seek to saved position
                                Log.d(TAG, "Network restored, resuming on-demand at position " + savedPosition + "ms");
                                performOnDemandResume();
                            }
                        }
                    });
                }

                @Override
                public void onLost(Network network) {
                    handler.post(() -> {
                        if (player != null && player.isPlaying()) {
                            wasPlayingBeforeStall = true;
                            if (!isLiveStream) {
                                long pos = player.getCurrentPosition();
                                if (pos > 0) savedPosition = pos;
                            }
                        }
                    });
                }
            };
            connectivityManager.registerNetworkCallback(request, networkCallback);
        }

        private void unregisterNetworkCallback() {
            if (networkCallback != null && connectivityManager != null) {
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                } catch (IllegalArgumentException e) {
                    // Already unregistered
                }
                networkCallback = null;
            }
        }

        private void startUpdatingTime() {
            stopUpdatingTime();
            updateTimeTask = new Runnable() {
                @Override
                public void run() {
                    if (player != null && player.isPlaying()) {
                        long currentTime = player.getCurrentPosition();
                        long duration = player.getDuration();
                        if (duration != C.TIME_UNSET && duration > 0 && RemoteStreamerService.this.duration <= 0) {
                            setDuration(duration);
                        }
                        setPosition(currentTime);
                        update();
                        JSObject timeData = new JSObject()
                                .put("currentTime", currentTime / 1000.0)
                                .put("duration", duration == C.TIME_UNSET ? 0 : duration / 1000.0);
                        if (plugin != null) plugin.onPlayerEvent("timeUpdate", timeData);
                        handler.postDelayed(this, 500);
                    } else {
                        handler.postDelayed(this, 1000);
                    }
                }
            };
            handler.post(updateTimeTask);
        }

        private void stopUpdatingTime() {
            if (updateTimeTask != null) {
                handler.removeCallbacks(updateTimeTask);
                updateTimeTask = null;
            }
        }

        @Override
        public void onAudioFocusChange(int focusChange) {
            if (player == null) {
                return;
            }

            handler.post(() -> {
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_GAIN:
                        player.setVolume(1.0f);
                        if (resumeOnFocusLossTransient) {
                            player.play();
                        }
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS:
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        resumeOnFocusLossTransient = player.isPlaying();
                        player.pause();
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        player.setVolume(0.1f);
                        break;
                }
            });
        }

        public void setVolume(float volume) {
            handler.post(() -> {
                if (player != null) {
                    player.setVolume(volume);
                }
            });
        }

        public long getCurrentPosition() {
            if (player != null) {
                long pos = player.getCurrentPosition();
                if (pos > 0) return pos;
            }
            return position;
        }
    }
