package com.example.penguinz0player;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

public class PlaybackService extends Service {
    private static final String CHANNEL_ID = "penguinz0_playback";
    private static final int NOTIFICATION_ID = 7301;
    private static final String ACTION_START = "com.example.penguinz0player.PLAYBACK_START";
    private static final String ACTION_PAUSE = "com.example.penguinz0player.PLAYBACK_PAUSE";
    private static final String ACTION_PLAY = "com.example.penguinz0player.PLAYBACK_RESUME";
    private static final String ACTION_STOP = "com.example.penguinz0player.PLAYBACK_STOP";
    private static final String EXTRA_TITLE = "title";

    private MediaSessionCompat mediaSession;
    private String currentTitle = "Penguinz0 Player";
    private boolean isPlaying = true;

    public static void start(Context context, String title) {
        Intent intent = new Intent(context, PlaybackService.class).setAction(ACTION_START);
        intent.putExtra(EXTRA_TITLE, title == null ? "Penguinz0 Player" : title);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void pause(Context context) {
        context.startService(new Intent(context, PlaybackService.class).setAction(ACTION_PAUSE));
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, PlaybackService.class).setAction(ACTION_STOP));
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        mediaSession = new MediaSessionCompat(this, "Penguinz0Playback");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() {
                isPlaying = true;
                MainActivity.dispatchMediaCommand("play");
                updateSessionAndNotification();
            }
            @Override public void onPause() {
                isPlaying = false;
                MainActivity.dispatchMediaCommand("pause");
                updateSessionAndNotification();
            }
            @Override public void onStop() {
                MainActivity.dispatchMediaCommand("pause");
                stopForeground(true);
                stopSelf();
            }
        });
        mediaSession.setActive(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Media playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Playback controls for Penguinz0 Player");
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (mediaSession != null) {
                mediaSession.setActive(false);
                mediaSession.release();
                mediaSession = null;
            }
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            isPlaying = false;
            MainActivity.dispatchMediaCommand("pause");
            updateSessionAndNotification();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY.equals(action)) {
            isPlaying = true;
            MainActivity.dispatchMediaCommand("play");
            updateSessionAndNotification();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            String title = intent.getStringExtra(EXTRA_TITLE);
            if (title != null && !title.trim().isEmpty()) currentTitle = title.trim();
        }
        isPlaying = true;
        updateSessionAndNotification();
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_STICKY;
    }

    private void updateSessionAndNotification() {
        if (mediaSession == null) return;
        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Penguinz0")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentTitle)
                .build());
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, isPlaying ? 1f : 0f)
                .build());
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private NotificationCompat.Builder notificationBuilder() {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 7302, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent toggle = new Intent(this, PlaybackService.class)
                .setAction(isPlaying ? ACTION_PAUSE : ACTION_PLAY);
        PendingIntent toggleIntent = PendingIntent.getService(this, 7303, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, PlaybackService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 7304, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle)
                .setContentText(isPlaying ? "Playing in background" : "Playback paused")
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOnlyAlertOnce(true)
                .setOngoing(isPlaying)
                .addAction(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        isPlaying ? "Pause" : "Play", toggleIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
                .setStyle(new MediaStyle().setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1));
    }

    private android.app.Notification buildNotification() {
        return notificationBuilder().build();
    }

    @Override public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
