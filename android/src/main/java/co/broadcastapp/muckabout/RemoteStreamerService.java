package co.broadcastapp.muckabout;


import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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

    public class RemoteStreamerService extends Service {
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

        private RemoteStreamerPlugin plugin;

        private final IBinder binder = new LocalBinder();

        public final class LocalBinder extends Binder {
            public RemoteStreamerService getService() {
                return RemoteStreamerService.this;
            }
        }

        @Override
        public IBinder onBind(Intent intent) {
            return binder;
        }

        @Override
        public boolean onUnbind(Intent intent) {
            this.destroy();

            return super.onUnbind(intent);
        }

        public void connectAndInitialize(RemoteStreamerPlugin plugin, Intent intent) {
            this.plugin = plugin;

            mediaSession = new MediaSessionCompat(this, "WebViewMediaSession");
            mediaSession.setCallback(new MediaSessionCallback(plugin));
            mediaSession.setActive(true);

            playbackStateBuilder = new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY)
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
    }