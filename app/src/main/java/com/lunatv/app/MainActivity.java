package com.lunatv.app;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;

public class MainActivity extends AppCompatActivity {

    private static final String LUNA_URL = "https://luna.amazon.com";

    static final String PREFS_NAME = "luna_tv";
    private static final String PREF_UA_MODE = "ua_mode";

    // Display modes: different sites serve different layouts per user-agent.
    // Desktop Chrome is the default; TV UAs get Luna's living-room layouts,
    // Mobile is a fallback for TVs that choke on the desktop layout.
    private static final String[] UA_MODE_NAMES = {
            "Desktop Chrome (default)",
            "Samsung TV (Tizen)",
            "LG TV (webOS)",
            "Mobile Chrome",
    };
    private static final String[] UA_STRINGS = {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 7.0) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) 94.0.4606.31/7.0 TV Safari/537.36",
            "Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/108.0.5359.211 Safari/537.36 WebAppManager",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
    };
    private static final int MODE_MOBILE = 3;

    // CSS layout viewport width forced for desktop/TV modes. TVs report high
    // densities, which otherwise gives Luna a ~960px viewport and oversized UI.
    private static final int DESKTOP_VIEWPORT_WIDTH = 1920;

    // Spoof the HTML5 Fullscreen API so Luna thinks we're already fullscreen.
    // Without this, Luna prompts the user to "go fullscreen" on every page load.
    private static final String FULLSCREEN_SPOOF_JS =
            "Object.defineProperty(document,'fullscreenElement',"
                    + "{get:function(){return document.documentElement;}});"
                    + "Object.defineProperty(document,'webkitFullscreenElement',"
                    + "{get:function(){return document.documentElement;}});"
                    + "Object.defineProperty(document,'webkitIsFullScreen',"
                    + "{get:function(){return true;}});"
                    + "Document.prototype.exitFullscreen=function(){return Promise.resolve();};"
                    + "Document.prototype.webkitExitFullscreen=function(){};"
                    + "Element.prototype.requestFullscreen=function(){return Promise.resolve();};"
                    + "Element.prototype.webkitRequestFullscreen=function(){};";

    private WebView webView;
    private FrameLayout rootLayout;
    private LinearLayout errorOverlay;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private SharedPreferences prefs;
    private UpdateChecker updateChecker;
    private AlertDialog activeDialog;
    // Set when the display mode changes: the old history was rendered under a
    // different UA, so drop it once the reloaded page finishes.
    private boolean clearHistoryOnLoad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        rootLayout = findViewById(R.id.root);
        errorOverlay = findViewById(R.id.error_overlay);
        webView = findViewById(R.id.webview);

        findViewById(R.id.retry_button).setOnClickListener(v -> {
            errorOverlay.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            webView.reload();
        });

        setupImmersiveMode();
        setupWebView();
        setupCookies();

        updateChecker = new UpdateChecker(this, prefs);
        updateChecker.checkDaily();

        webView.loadUrl(LUNA_URL);
    }

    // ── WebView Setup ──────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setOffscreenPreRaster(true);

        applyDisplayMode();

        // Remove X-Requested-With header (WebView detection vector)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(
                    settings, Collections.emptySet());
        }

        // Disable safe browsing URL checks (adds latency to every navigation)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, false);
        }

        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        webView.setWebViewClient(new LunaWebViewClient());
        webView.setWebChromeClient(new LunaWebChromeClient());
        webView.requestFocus();
    }

    private int getUaMode() {
        int mode = prefs.getInt(PREF_UA_MODE, 0);
        return (mode >= 0 && mode < UA_STRINGS.length) ? mode : 0;
    }

    // Apply the saved user-agent and matching viewport. Desktop/TV modes force
    // a 1920px CSS layout viewport regardless of screen density; mobile mode
    // lets the page's own viewport meta tag govern. Panels narrower than
    // 1920px (720p) skip the scale override — squeezing 1920 CSS px into
    // 1280 physical px would render everything at 2/3 size.
    private void applyDisplayMode() {
        int mode = getUaMode();
        WebSettings settings = webView.getSettings();
        settings.setUserAgentString(UA_STRINGS[mode]);
        settings.setUseWideViewPort(true);
        int screenWidthPx = getResources().getDisplayMetrics().widthPixels;
        if (mode == MODE_MOBILE || screenWidthPx < DESKTOP_VIEWPORT_WIDTH) {
            settings.setLoadWithOverviewMode(true);
            webView.setInitialScale(0);
        } else {
            settings.setLoadWithOverviewMode(false);
            webView.setInitialScale(
                    Math.round(100f * screenWidthPx / DESKTOP_VIEWPORT_WIDTH));
        }
    }

    @SuppressWarnings("deprecation")
    private void setupCookies() {
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);
    }

    // ── Settings ───────────────────────────────────────────────────────

    private void showSettingsDialog() {
        if (isFinishing() || isDestroyed()) return;
        String[] items = {
                "Display mode: " + UA_MODE_NAMES[getUaMode()],
                "Check for updates",
        };
        activeDialog = new AlertDialog.Builder(this)
                .setTitle("Luna TV " + BuildConfig.VERSION_NAME)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        showDisplayModeDialog();
                    } else {
                        updateChecker.checkNow();
                    }
                })
                .setOnDismissListener(dialog -> refocusWebView())
                .show();
    }

    private void showDisplayModeDialog() {
        if (isFinishing() || isDestroyed()) return;
        int current = getUaMode();
        activeDialog = new AlertDialog.Builder(this)
                .setTitle("Display mode")
                .setSingleChoiceItems(UA_MODE_NAMES, current, (dialog, which) -> {
                    dialog.dismiss();
                    if (which != current) {
                        prefs.edit().putInt(PREF_UA_MODE, which).apply();
                        applyDisplayMode();
                        clearHistoryOnLoad = true;
                        webView.loadUrl(LUNA_URL);
                    }
                })
                .setOnDismissListener(dialog -> refocusWebView())
                .show();
    }

    private void refocusWebView() {
        if (!isFinishing() && !isDestroyed()) {
            webView.requestFocus();
        }
    }

    // ── Immersive Mode ─────────────────────────────────────────────────

    private void setupImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Window window = getWindow();
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setupImmersiveMode();
        }
    }

    // ── Keys: Back (Xbox B) and Menu ───────────────────────────────────
    //
    // Short Back press: exit fullscreen → go back → exit app.
    // Long Back press (outside fullscreen video): settings dialog.
    // Menu key (TV remote): settings dialog.

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && customView == null) {
            showSettingsDialog();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && customView == null) {
            showSettingsDialog();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK
                && event.isTracking() && !event.isCanceled()) {
            if (customView != null) {
                hideCustomView();
                return true;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            finish();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ── HTML5 Fullscreen ───────────────────────────────────────────────

    private void hideCustomView() {
        if (customView == null) return;
        rootLayout.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        setupImmersiveMode();
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        setupImmersiveMode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        webView.destroy();
        super.onDestroy();
    }

    // ── WebViewClient ──────────────────────────────────────────────────

    private class LunaWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (clearHistoryOnLoad) {
                clearHistoryOnLoad = false;
                view.clearHistory();
            }
            errorOverlay.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            view.evaluateJavascript(FULLSCREEN_SPOOF_JS, null);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request.isForMainFrame()) {
                webView.setVisibility(View.GONE);
                errorOverlay.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                       android.net.http.SslError error) {
            handler.cancel();
        }
    }

    // ── WebChromeClient ────────────────────────────────────────────────

    private class LunaWebChromeClient extends WebChromeClient {

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> request.grant(request.getResources()));
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            webView.setVisibility(View.GONE);
            rootLayout.addView(customView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            setupImmersiveMode();
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }
    }
}
