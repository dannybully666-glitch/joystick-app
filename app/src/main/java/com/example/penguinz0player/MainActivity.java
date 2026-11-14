package com.example.penguinz0player;

import android.app.Activity;
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
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
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
    private boolean remoteUpdateCheckedThisLaunch = false;

    private static final String MODEL_REVISION = "751bff37182d3f1213fa05d7196b954e230abad9";
    private static final String MODEL_BASE = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/" + MODEL_REVISION + "/";
    private static final String[] MODEL_FILES = new String[] {
        "config.json", "tokenizer.json", "tokenizer_config.json",
        "special_tokens_map.json", "vocab.txt", "onnx/model_quantized.onnx"
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        webView.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.i(TAG, "Web page finished: " + url);
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

    private void schedulePeriodicSemanticSync() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                SemanticIndexSyncWorker.class, 6, TimeUnit.HOURS)
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
        if (callbackName == null || !callbackName.matches("[A-Za-z_$][A-Za-z0-9_$]{0,63}")) return;
        remoteUpdateExecutor.execute(() -> {
            org.json.JSONObject result = RemoteWebUpdateManager.checkAndStage(MainActivity.this);
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.evaluateJavascript("if(window." + callbackName + ")window." + callbackName + "(" + result.toString() + ");", null);
                }
            });
        });
    }

    private final class SemanticStorageBridge {
        @JavascriptInterface public void checkRemoteWebUpdate(final String callbackName) {
            checkRemoteWebNow(callbackName);
        }

        @JavascriptInterface public void restartForRemoteWebUpdate() {
            runOnUiThread(() -> {
                android.content.Intent intent = getIntent();
                finish();
                startActivity(intent);
            });
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

    private final class BackgroundPlaybackBridge {
        @JavascriptInterface public void onPlaybackStarted(String title) {
            runOnUiThread(() -> PlaybackService.start(MainActivity.this, title));
        }
        @JavascriptInterface public void onPlaybackPaused() {
            runOnUiThread(() -> PlaybackService.stop(MainActivity.this));
        }
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
            int width = root.getWidth(), height = root.getHeight();
            if (width <= 0 || height <= 0) return;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) webView.getLayoutParams();
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            webView.setLayoutParams(params);
            webView.setMinimumWidth(width);
            webView.requestLayout();
            root.requestLayout();
            webView.evaluateJavascript("document.documentElement.style.width='100%';document.body.style.width='100%';window.dispatchEvent(new Event('resize'));if(window.visualViewport)window.visualViewport.dispatchEvent(new Event('resize'));", null);
            webView.invalidate();
        });
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && customView != null && !isInPictureInPictureMode()) {
            PlaybackService.start(this, "Penguinz0 Player");
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build();
            enterPictureInPictureMode(params);
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean pip, Configuration config) {
        super.onPictureInPictureModeChanged(pip, config);
        if (webView != null) webView.setVisibility(pip && customView != null ? View.GONE : View.VISIBLE);
        if (customView != null) customView.setVisibility(View.VISIBLE);
        applyImmersiveMode(pip || customView != null);
        if (!pip) refreshWebViewBounds();
    }

    @Override public void onBackPressed() {
        if (customView != null && !isInPictureInPictureMode()) hideCustomView();
        else if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        modelExecutor.shutdownNow();
        dataSyncExecutor.shutdownNow();
        remoteUpdateExecutor.shutdownNow();
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
