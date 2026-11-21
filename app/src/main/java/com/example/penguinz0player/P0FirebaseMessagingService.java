package com.example.penguinz0player;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
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
        String videoId = message.getData().get("videoId");
        title = title == null ? "New Penguinz0 upload" : title;
        body = body == null ? "New upload received — semantic build started." : body;
        storeNotification(getApplicationContext(), title, body, videoId);
        showNotification(title, body, videoId);
    }

    private static final String PREFS = "p0_notification_history";
    private static final String HISTORY = "items";

    static void storeNotification(Context context, String title, String body, String videoId) {
        synchronized (P0FirebaseMessagingService.class) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray items = new JSONArray(prefs.getString(HISTORY, "[]"));
                if (videoId != null && !videoId.isEmpty()) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject old = items.optJSONObject(i);
                        if (old != null && videoId.equals(old.optString("videoId"))) return;
                    }
                }
                JSONObject item = new JSONObject();
                item.put("title", title == null ? "Penguinz0 upload" : title);
                item.put("body", body == null ? "" : body);
                item.put("videoId", videoId == null ? "" : videoId);
                item.put("timestamp", System.currentTimeMillis());
                item.put("read", false);
                JSONArray updated = new JSONArray();
                updated.put(item);
                for (int i = 0; i < items.length() && i < 49; i++) updated.put(items.get(i));
                prefs.edit().putString(HISTORY, updated.toString()).apply();
            } catch (JSONException error) {
                Log.e(TAG, "Could not save notification history", error);
            }
        }
    }

    static String getStoredNotifications(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HISTORY, "[]");
    }

    static void markNotificationsRead(Context context) {
        synchronized (P0FirebaseMessagingService.class) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray old = new JSONArray(prefs.getString(HISTORY, "[]"));
                JSONArray updated = new JSONArray();
                for (int i = 0; i < old.length(); i++) {
                    JSONObject item = old.optJSONObject(i);
                    if (item != null) { item.put("read", true); updated.put(item); }
                }
                prefs.edit().putString(HISTORY, updated.toString()).apply();
            } catch (JSONException error) {
                Log.e(TAG, "Could not mark notifications read", error);
            }
        }
    }

    private void showNotification(String title, String body, String videoId) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Penguinz0 uploads", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Alerts when a new Penguinz0 upload starts a catalogue build");
            manager.createNotificationChannel(channel);
        }
        Intent launch = new Intent(this, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (videoId != null) launch.putExtra("videoId", videoId);
        launch.putExtra("title", title);
        launch.putExtra("body", body);
        int notificationRequestCode = videoId == null ? 7101 : (videoId.hashCode() & 0x7fffffff);
        PendingIntent pending = PendingIntent.getActivity(this, notificationRequestCode, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(title).setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true).setContentIntent(pending);
        int notificationId = videoId == null ? 7102 : 7102 + ((videoId.hashCode() & 0x7fffffff) % 1000000);
        try { NotificationManagerCompat.from(this).notify(notificationId, notification.build()); }
        catch (SecurityException e) { Log.w(TAG, "Notification permission not granted", e); }
    }
}
