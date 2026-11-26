package com.example.penguinz0player;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.Manifest;
import android.content.pm.PackageManager;
import com.google.firebase.messaging.FirebaseMessaging;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static volatile MainActivity activeInstance;
    private static final String TAG = "Penguinz0WebView";
    private FrameLayout root;
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService dataSyncExecutor = Executors.newSingleThreadExecutor();
    private File semanticModelDir;
    private File semanticDataDir;
    private File remoteWebDir;
    private final ExecutorService remoteUpdateExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean remoteUpdateInProgress = new AtomicBoolean(false);
    private volatile long activeRemoteUpdateRequestId = -1L;
    private boolean remoteUpdateCheckedThisLaunch = false;
    private int lastWebViewWidth = -1;
    private int lastWebViewHeight = -1;
    // Tracks YouTube iframe playback independently of Android WebChromeClient fullscreen callbacks.
    private volatile boolean videoPlaybackActive = false;
    private int pipPreviousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean restorePipOrientation = false;

    private static final String MODEL_REVISION = "751bff37182d3f1213fa05d7196b954e230abad9";
    private static final String MODEL_BASE = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/" + MODEL_REVISION + "/";
    private static final String[] MODEL_FILES = new String[] {
        "config.json", "tokenizer.json", "tokenizer_config.json",
        "special_tokens_map.json", "vocab.txt", "onnx/model_quantized.onnx"
    };

    private void captureNotificationIntent(Intent intent) {
        if (intent == null) return;
        String videoId = intent.getStringExtra("videoId");
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return;
        String title = intent.getStringExtra("gcm.n.title");
        if (title == null) title = intent.getStringExtra("title");
        String body = intent.getStringExtra("gcm.n.body");
        if (body == null) body = intent.getStringExtra("body");
        P0FirebaseMessagingService.storeNotification(getApplicationContext(),
                title == null ? "New Penguinz0 upload" : title,
                body == null ? "New upload received — semantic build started." : body,
                videoId);
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureNotificationIntent(intent);
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeInstance = this;
        captureNotificationIntent(getIntent());
        getWindow().setStatusBarColor(Color.rgb(16,16,16));
        getWindow().setNavigationBarColor(Color.rgb(16,16,16));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(15,15,15));
        root.setClipChildren(true);
        root.setClipToPadding(true);

        semanticModelDir = new File(getFilesDir(), "semantic-model");
        if (!semanticModelDir.exists()) semanticModelDir.mkdirs();
        semanticDataDir = new File(getFilesDir(), "semantic-data");
        if (!semanticDataDir.exists()) semanticDataDir.mkdirs();
        // Apply a previously verified update before WebView opens any app files.
        RemoteWebUpdateManager.installPending(this);
        RemoteWebUpdateManager.ensureBundledFallback(this);
        remoteWebDir = RemoteWebUpdateManager.activeDirectory(this);
        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/remote/", new WebViewAssetLoader.InternalStoragePathHandler(this, remoteWebDir))
                .addPathHandler("/semantic-model/", new WebViewAssetLoader.InternalStoragePathHandler(this, semanticModelDir))
                .addPathHandler("/semantic-data/", new WebViewAssetLoader.InternalStoragePathHandler(this, semanticDataDir))
                .build();

        webView = new WebView(this);
        // Keep the WebView on the window's normal hardware-accelerated path.
        // Do not force a separate hardware layer: Chromium video can be composed
        // on a separate Surface and forced View layers may leave that plane black
        // when Android snapshots/resizes the Activity for Picture-in-Picture.
        webView.setBackgroundColor(Color.rgb(15,15,15));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);
        webView.addJavascriptInterface(new SemanticStorageBridge(), "AndroidSemanticStorage");
        webView.addJavascriptInterface(new BackgroundPlaybackBridge(), "AndroidBackgroundPlayback");
        webView.addJavascriptInterface(new PictureInPictureBridge(), "AndroidPictureInPicture");
        webView.addJavascriptInterface(new NotificationsBridge(), "AndroidNotifications");

        webView.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.i(TAG, "Web page finished: " + url);
                view.evaluateJavascript("if(window.p0RefreshNotificationBadge)window.p0RefreshNotificationBadge();", null);
                if (!remoteUpdateCheckedThisLaunch && url != null && url.contains("/remote/index.html")) {
                    remoteUpdateCheckedThisLaunch = true;
                    view.postDelayed(() -> checkRemoteWebNow("onRemoteWebUpdateChecked"), 900);
                }
            }

            @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                                  android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                Log.e(TAG, "Web resource error: " + request.getUrl() + " code="
                        + error.getErrorCode() + " description=" + error.getDescription());
            }

            @Override public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request,
                                                      android.webkit.WebResourceResponse response) {
                super.onReceivedHttpError(view, request, response);
                if (request.isForMainFrame() || request.getUrl().getHost() != null
                        && request.getUrl().getHost().contains("youtube")) {
                    Log.e(TAG, "HTTP " + response.getStatusCode() + " for " + request.getUrl());
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                Log.d(TAG, "JS " + message.messageLevel() + " " + message.message()
                        + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                previousOrientation = getRequestedOrientation();
                webView.setVisibility(View.GONE);
                root.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                applyImmersiveMode(true);
            }
            @Override public void onHideCustomView() { hideCustomView(); }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if ((r-l)!=(or-ol) || (b-t)!=(ob-ot)) refreshWebViewBounds();
        });
        schedulePeriodicSemanticSync();
        schedulePeriodicRemoteWebUpdate();
        webView.loadUrl("https://appassets.androidplatform.net/remote/index.html");
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.postDelayed(() ->
                webView.evaluateJavascript("if(window.p0RefreshNotificationBadge)window.p0RefreshNotificationBadge();", null), 250);
    }

    private void schedulePeriodicSemanticSync() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                SemanticIndexSyncWorker.class, 1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "penguinz0-semantic-index-sync",
                ExistingPeriodicWorkPolicy.KEEP,
                periodic);
    }


    private void schedulePeriodicRemoteWebUpdate() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                RemoteWebUpdateWorker.class, 6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "penguinz0-remote-web-update",
                ExistingPeriodicWorkPolicy.KEEP,
                periodic);
    }

    private void checkRemoteWebNow(final String callbackName) {
        checkRemoteWebNow(callbackName, 0L, "onRemoteWebUpdateProgress");
    }

    private synchronized void checkRemoteWebNow(final String callbackName, final long requestId) {
        checkRemoteWebNow(callbackName, requestId, null);
    }

    private synchronized void checkRemoteWebNow(final String callbackName, final long requestId,
                                                final String progressCallbackName) {
        if (callbackName == null || !callbackName.matches("[A-Za-z_$][A-Za-z0-9_$]{0,63}")) return;
        final String safeProgressCallback = progressCallbackName != null
                && progressCallbackName.matches("[A-Za-z_$][A-Za-z0-9_$]{0,63}")
                ? progressCallbackName : null;
        if (!remoteUpdateInProgress.compareAndSet(false, true)) {
            org.json.JSONObject busy = new org.json.JSONObject();
            try {
                busy.put("status", "busy");
                busy.put("message", "An update check is already running.");
                busy.put("active_request_id", activeRemoteUpdateRequestId);
            } catch (org.json.JSONException ignored) {}
            deliverRemoteUpdateResult(callbackName, requestId, busy);
            return;
        }
        activeRemoteUpdateRequestId = requestId;
        try {
            remoteUpdateExecutor.execute(() -> {
                org.json.JSONObject result;
                try {
                    result = RemoteWebUpdateManager.checkAndStage(MainActivity.this,
                            (stage, downloaded, total, message) -> deliverRemoteWebProgress(
                                    safeProgressCallback, requestId, stage, downloaded, total, message));
                } catch (Throwable error) {
                    Log.e(TAG, "Remote update check crashed", error);
                    result = new org.json.JSONObject();
                    try {
                        result.put("status", "error");
                        result.put("message", error.getMessage() == null ? "Update check failed unexpectedly." : error.getMessage());
                    } catch (org.json.JSONException ignored) {}
                } finally {
                    activeRemoteUpdateRequestId = -1L;
                    remoteUpdateInProgress.set(false);
                }
                deliverRemoteUpdateResult(callbackName, requestId, result);
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            activeRemoteUpdateRequestId = -1L;
            remoteUpdateInProgress.set(false);
            org.json.JSONObject result = new org.json.JSONObject();
            try { result.put("status", "error"); result.put("message", "The update checker is restarting. Please try again."); }
            catch (org.json.JSONException ignored) {}
            deliverRemoteUpdateResult(callbackName, requestId, result);
        }
    }

    private void deliverRemoteWebProgress(String callbackName, long requestId, String stage,
                                          long downloaded, long total, String message) {
        if (callbackName == null || webView == null) return;
        org.json.JSONObject progress = new org.json.JSONObject();
        try {
            progress.put("stage", stage);
            progress.put("downloaded_bytes", downloaded);
            progress.put("total_bytes", total);
            progress.put("message", message == null ? "" : message);
            runOnUiThread(() -> {
                if (webView != null) webView.evaluateJavascript(
                        "if(window." + callbackName + ")window." + callbackName + "("
                                + requestId + "," + progress.toString() + ");", null);
            });
        } catch (org.json.JSONException ignored) {}
    }

    private void deliverRemoteUpdateResult(String callbackName, long requestId, org.json.JSONObject result) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("if(window." + callbackName + ")window." + callbackName
                        + "(" + requestId + "," + result.toString() + ");", null);
            }
        });
    }

    private final class SemanticStorageBridge {
        @JavascriptInterface public void checkRemoteWebUpdate(final String callbackName, final long requestId) {
            checkRemoteWebNow(callbackName, requestId);
        }

        @JavascriptInterface public void checkRemoteWebUpdate(final String callbackName,
                                                              final long requestId,
                                                              final String progressCallbackName) {
            checkRemoteWebNow(callbackName, requestId, progressCallbackName);
        }

        @JavascriptInterface public void restartForRemoteWebUpdate() {
            runOnUiThread(() -> {
                android.content.Intent intent = getIntent();
                finish();
                startActivity(intent);
            });
        }

        // Manual forced builds are limited to one attempt per hour. This timestamp
        // lives in native SharedPreferences, so WebView/localStorage resets cannot bypass it.
        @JavascriptInterface public synchronized boolean claimManualBuildCooldown() {
            final long cooldownMs = 60L * 60L * 1000L;
            android.content.SharedPreferences prefs = getSharedPreferences("p0_data_build_limits", MODE_PRIVATE);
            long last = prefs.getLong("last_manual_build_request", 0L);
            long now = System.currentTimeMillis();
            if (last > 0L && now - last < cooldownMs) return false;
            prefs.edit().putLong("last_manual_build_request", now).apply();
            return true;
        }

        @JavascriptInterface public long getManualBuildCooldownRemainingMs() {
            android.content.SharedPreferences prefs = getSharedPreferences("p0_data_build_limits", MODE_PRIVATE);
            long last = prefs.getLong("last_manual_build_request", 0L);
            return Math.max(0L, 60L * 60L * 1000L - (System.currentTimeMillis() - last));
        }

        @JavascriptInterface public void syncDataNow(final String callbackName, final String progressCallbackName) {
            dataSyncExecutor.execute(() -> {
                boolean success = SemanticDataSync.sync(MainActivity.this, (stage, detail) -> runOnUiThread(() -> {
                    if (webView != null) {
                        String safeStage = quoteJs(stage);
                        String safeDetail = quoteJs(detail);
                        webView.evaluateJavascript("if(window." + progressCallbackName + ")window." + progressCallbackName + "(" + safeStage + "," + safeDetail + ");", null);
                    }
                }));
                runOnUiThread(() -> {
                    if (webView != null) webView.evaluateJavascript("if(window." + callbackName + ")window." + callbackName + "(" + (success ? "true" : "false") + ");", null);
                });
            });
        }

        @JavascriptInterface public void ensureModel(final String callbackName) {
            modelExecutor.execute(() -> {
                boolean success = prepareModelFiles();
                runOnUiThread(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript("if(window." + callbackName + ")window." + callbackName + "(" + (success ? "true" : "false") + ");", null);
                    }
                });
            });
        }
    }

    private final class NotificationsBridge {
        @JavascriptInterface public void enable(final String callbackName) {
            if (callbackName == null || !callbackName.matches("[A-Za-z_$][A-Za-z0-9_$]{0,63}")) return;
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingNotificationCallback = callbackName;
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7103);
                } else {
                    fetchAndRegisterFcmToken();
                    deliverNotificationPermission(callbackName, true);
                }
            });
        }

        @JavascriptInterface public String getNotifications() {
            return P0FirebaseMessagingService.getStoredNotifications(getApplicationContext());
        }

        @JavascriptInterface public void markAllRead() {
            P0FirebaseMessagingService.markNotificationsRead(getApplicationContext());
        }

        @JavascriptInterface public void openVideo(final String videoId) {
            if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) return;
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=" + videoId));
                    startActivity(intent);
                } catch (Exception error) {
                    Log.e(TAG, "Could not open notification video", error);
                }
            });
        }
    }

    private String pendingNotificationCallback;

    private void deliverNotificationPermission(String callbackName, boolean granted) {
        if (webView == null || callbackName == null) return;
        webView.evaluateJavascript("if(window." + callbackName + ")window." + callbackName + "(" + (granted ? "true" : "false") + ");", null);
    }

    private void fetchAndRegisterFcmToken() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                    "penguinz0_uploads", "Penguinz0 uploads", NotificationManager.IMPORTANCE_DEFAULT));
        }
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                P0FirebaseMessagingService.registerToken(getApplicationContext(), task.getResult());
                Log.i(TAG, "FCM token available and registration requested");
            } else { Log.e(TAG, "Unable to obtain FCM token", task.getException()); }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7103) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) fetchAndRegisterFcmToken();
            String callback = pendingNotificationCallback;
            pendingNotificationCallback = null;
            deliverNotificationPermission(callback, granted);
        }
    }

    private final class PictureInPictureBridge {
        @JavascriptInterface public boolean enterFullscreenVideoPiP() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !videoPlaybackActive || isFinishing()) return false;
            runOnUiThread(() -> waitForFullscreenVideoSurface(0));
            return true;
        }
    }

    // The PiP button first asks the YouTube iframe to enter fullscreen. WebChromeClient
    // supplies the actual fullscreen video view through onShowCustomView; only then do we
    // enter PiP. Never rotate the whole Activity as a substitute for video fullscreen.
    private void waitForFullscreenVideoSurface(int attempt) {
        if (isFinishing() || isInPictureInPictureMode()) return;
        if (customView != null) {
            pipPreviousOrientation = getRequestedOrientation();
            restorePipOrientation = true;
            // Wait for the fullscreen surface to finish its layout before asking Android
            // to animate it into PiP. A stable source rectangle gives the system a reliable
            // start point for the shrink animation instead of racing WebView's transition.
            waitForStableFullscreenLayout(customView, -1, -1, 0);
            return;
        }
        if (attempt < 30) root.postDelayed(() -> waitForFullscreenVideoSurface(attempt + 1), 100);
        else if (webView != null) webView.evaluateJavascript(
                "if(window.p0PiPFullscreenFailed)window.p0PiPFullscreenFailed()", null);
    }


    private void waitForStableFullscreenLayout(View videoView, int previousWidth, int previousHeight, int stableFrames) {
        if (isFinishing() || isInPictureInPictureMode() || customView == null || customView != videoView) return;
        int width = videoView.getWidth();
        int height = videoView.getHeight();
        if (width <= 0 || height <= 0) {
            root.postOnAnimation(() -> waitForStableFullscreenLayout(videoView, width, height, 0));
            return;
        }
        int nextStableFrames = (width == previousWidth && height == previousHeight) ? stableFrames + 1 : 0;
        if (nextStableFrames < 2) {
            root.postOnAnimation(() -> waitForStableFullscreenLayout(videoView, width, height, nextStableFrames));
            return;
        }
        android.graphics.Rect visible = new android.graphics.Rect();
        if (!videoView.getGlobalVisibleRect(visible) || visible.isEmpty()) {
            root.postOnAnimation(() -> waitForStableFullscreenLayout(videoView, width, height, 0));
            return;
        }
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9))
                .setSourceRectHint(visible);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setSeamlessResizeEnabled(true);
        enterPictureInPictureMode(builder.build());
    }

    private final class BackgroundPlaybackBridge {
        @JavascriptInterface public void onPlaybackStarted(String title) {
            videoPlaybackActive = true;
            runOnUiThread(() -> {
                PlaybackService.start(MainActivity.this, title);
                updatePictureInPictureParams(true);
            });
        }
        @JavascriptInterface public void onPlaybackPaused() {
            videoPlaybackActive = false;
            runOnUiThread(() -> {
                PlaybackService.pause(MainActivity.this);
                updatePictureInPictureParams(false);
            });
        }

        @JavascriptInterface public void onPlaybackStopped() {
            videoPlaybackActive = false;
            runOnUiThread(() -> {
                PlaybackService.stop(MainActivity.this);
                updatePictureInPictureParams(false);
            });
        }
    }

    static void dispatchMediaCommand(String command) {
        MainActivity activity = activeInstance;
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (activity.webView == null) return;
            String safe = "play".equals(command) ? "play" : "pause";
            activity.webView.evaluateJavascript(
                    "if(window.p0MediaSessionCommand)window.p0MediaSessionCommand('" + safe + "');", null);
        });
    }

    private static String quoteJs(String value) {
        String v = value == null ? "" : value;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private boolean prepareModelFiles() {
        try {
            File marker = new File(semanticModelDir, ".complete");
            File model = new File(semanticModelDir, "Xenova/all-MiniLM-L6-v2/onnx/model_quantized.onnx");
            if (marker.exists() && model.exists() && model.length() > 20_000_000L) return true;
            File repoDir = new File(semanticModelDir, "Xenova/all-MiniLM-L6-v2");
            for (String relative : MODEL_FILES) {
                File target = new File(repoDir, relative);
                if (target.exists() && target.length() > (relative.endsWith(".onnx") ? 20_000_000L : 50L)) continue;
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
                if (!downloadModelFile(relative, target)) return false;
            }
            if (!model.exists() || model.length() < 20_000_000L) return false;
            if (!marker.createNewFile() && !marker.exists()) return false;
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean downloadModelFile(String relative, File target) {
        String encodedPath = relative.replace(" ", "%20");
        File partial = new File(target.getParentFile(), target.getName() + ".part");
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(MODEL_BASE + encodedPath + "?download=true");
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(90000);
                connection.setRequestProperty("User-Agent", "Penguinz0Player/1.0");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " for " + relative);
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    out.getFD().sync();
                }
                if (!partial.exists() || partial.length() == 0) throw new Exception("Empty model file: " + relative);
                if (target.exists()) target.delete();
                if (!partial.renameTo(target)) throw new Exception("Could not save model file: " + relative);
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                partial.delete();
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return false;
    }

    private void applyImmersiveMode(boolean immersive) {
        getWindow().getDecorView().setSystemUiVisibility(immersive
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void hideCustomView() {
        if (customView == null) return;
        View old = customView;
        customView = null;
        if (old.getParent() instanceof ViewGroup) ((ViewGroup)old.getParent()).removeView(old);
        webView.setVisibility(View.VISIBLE);
        applyImmersiveMode(false);
        setRequestedOrientation(previousOrientation);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        refreshWebViewBounds();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        refreshWebViewBounds();
    }

    private void refreshWebViewBounds() {
        if (root == null || webView == null) return;
        root.post(() -> {
            if (root == null || webView == null) return;
            int width = root.getWidth(), height = root.getHeight();
            if (width <= 0 || height <= 0) return;

            ViewGroup.LayoutParams params = webView.getLayoutParams();
            if (params != null && (params.width != ViewGroup.LayoutParams.MATCH_PARENT
                    || params.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
                webView.setLayoutParams(params);
            }

            // Several Android callbacks can report the same bounds during a
            // rotation/fullscreen transition. Dispatch one resize per real size change.
            if (width == lastWebViewWidth && height == lastWebViewHeight) return;
            lastWebViewWidth = width;
            lastWebViewHeight = height;
            webView.evaluateJavascript(
                    "document.documentElement.style.width='100%';document.body.style.width='100%';"
                    + "window.dispatchEvent(new Event('resize'));"
                    + "if(window.visualViewport)window.visualViewport.dispatchEvent(new Event('resize'));",
                    null);
        });
    }

    private void updatePictureInPictureParams(boolean playing) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isInPictureInPictureMode()) return;
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9));
        if (webView != null) {
            android.graphics.Rect visible = new android.graphics.Rect();
            if (webView.getGlobalVisibleRect(visible) && !visible.isEmpty()) {
                builder.setSourceRectHint(visible);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ performs the transition itself; set this while playback
            // is active, not inside onUserLeaveHint, to avoid a competing transition.
            builder.setAutoEnterEnabled(false);
            builder.setSeamlessResizeEnabled(true);
        }
        setPictureInPictureParams(builder.build());
    }

    @Override public void onPictureInPictureModeChanged(boolean pip, Configuration config) {
        super.onPictureInPictureModeChanged(pip, config);
        // Do not resize, restyle, hide, or reparent the WebView/iframe during PiP.
        // YouTube's iframe video is composed by Chromium on a separate video plane;
        // DOM/CSS cropping after the transition can detach that plane and show black.
        // The native fullscreen custom view remains visible when YouTube supplies one.
        applyImmersiveMode(pip || customView != null);
        if (!pip) {
            if (restorePipOrientation) {
                restorePipOrientation = false;
                setRequestedOrientation(pipPreviousOrientation);
            }
            refreshWebViewBounds();
        }
    }

    // Preserve the previously working manual landscape-fullscreen -> PiP route.
    // Portrait app-backgrounding must not auto-enter PiP; the dedicated button handles that path.
    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isFinishing()
                || isInPictureInPictureMode() || !videoPlaybackActive || customView == null) return;
        if (getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) return;
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9));
        android.graphics.Rect visible = new android.graphics.Rect();
        if (customView.getGlobalVisibleRect(visible) && !visible.isEmpty()) builder.setSourceRectHint(visible);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setSeamlessResizeEnabled(true);
        enterPictureInPictureMode(builder.build());
    }

    @Override public void onBackPressed() {
        if (customView != null && !isInPictureInPictureMode()) hideCustomView();
        else if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (activeInstance == this) activeInstance = null;
        modelExecutor.shutdownNow();
        dataSyncExecutor.shutdownNow();
        remoteUpdateExecutor.shutdownNow();
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
