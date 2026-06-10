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

    public class RemoteStreamerService extends MediaBrowserServiceCompat implements AudioManager.OnAudioFocusChangeListener {
        private static final String TAG = "RemoteStreamerService";

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

        private RemoteStreamerPlugin plugin;

        private final IBinder binder = new LocalBinder();

        private List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();
        // Cache of mediaId -> stream URI for items served through the browse tree
        private final Map<String, String> browseUriCache = new HashMap<>();
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
            return new BrowserRoot(ROOT_ID, null);
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
            root.add(makeBrowsableItem(CATEGORY_SHOWS, "All Shows", "Browse all WNYC shows"));
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
                MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                        .setMediaId(SHOW_PREFIX + show.slug)
                        .setTitle(show.title)
                        .setIconUri(show.imageUrl.isEmpty() ? null : android.net.Uri.parse(show.imageUrl))
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
            MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                    .setMediaId(mediaId)
                    .setTitle(title)
                    .setSubtitle(subtitle)
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

        private MediaBrowserCompat.MediaItem makePlayableItem(String mediaId, String title, String subtitle, String streamUrl, String imageUrl) {
            // Cache the URI for later playback lookup
            if (streamUrl != null && !streamUrl.isEmpty()) {
                browseUriCache.put(mediaId, streamUrl);
            }
            MediaDescriptionCompat desc = new MediaDescriptionCompat.Builder()
                    .setMediaId(mediaId)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setMediaUri(streamUrl == null || streamUrl.isEmpty() ? null : android.net.Uri.parse(streamUrl))
                    .setIconUri(imageUrl == null || imageUrl.isEmpty() ? null : android.net.Uri.parse(imageUrl))
                    .build();
            return new MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);
        }

        @Override
        public void onCreate() {
            super.onCreate();
            handler = new Handler(Looper.getMainLooper());
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            executor = Executors.newSingleThreadExecutor();
            bffApiClient = new BffApiClient("https://demo.native-app.wnyc.org");
            
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

            mediaSession = new MediaSessionCompat(this, "WebViewMediaSession");
            mediaSession.setCallback(new MediaSessionCallback(plugin, this));
            mediaSession.setActive(true);

            // Required for Android Auto to control playback via MediaBrowserServiceCompat
            setSessionToken(mediaSession.getSessionToken());

            playbackStateBuilder = new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                    .setState(PlaybackStateCompat.STATE_PAUSED, position, playbackSpeed);
            mediaSession.setPlaybackState(playbackStateBuilder.build());

            mediaMetadataBuilder = new MediaMetadataCompat.Builder()
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
            mediaSession.setMetadata(mediaMetadataBuilder.build());

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

                int notificationActionIndex = 0;
                int compactNotificationActionIndicesIndex = 0;
                for (String actionName : possibleActions) {
                    if (plugin.hasActionHandler(actionName)) {
                        if (actionName.equals("play") && playbackState != PlaybackStateCompat.STATE_PAUSED) {
                            continue;
                        }
                        if (actionName.equals("pause") && playbackState != PlaybackStateCompat.STATE_PLAYING) {
                            continue;
                        }

                        if (playbackStateActions.containsKey(actionName)) {
                            activePlaybackStateActions = activePlaybackStateActions | playbackStateActions.get(actionName);
                        }

                        if (notificationActions.containsKey(actionName)) {
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

            handler.post(() -> {
                releasePlayer();
                player = new ExoPlayer.Builder(this).build();

                MediaSource mediaSource;
                if (url.contains(".m3u8")) {
                    mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url));
                    this.isLiveStream = true;
                    setDuration(0);
                    setPosition(0);
                } else {
                    mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url));
                    this.isLiveStream = false;
                }

                player.setMediaSource(mediaSource);
                player.prepare();

                setupPlayerListeners();

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
            if (player != null) {
                Log.d("RemoteStreamerService", "resuming playback");
                int focusResult = audioManager.requestAudioFocus(focusRequest);
                if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    handler.post(() -> {
                        if (player != null) {
                            if (isLiveStream) {
                                // if a live stream is paused and resumed, catch up to live
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

        public void stop() {
            stop(false);
        }

        public void stop(final boolean ended) {
            if (plugin != null) plugin.onPlayerEvent("stop", new JSObject().put("ended", ended));
            releasePlayer();
            // Do not destroy service here, keep it alive or let it be destroyed by unbind if needed?
            // Original code destroyed service on stop. But we want it to persist?
            // If stopped, maybe we can stop foreground?
            stopForeground(true);
            // But we don't want to kill the service if the user just pressed stop but might play again?
            // Actually, if they press stop, the notification goes away.
            // So stopping foreground is correct.
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
                            break;
                        case Player.STATE_READY:
                            if (plugin != null) plugin.onPlayerEvent("buffering", new JSObject().put("isBuffering", false));
                            if (!isLiveStream) startUpdatingTime();
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
                        if (plugin != null) plugin.onPlayerEvent("pause", new JSObject());
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    if (error.getCause().getClass().equals(java.net.ConnectException.class)) {
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

        private void startUpdatingTime() {
            stopUpdatingTime();
            updateTimeTask = new Runnable() {
                @Override
                public void run() {
                    if (player != null && player.isPlaying()) {
                        long currentTime = player.getCurrentPosition();
                        long duration = player.getDuration();
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
            return position;
        }
    }