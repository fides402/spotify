package com.monochrome.app;

import android.app.Activity;
import android.content.Intent;
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

    // Mobile Chrome UA → the site will render in mobile mode
    static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36";

    static final String TARGET_URL = "https://monochrome.tf/";
    static final int FILE_CHOOSER_REQUEST = 1001;

    WebView webView;
    ValueCallback<Uri[]> filePathCallback;
    PowerManager.WakeLock wakeLock;

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
            // Enforce proper mobile viewport so the site stays in mobile layout
            view.evaluateJavascript(
                    "(function(){" +
                    "  var m = document.querySelector('meta[name=viewport]');" +
                    "  if (!m) {" +
                    "    m = document.createElement('meta');" +
                    "    m.name = 'viewport';" +
                    "    document.head.appendChild(m);" +
                    "  }" +
                    "  m.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0';" +
                    "})();",
                    null);
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

        // PARTIAL_WAKE_LOCK keeps the CPU running when the screen turns off,
        // which is required for the WebView's audio thread to keep playing.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "monochrome:audiowake");
        wakeLock.acquire();

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

        // Mobile layout: respect the site's own viewport meta tag at 1:1 scale
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
        // Do NOT call webView.onPause() or webView.pauseTimers() here —
        // those methods suspend the WebView renderer and stop audio playback.
        // Leaving the WebView running is what allows music to continue when
        // the screen turns off or the user switches to another app.
    }

    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        webView.destroy();
        super.onDestroy();
    }
}
