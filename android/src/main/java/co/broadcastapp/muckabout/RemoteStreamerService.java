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

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.session.MediaButtonReceiver;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import com.getcapacitor.JSObject;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.io.IOException;
import java.io.InputStream;
import android.graphics.BitmapFactory;

import androidx.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

    public class RemoteStreamerService extends MediaBrowserServiceCompat implements AudioManager.OnAudioFocusChangeListener {
        private static final String TAG = "RemoteStreamerService";
        private static final String EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
        private static final String EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
        private static final int CONTENT_STYLE_LIST_ITEM = 1;
        private static final int CONTENT_STYLE_GRID_ITEM = 2;

        private MediaSessionCompat mediaSession;
        private PlaybackStateCompat.Builder playbackStateBuilder;
        private MediaMetadataCompat.Builder mediaMetadataBuilder;
        private NotificationManager notificationManager;
        private NotificationCompat.Builder notificationBuilder;
        private MediaStyle notificationStyle;
        private final Map<String, NotificationCompat.Action> notificationActions = new HashMap<>();
        private final Map<String, Long> playbackStateActions = new HashMap<>();
        private final String[] possibleActions = {"previoustrack", "seekbackward", "play", "pause", "seekforward", "nexttrack", "seekto", "stop"};
        final Set<String> possibleCompactViewActions = new HashSet<>(Arrays.asList("previoustrack", "play", "pause", "nexttrack", "stop", "seekto"));
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
        private ConnectivityManager.NetworkCallback networkCallback;

        private RemoteStreamerPlugin plugin;

        private final IBinder binder = new LocalBinder();

        private List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
        // Cache of mediaId -> stream URI for items served through the browse tree
        private final Map<String, String> browseUriCache = new HashMap<>();
        // Cache of mediaId -> metadata (title, subtitle, imageUrl) for updating MediaSession
        private final Map<String, String[]> browseMetadataCache = new HashMap<>();
        private static final String ROOT_ID = "root";
        private static final String CATEGORY_LIVE = "live";
        private static final String CATEGORY_NEWS = "news";
        private static final String CATEGORY_TOP_STORIES = "top_stories";
        private static final String CATEGORY_SHOWS = "shows";
        private static final String SHOW_PREFIX = "show_";

        private BffApiClient bffApiClient;
        private ExecutorService executor;

        public final class LocalBinder extends Binder {
            public RemoteStreamerService getService() {
                return RemoteStreamerService.this;
            }
        }

        @Override
        public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
            Bundle extras = new Bundle();
            extras.putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM);
            extras.putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM);
            return new BrowserRoot(ROOT_ID, extras);
        }

        @Override
        public void onLoadChildren(final String parentMediaId, final Result<List<MediaBrowserCompat.MediaItem>> result) {
            // Detach so we can load asynchronously
            result.detach();

            executor.execute(() -> {
                List<MediaBrowserCompat.MediaItem> items;
                switch (parentMediaId) {
                    case ROOT_ID:
                        items = buildRootMenu();
                        break;
                    case CATEGORY_LIVE:
                        items = buildLiveStreams();
                        break;
                    case CATEGORY_NEWS:
                        items = buildLatestNews();
                        break;
                    case CATEGORY_TOP_STORIES:
                        items = buildTopStories();
                        break;
                    case CATEGORY_SHOWS:
                        items = buildAllShows();
                        break;
                    default:
                        if (parentMediaId.startsWith(SHOW_PREFIX)) {
                            String showSlug = parentMediaId.substring(SHOW_PREFIX.length());
                            items = buildEpisodes(showSlug);
                        } else {
                            items = new ArrayList<>();
                        }
                        break;
                }
                result.sendResult(items);
            });
        }

        private List<MediaBrowserCompat.MediaItem> buildRootMenu() {
            List<MediaBrowserCompat.MediaItem> root = new ArrayList<>();
            root.add(makeBrowsableItem(CATEGORY_LIVE, "Live Radio", "Listen to WNYC live streams"));
            root.add(makeBrowsableItem(CATEGORY_NEWS, "Latest News", "NYC Headlines & NPR News Now"));
            root.add(makeBrowsableItem(CATEGORY_TOP_STORIES, "Top Stories", "Curated stories from WNYC"));
            // All Shows uses grid layout for the show tiles
            Bundle showsExtras = new Bundle();
            showsExtras.putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID_ITEM);
            showsExtras.putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM);
            MediaDescriptionCompat showsDesc = new MediaDescriptionCompat.Builder()
                    .setMediaId(CATEGORY_SHOWS)
                    .setTitle("All Shows")
                    .setSubtitle("Browse all WNYC shows")
                    .setExtras(showsExtras)
                    .build();
            root.add(new MediaBrowserCompat.MediaItem(showsDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            return root;
        }

        private List<MediaBrowserCompat.MediaItem> buildLiveStreams() {
            List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
            List<BffApiClient.LiveStream> streams = bffApiClient.fetchLiveStreams();
            for (BffApiClient.LiveStream stream : streams) {
                String subtitle = stream.currentShowTitle.isEmpty() ? "Live" : stream.currentShowTitle;
                items.add(makePlayableItem("live_" + stream.slug, stream.stationName, subtitle, stream.hlsUrl, stream.imageUrl));
            }
            return items;
        }

        private List<MediaBrowserCompat.MediaItem> buildLatestNews() {
            List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
            List<BffApiClient.NewsItem> news = bffApiClient.fetchLatestNews();
            for (BffApiClient.NewsItem n : news) {
                String subtitle = n.showTitle;
                if (n.durationSeconds > 0) {
                    subtitle += " | " + (n.durationSeconds / 60) + " min";
                }
                items.add(makePlayableItem("news_" + n.id, n.title, subtitle, n.audioUrl, n.imageUrl));
            }
            return items;
        }

        private List<MediaBrowserCompat.MediaItem> buildTopStories() {
            List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
            List<BffApiClient.StoryItem> stories = bffApiClient.fetchTopStories();
            for (BffApiClient.StoryItem story : stories) {
                String subtitle = story.showTitle;
                if (story.durationSeconds > 0) {
                    subtitle += " | " + (story.durationSeconds / 60) + " min";
                }
                items.add(makePlayableItem("story_" + story.id, story.title, subtitle, story.audioUrl, story.imageUrl));
            }
            return items;
        }

        private List<MediaBrowserCompat.MediaItem> buildAllShows() {
            List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
            List<BffApiClient.Show> shows = bffApiClient.fetchAllShows();
            for (BffApiClient.Show show : shows) {
                // Each show's children (episodes) should render as a single-column list
                Bundle extras = new Bundle();
                extras.putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM);
                extras.putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM);
                MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                        .setMediaId(SHOW_PREFIX + show.slug)
                        .setTitle(show.title)
                        .setExtras(extras)
                        .setIconUri(resolveItemIconUri(show.imageUrl))
                        .build();
                items.add(new MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            }
            return items;
        }

        private List<MediaBrowserCompat.MediaItem> buildEpisodes(String showSlug) {
            List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
            List<BffApiClient.Episode> episodes = bffApiClient.fetchEpisodes(showSlug);
            for (BffApiClient.Episode ep : episodes) {
                String subtitle = ep.showTitle;
                if (ep.durationSeconds > 0) {
                    subtitle += " | " + (ep.durationSeconds / 60) + " min";
                }
                items.add(makePlayableItem("episode_" + ep.id, ep.title, subtitle, ep.audioUrl, ep.imageUrl));
            }
            return items;
        }

        private MediaBrowserCompat.MediaItem makeBrowsableItem(String mediaId, String title, String subtitle) {
            Bundle extras = makeListContentStyleExtras();
            MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                    .setMediaId(mediaId)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                .setExtras(extras)
                    .build();
            return new MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
        }

        public void setMediaItems(List<MediaBrowserCompat.MediaItem> items) {
            this.mediaItems = items;
            notifyChildrenChanged(ROOT_ID);
        }

        public String getStreamUrlForMediaId(String mediaId) {
            // First check manually-set media items
            for (MediaBrowserCompat.MediaItem item : mediaItems) {
                MediaDescriptionCompat desc = item.getDescription();
                if (desc.getMediaId() != null && desc.getMediaId().equals(mediaId)) {
                    if (desc.getMediaUri() != null) {
                        return desc.getMediaUri().toString();
                    }
                }
            }
            // Then check the browse tree cache
            return browseUriCache.get(mediaId);
        }

        /**
         * Updates the MediaSession metadata from the browse cache for a given mediaId.
         * Called when playback starts from Android Auto browse tree.
         */
        public void updateMetadataForMediaId(String mediaId) {
            String[] meta = browseMetadataCache.get(mediaId);
            if (meta != null) {
                setTitle(meta[0]);
                setArtist(meta[1]);
                setAlbum("");
                // Load artwork asynchronously if image URL is available
                if (!meta[2].isEmpty()) {
                    final String imageUrl = meta[2];
                    executor.execute(() -> {
                        try {
                            URL url = new URL(imageUrl);
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setConnectTimeout(5000);
                            conn.setReadTimeout(5000);
                            InputStream is = conn.getInputStream();
                            Bitmap bmp = BitmapFactory.decodeStream(is);
                            is.close();
                            conn.disconnect();
                            if (bmp != null) {
                                handler.post(() -> {
                                    setArtwork(bmp);
                                    update();
                                });
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to load artwork for " + mediaId, e);
                        }
                    });
                }
                update();
            }
        }

        private MediaBrowserCompat.MediaItem makePlayableItem(String mediaId, String title, String subtitle, String streamUrl, String imageUrl) {
            // Cache the URI and metadata for later playback lookup
            if (streamUrl != null && !streamUrl.isEmpty()) {
                browseUriCache.put(mediaId, streamUrl);
            }
            browseMetadataCache.put(mediaId, new String[]{
                title != null ? title : "",
                subtitle != null ? subtitle : "",
                imageUrl != null ? imageUrl : ""
            });
            Bundle extras = makeListContentStyleExtras();
            MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                    .setMediaId(mediaId)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setExtras(extras)
                    .setMediaUri(streamUrl == null || streamUrl.isEmpty() ? null : android.net.Uri.parse(streamUrl))
                    .setIconUri(resolveItemIconUri(imageUrl))
                    .build();
            return new MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
        }

        private android.net.Uri resolveItemIconUri(String imageUrl) {
            if (imageUrl != null && !imageUrl.trim().isEmpty() && !imageUrl.equals("null")) {
                return android.net.Uri.parse(imageUrl);
            }
            return android.net.Uri.parse("android.resource://" + getPackageName() + "/drawable/fallback_ep");
        }

        private Bundle makeListContentStyleExtras() {
            Bundle extras = new Bundle();
            extras.putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM);
            extras.putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM);
            return extras;
        }

        @Override
        public void onCreate() {
            super.onCreate();
            handler = new Handler(Looper.getMainLooper());
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            executor = Executors.newSingleThreadExecutor();
            bffApiClient = new BffApiClient("https://wnyc.org");
            setupNetworkCallback();

            // Initialize MediaSession immediately so Android Auto can connect
            // without waiting for the WebView plugin to bind.
            mediaSession = new MediaSessionCompat(this, "WebViewMediaSession");
            mediaSession.setCallback(new MediaSessionCallback(null, this));
            mediaSession.setActive(true);
            setSessionToken(mediaSession.getSessionToken());

            playbackStateBuilder = new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                            | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                            | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_STOP
                            | PlaybackStateCompat.ACTION_FAST_FORWARD | PlaybackStateCompat.ACTION_REWIND)
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f);
            mediaSession.setPlaybackState(playbackStateBuilder.build());

            mediaMetadataBuilder = new MediaMetadataCompat.Builder()
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 0);
            mediaSession.setMetadata(mediaMetadataBuilder.build());

            // Prepare default content (WNYC newscast) so Android Auto doesn't show a spinner
            executor.execute(() -> {
                List<BffApiClient.NewsItem> news = bffApiClient.fetchLatestNews();
                if (!news.isEmpty()) {
                    BffApiClient.NewsItem defaultItem = news.get(0); // WNYC local newscast
                    String mediaId = "news_" + defaultItem.id;
                    browseUriCache.put(mediaId, defaultItem.audioUrl);
                    browseMetadataCache.put(mediaId, new String[]{
                        defaultItem.title,
                        defaultItem.showTitle,
                        defaultItem.imageUrl
                    });
                    handler.post(() -> {
                        setTitle(defaultItem.title);
                        setArtist(defaultItem.showTitle);
                        update();
                    });
                }
            });
            
            String versionName = "1.0"; // Default version
            String deviceModel = android.os.Build.MODEL;
            String osVersion = android.os.Build.VERSION.RELEASE;
            try {
                versionName = getPackageManager()
                .getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                Log.e("RemoteStreamerService", "Failed to get version name", e);
            }
            String userAgent = "WNYC-App/" + versionName + " (Android " + osVersion + "; " + deviceModel + ")";
            DefaultHttpDataSource.Factory httpDataSourceFactory =
                new DefaultHttpDataSource.Factory().setUserAgent(userAgent);
            dataSourceFactory = new DefaultDataSource.Factory(this, httpDataSourceFactory);

            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build();
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
            // Only destroy when the local plugin unbinds, not when Android Auto disconnects
            if (intent != null && "android.media.browse.MediaBrowserService".equals(intent.getAction())) {
                return super.onUnbind(intent);
            }
            this.destroy();
            return super.onUnbind(intent);
        }

        public void connectAndInitialize(RemoteStreamerPlugin plugin, Intent intent) {
            this.plugin = plugin;

            // Update the callback with the plugin reference (was null during onCreate)
            mediaSession.setCallback(new MediaSessionCallback(plugin, this));

            notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel("playback", "Playback", NotificationManager.IMPORTANCE_LOW);
                notificationManager.createNotificationChannel(channel);
            }

            notificationStyle = new MediaStyle().setMediaSession(mediaSession.getSessionToken());
            notificationBuilder = new NotificationCompat.Builder(this, "playback")
                    .setStyle(notificationStyle)
                    .setSmallIcon(R.drawable.ic_baseline_wnyc_white)
                    .setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, PendingIntent.FLAG_IMMUTABLE))
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notificationBuilder.build());
            }

            notificationActions.put("play", new NotificationCompat.Action(
                    R.drawable.ic_baseline_play_arrow_24, "Play", MediaButtonReceiver.buildMediaButtonPendingIntent(this, (PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY))
            ));
            notificationActions.put("pause", new NotificationCompat.Action(
                    R.drawable.ic_baseline_pause_24, "Pause", MediaButtonReceiver.buildMediaButtonPendingIntent(this, (PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE))
            ));
            notificationActions.put("seekbackward", new NotificationCompat.Action(
                    R.drawable.ic_baseline_previous10, "Previous Track", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_REWIND)
            ));
            notificationActions.put("seekforward", new NotificationCompat.Action(
                    R.drawable.ic_baseline_next10, "Next Track", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_FAST_FORWARD)
            ));
            notificationActions.put("previoustrack", new NotificationCompat.Action(
                    R.drawable.ic_baseline_previous10, "Previous Track", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            ));
            notificationActions.put("nexttrack", new NotificationCompat.Action(
                    R.drawable.ic_baseline_next10, "Next Track", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            ));
            notificationActions.put("stop", new NotificationCompat.Action(
                    R.drawable.ic_baseline_stop_24, "Stop", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)
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

        public void destroy() {
            cancelReconnect();
            if (networkCallback != null && connectivityManager != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
            releasePlayer();
            if (executor != null) {
                executor.shutdownNow();
            }
            stopForeground(true);
            //mediaSession.setActive(false);
            notificationManager.cancel(NOTIFICATION_ID);
            stopSelf();
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
                    // connectAndInitialize() hasn't been called yet (Android Auto playing before JS init)
                    // Hardcode the essential actions so DHU shows proper controls
                    activePlaybackStateActions = PlaybackStateCompat.ACTION_PLAY
                            | PlaybackStateCompat.ACTION_PAUSE
                            | PlaybackStateCompat.ACTION_PLAY_PAUSE
                            | PlaybackStateCompat.ACTION_STOP;
                    if (!isLiveStream) {
                        // SKIP_TO_PREVIOUS/NEXT are what Android Auto renders as side buttons
                        // They're wired to 10-second seeks in MediaSessionCallback
                        activePlaybackStateActions |= PlaybackStateCompat.ACTION_SEEK_TO
                                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
                    }
                } else {
                    String[] nativeActions = isLiveStream
                            ? new String[]{"play", "pause", "stop"}
                            : new String[]{"play", "pause", "seekto", "stop", "previoustrack", "nexttrack"};
                    for (String nativeAction : nativeActions) {
                        if (playbackStateActions.containsKey(nativeAction)) {
                            activePlaybackStateActions = activePlaybackStateActions | playbackStateActions.get(nativeAction);
                        }
                    }
                }

                int notificationActionIndex = 0;
                int compactNotificationActionIndicesIndex = 0;
                for (String actionName : possibleActions) {
                    boolean actionAvailable = (plugin != null && plugin.hasActionHandler(actionName));
                    if (actionAvailable) {
                        if (actionName.equals("play") && playbackState != PlaybackStateCompat.STATE_PAUSED) {
                            continue;
                        }
                        if (actionName.equals("pause") && playbackState != PlaybackStateCompat.STATE_PLAYING) {
                            continue;
                        }
                        // For Android Auto: skip all seek/skip actions for live streams
                        if (isLiveStream && (actionName.equals("seekforward") || actionName.equals("seekbackward")
                                || actionName.equals("previoustrack") || actionName.equals("nexttrack"))) {
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
                    playbackStateBuilder.setActions(activePlaybackStateActions);
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
                playbackStateBuilder.setState(this.playbackState, this.position, this.playbackSpeed);
                mediaSession.setPlaybackState(playbackStateBuilder.build());
                playbackStateUpdate = false;
            }

            if (mediaMetadataUpdate && mediaMetadataBuilder != null) {
                mediaMetadataBuilder
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
                mediaSession.setMetadata(mediaMetadataBuilder.build());
                mediaMetadataUpdate = false;
            }

            if (notificationUpdate && notificationBuilder != null) {
                notificationBuilder
                        .setContentTitle(title)
                        .setContentText(artist + " - " + album)
                        .setLargeIcon(artwork);
                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
                notificationUpdate = false;
            }
        }

        public void updatePossibleActions() {
            this.possibleActionsUpdate = true;
            this.update();
        }

        public void play(String url) {
            if (url == null) return;

            // Cancel any pending reconnect
            cancelReconnect();
            reconnectAttempts = 0;
            isReconnecting = false;
            currentUrl = url;

            handler.post(() -> {
                // Release old player synchronously (we're already on the handler thread)
                if (player != null) {
                    Log.d("RemoteStreamerService", "releasing player before new play");
                    stopUpdatingTime();
                    player.release();
                    player = null;
                    audioManager.abandonAudioFocusRequest(focusRequest);
                }

                player = new ExoPlayer.Builder(this).build();

                MediaSource mediaSource;
                if (url.contains(".m3u8")) {
                    mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url));
                    this.isLiveStream = true;
                    setDuration(-1); // -1 signals live/unknown duration to Android Auto (hides timeline)
                    setPosition(0);
                } else {
                    mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url));
                    this.isLiveStream = false;
                }

                player.setMediaSource(mediaSource);
                player.prepare();

                setupPlayerListeners();
                updatePossibleActions(); // refresh actions (seek available for on-demand, not for live)

                int focusResult = audioManager.requestAudioFocus(focusRequest);
                if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    player.play();
                }
                Log.d("stream", "playing");
                setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                update();

                if (plugin != null) plugin.onPlayerEvent("play", new JSObject());
            });
        }

        public void pause() {
            if (player != null) {
                Log.d("RemoteStreamerService", "pausing playback");
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
            handler.post(() -> {
                // For live streams: if player is null, errored, or stalled (buffering), rebuild from scratch
                if (isLiveStream && currentUrl != null) {
                    boolean needsRebuild = (player == null)
                            || (player.getPlayerError() != null)
                            || (player.getPlaybackState() == Player.STATE_BUFFERING)
                            || (player.getPlaybackState() == Player.STATE_IDLE);
                    if (needsRebuild) {
                        Log.d(TAG, "resume() on stalled/failed live stream, rebuilding player");
                        cancelReconnect();
                        reconnectAttempts = 0;
                        isReconnecting = true;
                        performReconnect(currentUrl);
                        return;
                    }
                }
                // For on-demand: if player is null, errored, or stalled, rebuild and seek to saved position
                if (!isLiveStream && currentUrl != null) {
                    boolean needsRebuild = (player == null)
                            || (player.getPlayerError() != null)
                            || (player.getPlaybackState() == Player.STATE_BUFFERING && wasPlayingBeforeStall)
                            || (player.getPlaybackState() == Player.STATE_IDLE);
                    if (needsRebuild) {
                        Log.d(TAG, "resume() on stalled/failed on-demand, rebuilding at position " + savedPosition + "ms");
                        performOnDemandResume();
                        return;
                    }
                }
                if (player != null) {
                    Log.d("RemoteStreamerService", "resuming playback");
                    int focusResult = audioManager.requestAudioFocus(focusRequest);
                    if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        if (isLiveStream) {
                            // if a live stream is paused and resumed, catch up to live
                            player.seekToDefaultPosition();
                        }
                        player.play();
                        setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                        update();
                        if (plugin != null) plugin.onPlayerEvent("play", new JSObject());
                    }
                }
            });
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
            stopForeground(true);
        }

        public void releasePlayer() {
            if (player != null) {
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
                            // Save position for on-demand recovery
                            if (!isLiveStream && player != null) {
                                long pos = player.getCurrentPosition();
                                if (pos > 0) savedPosition = pos;
                            }
                            // Start stall watchdog (but not if already reconnecting)
                            if (!isReconnecting) startStallWatchdog();
                            break;
                        case Player.STATE_READY:
                            // Playback recovered — reset reconnect state
                            cancelStallWatchdog();
                            reconnectAttempts = 0;
                            isReconnecting = false;
                            wasPlayingBeforeStall = false;
                            if (plugin != null) plugin.onPlayerEvent("buffering", new JSObject().put("isBuffering", false));
                            if (!isLiveStream) startUpdatingTime();
                            break;
                        case Player.STATE_ENDED:
                            cancelStallWatchdog();
                            stopUpdatingTime();
                            savedPosition = 0; // reset so next play starts from beginning
                            stop(true);
                            break;
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (isPlaying) {
                        if (plugin != null) plugin.onPlayerEvent("play", new JSObject());
                    } else {
                        if (!isReconnecting && plugin != null) {
                            plugin.onPlayerEvent("pause", new JSObject());
                        }
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "Player error: " + error.getMessage() + " (code=" + error.errorCode + ")", error);

                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        // Recoverable: just seek back to live edge
                        player.seekToDefaultPosition();
                        player.prepare();
                        player.play();
                        return;
                    }

                    if (plugin != null) plugin.onPlayerEvent("error", new JSObject().put("message", error.getMessage()));

                    // For live streams, attempt reconnection
                    if (isLiveStream && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        reconnect();
                    } else if (!isLiveStream) {
                        // On-demand: save position, pause state, wait for network/user action
                        if (player != null) {
                            long pos = player.getCurrentPosition();
                            if (pos > 0) savedPosition = pos;
                        }
                        wasPlayingBeforeStall = true;
                        isReconnecting = false;
                        setPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                        update();
                        Log.d(TAG, "On-demand error: saved position=" + savedPosition + "ms, waiting for network");
                    } else {
                        // Live exhausted retries — stop
                        isReconnecting = false;
                        setPlaybackState(PlaybackStateCompat.STATE_ERROR);
                        update();
                        if (plugin != null) plugin.onPlayerEvent("stop", new JSObject().put("ended", false));
                    }
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

                MediaSource mediaSource;
                if (currentUrl.contains(".m3u8")) {
                    mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(currentUrl));
                } else {
                    mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(currentUrl));
                }

                player.setMediaSource(mediaSource);
                player.prepare();
                setupPlayerListeners();

                if (savedPosition > 0) {
                    player.seekTo(savedPosition);
                }

                int focusResult = audioManager.requestAudioFocus(focusRequest);
                if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    player.play();
                    setPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    update();
                    if (plugin != null) plugin.onPlayerEvent("play", new JSObject());
                }

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

        private void startUpdatingTime() {
            stopUpdatingTime();
            updateTimeTask = new Runnable() {
                @Override
                public void run() {
                    if (player == null || player.getPlaybackState() == Player.STATE_ENDED) {
                        // Player gone or finished — stop updating
                        return;
                    }
                    if (player.isPlaying()) {
                        long currentTime = player.getCurrentPosition();
                        long duration = player.getDuration();
                        // Clamp position to duration to prevent showing time past end
                        if (duration != C.TIME_UNSET && duration > 0 && currentTime > duration) {
                            currentTime = duration;
                        }
                        setDuration(duration);
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
            handler.post(() -> {
                if (player == null) {
                    return;
                }
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_GAIN:
                        player.setVolume(1.0f);
                        if (resumeOnFocusLossTransient) {
                            resumeOnFocusLossTransient = false;
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
            return position;
        }
    }