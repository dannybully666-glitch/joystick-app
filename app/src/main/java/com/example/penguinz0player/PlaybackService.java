package com.example.penguinz0player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class PlaybackService extends Service {
    private static final String CHANNEL_ID = "penguinz0_playback";
    private static final int NOTIFICATION_ID = 7301;
    private static final String ACTION_START = "com.example.penguinz0player.PLAYBACK_START";
    private static final String ACTION_STOP = "com.example.penguinz0player.PLAYBACK_STOP";
    private static final String EXTRA_TITLE = "title";

    public static void start(Context context, String title) {
        Intent intent = new Intent(context, PlaybackService.class).setAction(ACTION_START);
        intent.putExtra(EXTRA_TITLE, title == null ? "Penguinz0 Player" : title);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, PlaybackService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Media playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps Penguinz0 Player audio active while the app is in the background");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        String title = intent.getStringExtra(EXTRA_TITLE);
        if (title == null || title.trim().isEmpty()) title = "Penguinz0 Player";
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 7302, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Playing in background")
                .setContentText(title)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
