package com.monochrome.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    // Mobile Chrome UA — the site's own JS overrides navigator.userAgent to Windows
    // Chrome anyway, but the HTTP request UA and the pre-override window.__IS_IOS__
    // check still see this value. Using a mobile UA ensures the server (if it ever
    // does server-side detection) serves the mobile variant, and CSS media queries
    // based on viewport width handle the rest.
    static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36";

    static final String TARGET_URL = "https://monochrome.tf/";
    static final int FILE_CHOOSER_REQUEST = 1001;

    WebView webView;
    ValueCallback<Uri[]> filePathCallback;
    PowerManager.WakeLock wakeLock;

    // ── JS to inject once the page finishes loading ──────────────────────────
    //
    // 1. Viewport: only set width=device-width if the site has not already done
    //    so — we never want to fight the site's own responsive logic.
    //
    // 2. Visibility API override: some Android WebView builds set
    //    document.hidden=true when the screen turns off even if we did NOT call
    //    webView.onPause(). Overriding the getters keeps the site from pausing
    //    its Web Audio pipeline when the display goes dark.
    //
    // 3. AudioContext guard: intercept every AudioContext constructor call so
    //    that any context that gets auto-suspended (OS-level) is immediately
    //    re-resumed. This prevents the "music stops 30 s after screen off"
    //    symptom caused by the OS suspending the audio thread.
    static final String INJECT_JS =
        "(function(){" +

        // ── 1. Mobile viewport (only if needed) ──────────────────────────
        "  var vp = document.querySelector('meta[name=viewport]');" +
        "  if (!vp) {" +
        "    vp = document.createElement('meta');" +
        "    vp.name = 'viewport';" +
        "    document.head.appendChild(vp);" +
        "  }" +
        "  if (!vp.content || vp.content.indexOf('device-width') === -1) {" +
        "    vp.content = 'width=device-width, initial-scale=1.0';" +
        "  }" +

        // ── 2. Keep document always 'visible' so the site never pauses ───
        "  try {" +
        "    Object.defineProperty(document, 'hidden'," +
        "      { get: function(){ return false; }, configurable: true });" +
        "    Object.defineProperty(document, 'visibilityState'," +
        "      { get: function(){ return 'visible'; }, configurable: true });" +
        "  } catch(e) {}" +

        // ── 3. Auto-resume any suspended AudioContext ─────────────────────
        // Wrap the native constructor so every instance is tracked and kept alive.
        "  (function(){" +
        "    var _AC = window.AudioContext || window.webkitAudioContext;" +
        "    if (!_AC) return;" +
        "    var contexts = [];" +
        "    function WrapAC(opts) {" +
        "      var ctx = opts ? new _AC(opts) : new _AC();" +
        "      contexts.push(ctx);" +
        "      ctx.addEventListener('statechange', function(){" +
        "        if (ctx.state === 'suspended') {" +
        "          ctx.resume().catch(function(){});" +
        "        }" +
        "      });" +
        "      return ctx;" +
        "    }" +
        "    WrapAC.prototype = _AC.prototype;" +
        "    window.AudioContext = WrapAC;" +
        "    window.webkitAudioContext = WrapAC;" +
        // Poll every 2 s: resume any context that slipped through
        "    setInterval(function(){" +
        "      contexts.forEach(function(c){" +
        "        if (c.state === 'suspended') c.resume().catch(function(){});" +
        "      });" +
        "    }, 2000);" +
        "  })();" +

        "})();";

    // ── Static WebViewClient ─────────────────────────────────────────────────
    static class MonoWebViewClient extends WebViewClient {

        private final MainActivity host;

        MonoWebViewClient(MainActivity host) {
            this.host = host;
        }

        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            String url = req.getUrl().toString();
            if (!url.startsWith("https://monochrome.tf") &&
                    !url.startsWith("http://monochrome.tf")) {
                host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }
            return false;
        }

        public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                       SslError error) {
            handler.proceed();
        }

        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            view.evaluateJavascript(INJECT_JS, null);
        }
    }

    // ── Static WebChromeClient ───────────────────────────────────────────────
    static class MonoChromeClient extends WebChromeClient {

        private final MainActivity host;

        MonoChromeClient(MainActivity host) {
            this.host = host;
        }

        public void onPermissionRequest(PermissionRequest request) {
            request.grant(request.getResources());
        }

        public void onGeolocationPermissionsShowPrompt(String origin,
                GeolocationPermissions.Callback callback) {
            callback.invoke(origin, true, false);
        }

        public boolean onShowFileChooser(WebView view,
                ValueCallback<Uri[]> fileCb,
                WebChromeClient.FileChooserParams params) {
            if (host.filePathCallback != null) {
                host.filePathCallback.onReceiveValue(null);
            }
            host.filePathCallback = fileCb;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            try {
                host.startActivityForResult(
                        Intent.createChooser(intent, "Select file"),
                        FILE_CHOOSER_REQUEST);
            } catch (Exception e) {
                host.filePathCallback = null;
                return false;
            }
            return true;
        }

        public boolean onJsAlert(WebView view, String url,
                                 String message, JsResult result) {
            result.confirm();
            return false;
        }

        public boolean onConsoleMessage(ConsoleMessage cm) {
            return true;
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // PARTIAL_WAKE_LOCK: keeps the CPU alive when the screen turns off.
        // Without this the CPU clock may drop so low that the audio thread
        // stutters or stops entirely (even if the WebView is not paused).
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "monochrome:audio");
        wakeLock.acquire();

        // Android 13+ requires explicit POST_NOTIFICATIONS permission so the
        // AudioService foreground notification is allowed to appear.
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
            }
        }

        // Start the foreground service. This is the single most important thing
        // for reliable background audio on Android: a foreground service with
        // FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK tells the OS that this process
        // is actively playing media and must not be killed.
        startService(new Intent(this, AudioService.class));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        setContentView(webView);

        configureWebView();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(TARGET_URL);
        }
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();

        s.setUserAgentString(MOBILE_UA);

        // With a mobile UA and useWideViewPort=true the WebView respects the
        // site's own <meta name="viewport"> tag (width=device-width) and renders
        // at the correct mobile width. loadWithOverviewMode=false keeps the
        // initial scale at 1:1 (no zoom-to-fit).
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            s.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        }
        s.setJavaScriptEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Allow autoplay without requiring a user gesture — critical for
        // gapless playback and for the player to start on load.
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setDefaultTextEncodingName("UTF-8");

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new MonoWebViewClient(this));
        webView.setWebChromeClient(new MonoChromeClient(this));
        webView.requestFocus();
    }

    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            String jsKey;
            if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                jsKey = "MediaTrackNext";
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                jsKey = "MediaTrackPrevious";
            } else {
                jsKey = "MediaPlayPause";
            }
            webView.evaluateJavascript(
                    "document.dispatchEvent(new KeyboardEvent('keydown'," +
                    "{key:'" + jsKey + "',bubbles:true}));",
                    null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    results = new Uri[]{data.getData()};
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    protected void onPause() {
        super.onPause();
        // DO NOT call webView.onPause() here. That method suspends the WebView
        // renderer process (pauses JS timers, stops Web Audio, freezes network
        // requests). Omitting it is what allows the site's audio pipeline to
        // keep running when the user switches apps or the screen turns off.
        //
        // The combination of:
        //   • not pausing the WebView
        //   • PARTIAL_WAKE_LOCK keeping the CPU awake
        //   • AudioService keeping the process alive (foreground service)
        //   • INJECT_JS auto-resuming any suspended AudioContext
        // gives us gapless, interruption-free background audio.
    }

    protected void onResume() {
        super.onResume();
        webView.onResume();
        // Re-inject after coming back to foreground — some devices suspend
        // AudioContexts on app-switch even without webView.onPause() being called.
        webView.evaluateJavascript(
            "(function(){" +
            "  var _AC = window.AudioContext || window.webkitAudioContext;" +
            "  if (!_AC) return;" +
            "  // Resume every live context" +
            "  try {" +
            "    document.querySelectorAll('audio,video').forEach(function(m){" +
            "      if (!m.paused) m.play().catch(function(){});" +
            "    });" +
            "  } catch(e) {}" +
            "  window.dispatchEvent(new Event('focus'));" +
            "  window.dispatchEvent(new Event('pageshow'));" +
            "})();",
            null);
    }

    protected void onDestroy() {
        stopService(new Intent(this, AudioService.class));
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        webView.destroy();
        super.onDestroy();
    }
}
