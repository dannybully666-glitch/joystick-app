package com.example.penguinz0player;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class P0FirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "P0Notifications";
    private static final String WORKER = "https://penguinz0-live-catalogue.dannybully666.workers.dev";
    private static final String CHANNEL_ID = "penguinz0_uploads";

    @Override public void onNewToken(String token) {
        super.onNewToken(token);
        registerToken(getApplicationContext(), token);
    }

    static void registerToken(Context context, String token) {
        if (token == null || token.isEmpty()) return;
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(WORKER + "/fcm/register");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                String body = "{\"token\":\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
                try (OutputStream os = connection.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                int code = connection.getResponseCode();
                Log.i(TAG, "FCM token registration HTTP " + code);
            } catch (Exception e) {
                Log.e(TAG, "FCM token registration failed", e);
            } finally { if (connection != null) connection.disconnect(); }
        }).start();
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        String title = message.getNotification() != null ? message.getNotification().getTitle() : message.getData().get("title");
        String body = message.getNotification() != null ? message.getNotification().getBody() : message.getData().get("body");
        showNotification(title == null ? "New Penguinz0 upload" : title,
                body == null ? "New upload received — semantic build started." : body);
    }

    private void showNotification(String title, String body) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Penguinz0 uploads", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Alerts when a new Penguinz0 upload starts a catalogue build");
            manager.createNotificationChannel(channel);
        }
        Intent launch = new Intent(this, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 7101, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(title).setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true).setContentIntent(pending);
        try { NotificationManagerCompat.from(this).notify(7102, notification.build()); }
        catch (SecurityException e) { Log.w(TAG, "Notification permission not granted", e); }
    }
}
