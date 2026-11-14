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

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16,16,16));
        getWindow().setNavigationBarColor(Color.rgb(16,16,16));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(15,15,15));
        root.setClipChildren(true);
        root.setClipToPadding(true);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
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

        // Re-measure whenever Android changes the available window bounds, including
        // rotation, gesture navigation and split/resize transitions.
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if ((r-l)!=(or-ol) || (b-t)!=(ob-ot)) refreshWebViewBounds();
        });
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
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
            webView.evaluateJavascript(
                "document.documentElement.style.width='100%';document.body.style.width='100%';" +
                "window.dispatchEvent(new Event('resize'));if(window.visualViewport)window.visualViewport.dispatchEvent(new Event('resize'));",
                null);
            webView.invalidate();
        });
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && customView != null && !isInPictureInPictureMode()) {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16,9)).build());
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
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
