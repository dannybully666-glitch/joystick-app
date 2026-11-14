package com.example.penguinz0player;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean enteringPipFromVideo = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16, 17, 20));
        getWindow().setNavigationBarColor(Color.rgb(16, 17, 20));

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(16, 17, 20));

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(16, 17, 20));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }

                customView = view;
                customViewCallback = callback;
                previousOrientation = getRequestedOrientation();

                // Replace the app page with YouTube's actual fullscreen video surface.
                webView.setVisibility(View.GONE);
                FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER);
                root.addView(customView, videoParams);

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                setImmersiveMode(true);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void setImmersiveMode(boolean immersive) {
        if (immersive) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void hideCustomView() {
        if (customView == null) return;

        View oldView = customView;
        customView = null;
        if (oldView.getParent() instanceof ViewGroup) {
            ((ViewGroup) oldView.getParent()).removeView(oldView);
        }

        webView.setVisibility(View.VISIBLE);
        setImmersiveMode(false);
        setRequestedOrientation(previousOrientation);

        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    private void updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build();
            setPictureInPictureParams(params);
        }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();

        // Never put the app interface into PiP. PiP is only available while
        // YouTube's fullscreen video surface is active.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && customView != null
                && !isInPictureInPictureMode()) {
            try {
                updatePipParams();
                enteringPipFromVideo = true;
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9))
                        .build());
            } catch (IllegalStateException ignored) {
                enteringPipFromVideo = false;
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);

        if (inPip) {
            // Keep only the fullscreen YouTube video surface visible.
            if (webView != null) webView.setVisibility(View.GONE);
            if (customView != null) customView.setVisibility(View.VISIBLE);
            setImmersiveMode(true);
        } else {
            if (webView != null) webView.setVisibility(View.VISIBLE);
            setImmersiveMode(customView != null);
            enteringPipFromVideo = false;
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null && !isInPictureInPictureMode()) {
            hideCustomView();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
