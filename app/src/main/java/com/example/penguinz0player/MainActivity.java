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
    private FrameLayout root;
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(16,16,16));
        getWindow().setNavigationBarColor(Color.rgb(16,16,16));

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(16,16,16));

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(16,16,16));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                previousOrientation = getRequestedOrientation();

                // Keep both surfaces in one MATCH_PARENT root. Avoid addContentView(),
                // which can leave the WebView with its old pre-rotation width.
                webView.setVisibility(View.GONE);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER);
                root.addView(customView, lp);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                setImmersive(true);
            }
            @Override public void onHideCustomView() { hideCustomView(); }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void setImmersive(boolean on) {
        getWindow().getDecorView().setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void hideCustomView() {
        if (customView == null) return;
        View v = customView;
        customView = null;
        if (v.getParent() instanceof ViewGroup) ((ViewGroup)v.getParent()).removeView(v);
        webView.setVisibility(View.VISIBLE);
        setImmersive(false);
        setRequestedOrientation(previousOrientation);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        refreshWebViewBounds();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Re-measure the WebView after the landscape -> portrait transition.
        refreshWebViewBounds();
    }

    private void refreshWebViewBounds() {
        if (root == null || webView == null) return;
        root.post(() -> {
            int w = root.getWidth(), h = root.getHeight();
            if (w <= 0 || h <= 0) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) webView.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            webView.setLayoutParams(lp);
            webView.requestLayout();
            root.requestLayout();
            webView.evaluateJavascript(
                "window.dispatchEvent(new Event('resize')); if(window.visualViewport){window.visualViewport.dispatchEvent(new Event('resize'));}",
                null);
            webView.invalidate();
        });
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Do not enter PiP for the app home/feed. Only request it during YouTube fullscreen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && customView != null
                && !isInPictureInPictureMode()) {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16,9)).build());
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean pip, Configuration config) {
        super.onPictureInPictureModeChanged(pip, config);
        if (webView != null) webView.setVisibility(pip && customView != null ? View.GONE : View.VISIBLE);
        if (customView != null) customView.setVisibility(View.VISIBLE);
        setImmersive(pip || customView != null);
        if (!pip) refreshWebViewBounds();
    }

    @Override public void onBackPressed() {
        if (customView != null) hideCustomView();
        else if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
