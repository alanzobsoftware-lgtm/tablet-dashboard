package com.alanzob.tabletdashboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Full-screen kiosk wrapper for the home dashboard.
 *
 * - Shows the dashboard page full screen, keeps the screen on, hides Android's bars (and re-hides them if they reappear).
 * - Can be set as the tablet's Home app, so it starts on boot and the Home button comes back here.
 * - The dashboard itself (feeds, calendar, games, stocks) lives on the web and updates on its own.
 * - Hidden way out: tap the top-left corner 5 times within 3 seconds to open Android Settings.
 * - Night: the page asks the app to turn the real backlight down (TabletApp.setBrightness).
 * - Watchdog: if the page stops responding for about 3 minutes, the app reloads it.
 */
public class MainActivity extends Activity {
    private static final String DASHBOARD_URL = "https://alanzobsoftware-lgtm.github.io/tablet-dashboard/";
    private static final int CORNER_PX = 120;

    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int cornerTaps = 0;
    private long firstTapAt = 0;

    // Freeze watchdog: the page updates window.__alive every second; if it stops changing, reload.
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private long lastAlive = 0;
    private int stalls = 0;
    private boolean checkPending = false;
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (web == null) return;
            if (checkPending) stalls++;   // the last check never got an answer: the page is stuck
            checkPending = true;
            web.evaluateJavascript("(window.__alive||0)", value -> {
                checkPending = false;
                long v = 0;
                try { v = Long.parseLong(String.valueOf(value).replace("\"", "").trim()); } catch (Exception ignored) { }
                if (v > 0) {
                    if (v == lastAlive) stalls++; else stalls = 0;
                    lastAlive = v;
                }
            });
            if (stalls >= 3) {
                stalls = 0;
                checkPending = false;
                lastAlive = 0;
                web.loadUrl(DASHBOARD_URL);
            }
            watchdogHandler.postDelayed(this, 60_000);
        }
    };

    /** Small bridge the page can call: window.TabletApp.setBrightness(level), level 0..1, or -1 for normal. */
    private class Bridge {
        @JavascriptInterface
        public void setBrightness(final float level) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = level < 0
                        ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        : Math.max(0.01f, Math.min(1f, level));
                getWindow().setAttributes(lp);
            });
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);   // status bar stays hidden even if Android drops immersive mode
        if (Build.VERSION.SDK_INT >= 28) {
            // Use the whole screen, including the camera-notch area, instead of a black strip
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0d1117"));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                  // remembers settings and last-good data
        s.setMediaPlaybackRequiresUserGesture(false);  // radio can resume without a tap
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);                            // ignore the device's large-text setting; the page scales itself
        s.setUseWideViewPort(true);                    // honor the page's viewport so it measures the real screen
        s.setLoadWithOverviewMode(false);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Stay on the dashboard; never wander off to other sites.
                return !request.getUrl().toString().startsWith(DASHBOARD_URL);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    // No Wi-Fi yet (for example right after a reboot): try again in 30 seconds.
                    handler.removeCallbacksAndMessages(null);
                    handler.postDelayed(() -> { if (web != null) web.loadUrl(DASHBOARD_URL); }, 30_000);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The page engine crashed (for example low memory): start fresh.
                recreate();
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "TabletApp");

        setContentView(web);
        web.loadUrl(DASHBOARD_URL);
        watchdogHandler.postDelayed(watchdog, 90_000);
        hideSystemBars();
        // If the bars come back (a swipe from the edge, a pop-up menu), tuck them away again after 2 seconds.
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(vis -> {
            if ((vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) getWindow().getDecorView().postDelayed(this::hideSystemBars, 2000);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && ev.getX() < CORNER_PX && ev.getY() < CORNER_PX) {
            long now = System.currentTimeMillis();
            if (now - firstTapAt > 3000) {
                firstTapAt = now;
                cornerTaps = 0;
            }
            if (++cornerTaps >= 5) {
                cornerTaps = 0;
                Toast.makeText(this, "Opening Android Settings", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // Kiosk: Back does nothing.
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        watchdogHandler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
