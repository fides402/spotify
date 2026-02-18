package com.monochrome.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.webkit.JavascriptInterface;
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

    static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36";

    static final String TARGET_URL = "https://monochrome.tf/";
        static final int FILE_CHOOSER_REQUEST = 1001;

    WebView webView;
        ValueCallback<Uri[]> filePathCallback;
        PowerManager.WakeLock wakeLock;

    // ── JS to inject once the page finishes loading ────────────────────────
    //
    // Compared to the previous version, the playback bridge (section 4) now
    // also extracts the artwork URL from navigator.mediaSession.metadata or
    // og:image, and passes it to AndroidBridge.updatePlayback() so
    // AudioService can fetch and display it in the notification widget.
    static final String INJECT_JS = "(function(){" +

            // ── 1. Mobile viewport (only if needed) ────────────────────────
            " var vp = document.querySelector('meta[name=viewport]');" +
            " if (!vp) {" +
            "   vp = document.createElement('meta');" +
            "   vp.name = 'viewport';" +
            "   document.head.appendChild(vp);" +
            " }" +
            " if (!vp.content || vp.content.indexOf('device-width') === -1) {" +
            "   vp.content = 'width=device-width, initial-scale=1.0';" +
            " }" +

            // ── 2. Keep document always 'visible' so the site never pauses ──
            " try {" +
            "   Object.defineProperty(document, 'hidden'," +
            "     { get: function(){ return false; }, configurable: true });" +
            "   Object.defineProperty(document, 'visibilityState'," +
            "     { get: function(){ return 'visible'; }, configurable: true });" +
            " } catch(e) {}" +

            // ── 3. Auto-resume any suspended AudioContext ───────────────────
            " (function(){" +
            "   var _AC = window.AudioContext || window.webkitAudioContext;" +
            "   if (!_AC) return;" +
            "   var contexts = [];" +
            "   function WrapAC(opts) {" +
            "     var ctx = opts ? new _AC(opts) : new _AC();" +
            "     contexts.push(ctx);" +
            "     ctx.addEventListener('statechange', function(){" +
            "       if (ctx.state === 'suspended') {" +
            "         ctx.resume().catch(function(){});" +
            "       }" +
            "     });" +
            "     return ctx;" +
            "   }" +
            "   WrapAC.prototype = _AC.prototype;" +
            "   window.AudioContext = WrapAC;" +
            "   window.webkitAudioContext = WrapAC;" +
            "   setInterval(function(){" +
            "     contexts.forEach(function(c){" +
            "       if (c.state === 'suspended') c.resume().catch(function(){});" +
            "     });" +
            "   }, 2000);" +
            " })();" +

            // ── 4. Playback state bridge → native notification ──────────────
            // Now also extracts artwork URL from mediaSession or og:image.
            " (function(){" +
            "   if (window._mcBridgeInit) return;" +
            "   window._mcBridgeInit = true;" +
            "   var lastPlaying = null, lastTitle = '', lastArtist = '', lastArt = '';" +

            "   function getArtworkUrl() {" +
            "     try {" +
            "       var meta = navigator.mediaSession && navigator.mediaSession.metadata;" +
            "       if (meta && meta.artwork && meta.artwork.length > 0) {" +
            "         var best = null; var bestSize = 0;" +
            "         for (var i = 0; i < meta.artwork.length; i++) {" +
            "           var a = meta.artwork[i];" +
            "           if (!a || !a.src) continue;" +
            "           var sz = a.sizes ? parseInt(a.sizes.split('x')[0]) || 0 : 0;" +
            "           if (sz >= bestSize) { best = a.src; bestSize = sz; }" +
            "         }" +
            "         if (best) return best;" +
            "       }" +
            "     } catch(e) {}" +
            "     var og = document.querySelector('meta[property=\"og:image\"]');" +
            "     if (og && og.content) return og.content;" +
            "     var tw = document.querySelector('meta[name=\"twitter:image\"]');" +
            "     if (tw && tw.content) return tw.content;" +
            "     var img = document.querySelector('img[class*=\"cover\"],img[class*=\"artwork\"]," +
            "       img[class*=\"album\"],img[class*=\"thumbnail\"]');" +
            "     if (img && img.src && img.naturalWidth > 60) return img.src;" +
            "     return '';" +
            "   }" +

            "   function report(playing, title, artist, artUrl, pos, dur) {" +
            "     title  = title  || '';" +
            "     artist = artist || '';" +
            "     artUrl = artUrl || '';" +
            "     pos = pos || 0; dur = dur || 0;" +
            "     if (!playing && playing === lastPlaying && title === lastTitle &&" +
            "         artist === lastArtist && artUrl === lastArt) return;" +
            "     lastPlaying = playing; lastTitle = title;" +
            "     lastArtist = artist;  lastArt   = artUrl;" +
            "     if (window.AndroidBridge) {" +
            "       AndroidBridge.updatePlayback(" +
            "         playing ? 'true' : 'false', title, artist, artUrl," +
            "         String(Math.round(pos * 1000))," +
            "         String(Math.round(dur * 1000)));" +
            "     }" +
            "   }" +

            "   function hookMedia(el) {" +
            "     if (el._mcHooked) return; el._mcHooked = true;" +
            "     function snap() {" +
            "       var meta = navigator.mediaSession && navigator.mediaSession.metadata;" +
            "       report(!el.paused && !el.ended," +
            "         meta ? meta.title || '' : document.title," +
            "         meta ? meta.artist || '' : ''," +
            "         getArtworkUrl()," +
            "         el.currentTime || 0, el.duration || 0);" +
            "     }" +
            "     el.addEventListener('play',  snap);" +
            "     el.addEventListener('pause', snap);" +
            "     el.addEventListener('ended', snap);" +
            "   }" +

            "   document.querySelectorAll('audio,video').forEach(hookMedia);" +

            "   var obs = new MutationObserver(function(muts) {" +
                                                       "     muts.forEach(function(m) {" +
                                                       "       m.addedNodes.forEach(function(n) {" +
                                                       "         if (n.nodeType !== 1) return;" +
                                                       "         if (n.tagName === 'AUDIO' || n.tagName === 'VIDEO') hookMedia(n);" +
                                                       "         if (n.querySelectorAll) n.querySelectorAll('audio,video').forEach(hookMedia);" +
                                                       "       });" +
                                                       "     });" +
                                                       "   });" +
                                                       "   if (document.body) obs.observe(document.body, {childList:true, subtree:true});" +

                                                       "   setInterval(function() {" +
                                                       "     var playing = false; var curEl = null;" +
                                                       "     document.querySelectorAll('audio,video').forEach(function(el) {" +
                                                       "       if (!el.paused && !el.ended && el.readyState > 2) { playing = true; curEl = el; }" +
                                                       "       else if (!curEl) curEl = el;" +
                                                       "     });" +
                                                       "     var meta = navigator.mediaSession && navigator.mediaSession.metadata;" +
            "     var title  = meta ? (meta.title  || document.title) : document.title;" +
            "     var artist = meta ? (meta.artist || '')             : '';" +
                                               "     var artUrl = getArtworkUrl();" +
                                               "     var pos = curEl ? (curEl.currentTime || 0) : 0;" +
                                               "     var dur = curEl ? (curEl.duration || 0) : 0;" +
                                               "     report(playing, title, artist, artUrl, pos, dur);" +
                                               "   }, 1000);" +
                                               " })();" +

                                               "})();";

        // ── JS bridge: web app → native ────────────────────────────────────────

        /**
                                            * Exposed to JavaScript as window.AndroidBridge.
         * Called by the injected JS whenever playback state or track info changes.
         * Now accepts a 4th parameter: artworkUrl — the URL of the cover art image.
         * Runs on a background WebView thread → must not touch the UI directly.
         */
        final class MonoJSBridge {
            @JavascriptInterface
            public void updatePlayback(String isPlayingStr, String title, String artist,
                                       String artworkUrl, String positionMs, String durationMs) {
                            Intent i = new Intent(MainActivity.this, AudioService.class);
                            i.setAction(AudioService.ACTION_UPDATE);
                            i.putExtra(AudioService.EXTRA_IS_PLAYING, "true".equals(isPlayingStr));
                            i.putExtra(AudioService.EXTRA_TITLE,       title);
                            i.putExtra(AudioService.EXTRA_ARTIST,      artist);
                            if (artworkUrl != null && !artworkUrl.isEmpty()) {
                                                i.putExtra(AudioService.EXTRA_ARTWORK_URL, artworkUrl);
                            }
                            try { i.putExtra(AudioService.EXTRA_POSITION, Long.parseLong(positionMs)); }
                            catch (NumberFormatException e) { /* ignore */ }
                            try { i.putExtra(AudioService.EXTRA_DURATION, Long.parseLong(durationMs)); }
                            catch (NumberFormatException e) { /* ignore */ }
                            startService(i);
            }

            /** Backwards-compatible 4-arg overload (called by old JS still in cache). */
            @JavascriptInterface
            public void updatePlayback(String isPlayingStr, String title, String artist, String artworkUrl) {
                updatePlayback(isPlayingStr, title, artist, artworkUrl, "0", "0");
            }

            /** Backwards-compatible 3-arg overload (called by old JS still in cache). */
            @JavascriptInterface
            public void updatePlayback(String isPlayingStr, String title, String artist) {
                updatePlayback(isPlayingStr, title, artist, "", "0", "0");
            }
        }

    // ── BroadcastReceiver: notification buttons → WebView ──────────────────

    private final BroadcastReceiver mediaControlReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                                String action = intent.getAction();
                                if (webView == null) return;

                                // Handle seek from MediaSession seekbar
                                if (AudioService.ACTION_SEEK.equals(action)) {
                                    final long seekPos = intent.getLongExtra(AudioService.EXTRA_SEEK_POS, 0);
                                    final double seekSec = seekPos / 1000.0;
                                    webView.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            webView.evaluateJavascript(
                                                "(function(){var m=document.querySelector('audio,video');" +
                                                "if(m){m.currentTime=" + seekSec + ";}})();", null);
                                        }
                                    });
                                    return;
                                }

                                // Play/pause: directly toggle audio/video element
                                if (AudioService.ACTION_PLAY_PAUSE.equals(action)) {
                                    webView.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            webView.evaluateJavascript(
                                                "(function(){" +
                                                "var els=document.querySelectorAll('audio,video');" +
                                                "var toggled=false;" +
                                                "els.forEach(function(el){" +
                                                "  if(!el.paused&&!el.ended){el.pause();toggled=true;}" +
                                                "});" +
                                                "if(!toggled){els.forEach(function(el){" +
                                                "  el.play().catch(function(){});" +
                                                "});}" +
                                                "if(!toggled&&els.length===0){" +
                                                "  var btn=document.querySelector('[class*=\"play\"],[aria-label*=\"Play\"],[aria-label*=\"Pause\"],[data-testid*=\"play\"],[data-testid*=\"pause\"]');" +
                                                "  if(btn)btn.click();" +
                                                "}" +
                                                "})();", null);
                                        }
                                    });
                                    return;
                                }

                                // Next/prev: use keyboard event + navigator.mediaSession
                                String jsKey;
                                if      (AudioService.ACTION_NEXT.equals(action))       jsKey = "MediaTrackNext";
                                else if (AudioService.ACTION_PREV.equals(action))       jsKey = "MediaTrackPrevious";
                                else return;

                                final String key = jsKey;
                                webView.post(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                                            webView.evaluateJavascript(
                                                                                                            "(function(){" +
                                                                                                            "document.dispatchEvent(new KeyboardEvent('keydown'," +
                                                                                                            "{key:'" + key + "',bubbles:true}));" +
                                                                                                            "try{navigator.mediaSession.playbackState;}" +
                                                                                                            "catch(e){}" +
                                                                                                            "})();", null);
                                                    }
                                });
                }
    };

    // ── Static WebViewClient ───────────────────────────────────────────────

    static class MonoWebViewClient extends WebViewClient {
                private final MainActivity host;
                MonoWebViewClient(MainActivity host) { this.host = host; }

        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                        String url = req.getUrl().toString();
                        if (!url.startsWith("https://monochrome.tf") &&
                                            !url.startsWith("http://monochrome.tf")) {
                                            host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                                            return true;
                        }
                        return false;
        }

        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                        handler.proceed();
        }

        public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        view.evaluateJavascript(INJECT_JS, null);
        }
    }

    // ── Static WebChromeClient ─────────────────────────────────────────────

    static class MonoChromeClient extends WebChromeClient {
                private final MainActivity host;
                MonoChromeClient(MainActivity host) { this.host = host; }

        public void onPermissionRequest(PermissionRequest request) {
                        request.grant(request.getResources());
        }

        public void onGeolocationPermissionsShowPrompt(
                            String origin, GeolocationPermissions.Callback callback) {
                        callback.invoke(origin, true, false);
        }

        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> fileCb,
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

        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                        result.confirm();
                        return false;
        }

        public boolean onConsoleMessage(ConsoleMessage cm) { return true; }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                requestWindowFeature(Window.FEATURE_NO_TITLE);
                getWindow().setFlags(
                                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "monochrome:audio");
                wakeLock.acquire();

        if (Build.VERSION.SDK_INT >= 33) {
                        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") !=
                                                PackageManager.PERMISSION_GRANTED) {
                                            requestPermissions(
                                                                        new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
                        }
        }

        IntentFilter filter = new IntentFilter();
                filter.addAction(AudioService.ACTION_PLAY_PAUSE);
                filter.addAction(AudioService.ACTION_NEXT);
                filter.addAction(AudioService.ACTION_PREV);
                filter.addAction(AudioService.ACTION_SEEK);
                if (Build.VERSION.SDK_INT >= 33) {
                                registerReceiver(mediaControlReceiver, filter, RECEIVER_NOT_EXPORTED);
                } else {
                                registerReceiver(mediaControlReceiver, filter);
                }

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
                webView.addJavascriptInterface(new MonoJSBridge(), "AndroidBridge");
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
                                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                    webView.evaluateJavascript(
                        "(function(){" +
                        "var els=document.querySelectorAll('audio,video');" +
                        "var toggled=false;" +
                        "els.forEach(function(el){" +
                        "  if(!el.paused&&!el.ended){el.pause();toggled=true;}" +
                        "});" +
                        "if(!toggled){els.forEach(function(el){" +
                        "  el.play().catch(function(){});" +
                        "});}" +
                        "})();", null);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                                keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                    String jsKey = (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT)
                        ? "MediaTrackNext" : "MediaTrackPrevious";
                    webView.evaluateJavascript(
                                            "document.dispatchEvent(new KeyboardEvent('keydown'," +
                                            "{key:'" + jsKey + "',bubbles:true}));", null);
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
                // DO NOT call webView.onPause() — keeps audio running in background
    }

    protected void onResume() {
                super.onResume();
                webView.onResume();
                webView.evaluateJavascript(
                                    "(function(){" +
                                    "  try {" +
                                    "    document.querySelectorAll('audio,video').forEach(function(m){" +
                                    "      if (!m.paused) m.play().catch(function(){});" +
                                    "    });" +
                                    "  } catch(e) {}" +
                                    "  window.dispatchEvent(new Event('focus'));" +
                                    "  window.dispatchEvent(new Event('pageshow'));" +
                                    "})();", null);
    }

    protected void onDestroy() {
                unregisterReceiver(mediaControlReceiver);
                stopService(new Intent(this, AudioService.class));
                if (wakeLock != null && wakeLock.isHeld()) {
                                wakeLock.release();
                }
                webView.destroy();
                super.onDestroy();
    }
}
