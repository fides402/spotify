package com.monochrome.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
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

    // ── HOOK_JS: injected at onPageStarted (before page scripts run) ─────────
    // Hooks MediaSession.prototype so we capture Spotify's action handlers
    // and artwork metadata as soon as they are registered, race-free.
    static final String HOOK_JS = "(function(){" +
            "if(window._mcHooksInstalled)return;" +
            "window._mcHooksInstalled=true;" +
            "window._mcHandlers={};" +
            "window._capturedArtUrl='';" +
            // Intercept setActionHandler to store Spotify's callbacks
            "try{" +
            "  var _oSAH=MediaSession.prototype.setActionHandler;" +
            "  MediaSession.prototype.setActionHandler=function(a,h){" +
            "    _oSAH.call(this,a,h);" +
            "    if(this===navigator.mediaSession)window._mcHandlers[a]=h;" +
            "  };" +
            "}catch(e){}" +
            // Intercept metadata setter to capture artwork directly from the value
            "try{" +
            "  var _d=Object.getOwnPropertyDescriptor(MediaSession.prototype,'metadata');" +
            "  if(_d&&_d.set){" +
            "    var _oS=_d.set;" +
            "    Object.defineProperty(MediaSession.prototype,'metadata',{" +
            "      get:_d.get," +
            "      set:function(v){" +
            "        _oS.call(this,v);" +
            "        if(v&&v.artwork&&v.artwork.length>0){" +
            "          var b='',bs=-1;" +
            "          for(var i=0;i<v.artwork.length;i++){" +
            "            var a=v.artwork[i];" +
            "            if(!a||!a.src)continue;" +
            "            var sz=a.sizes?parseInt(a.sizes)||0:0;" +
            "            if(sz>bs){b=a.src;bs=sz;}" +
            "          }" +
            "          if(b)window._capturedArtUrl=b;" +
            "        }else{window._capturedArtUrl='';}" +
            "        if(window._mcSnapNow)setTimeout(window._mcSnapNow,100);" +
            "      }," +
            "      configurable:true" +
            "    });" +
            "  }" +
            "}catch(e){}" +
            // Helper: invoke one of Spotify's registered action handlers
            "window._mcCallHandler=function(action){" +
            "  var h=window._mcHandlers[action];" +
            "  if(h){try{h({});return true;}catch(e){}}" +
            "  return false;" +
            "};" +
            "})();";

    // ── INJECT_JS: injected at onPageFinished (DOM is ready) ─────────────────
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
            " (function(){" +
            "   if (window._mcBridgeInit) return;" +
            "   window._mcBridgeInit = true;" +
            "   var lastPlaying = null, lastTitle = '', lastArtist = '', lastArt = '';" +

            "   function getArtworkUrl() {" +
            // Primary: artwork captured by HOOK_JS from MediaSession setter (race-free)
            "     if (window._capturedArtUrl) return window._capturedArtUrl;" +
            // Secondary: live read from navigator.mediaSession.metadata
            "     try {" +
            "       var meta = navigator.mediaSession && navigator.mediaSession.metadata;" +
            "       if (meta && meta.artwork && meta.artwork.length > 0) {" +
            "         var best = ''; var bestSize = -1;" +
            "         for (var i = 0; i < meta.artwork.length; i++) {" +
            "           var a = meta.artwork[i];" +
            "           if (!a || !a.src) continue;" +
            "           var sz = a.sizes ? parseInt(a.sizes) || 0 : 0;" +
            "           if (sz > bestSize) { best = a.src; bestSize = sz; }" +
            "         }" +
            "         if (best) return best;" +
            "       }" +
            "     } catch(e) {}" +
            // Tertiary: largest square-ish <img> on page (heuristic for album art)
            "     var best = ''; var bestScore = 0;" +
            "     document.querySelectorAll('img').forEach(function(img) {" +
            "       if (!img.src || !img.complete || img.naturalWidth < 80) return;" +
            "       var ratio = img.naturalWidth / Math.max(img.naturalHeight, 1);" +
            "       if (ratio < 0.5 || ratio > 2.0) return;" +
            "       var score = img.naturalWidth * img.naturalHeight;" +
            "       if (score > bestScore) { best = img.src; bestScore = score; }" +
            "     });" +
            "     return best;" +
            "   }" +

            "   function report(playing, title, artist, artUrl, pos, dur) {" +
            "     title  = title  || '';" +
            "     artist = artist || '';" +
            "     artUrl = artUrl || '';" +
            "     pos = pos || 0; dur = dur || 0;" +
            "     if (playing === lastPlaying && title === lastTitle &&" +
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

            "   function snapNow() {" +
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
            "   }" +
            // Expose snapNow so HOOK_JS can trigger it when metadata changes
            "   window._mcSnapNow = snapNow;" +

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

            "   setInterval(snapNow, 1000);" +
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
            i.putExtra(AudioService.EXTRA_TITLE, title);
            i.putExtra(AudioService.EXTRA_ARTIST, artist);
            if (artworkUrl != null && !artworkUrl.isEmpty()) {
                i.putExtra(AudioService.EXTRA_ARTWORK_URL, artworkUrl);
            }
            try {
                i.putExtra(AudioService.EXTRA_POSITION, Long.parseLong(positionMs));
            } catch (NumberFormatException e) {
                /* ignore */ }
            try {
                i.putExtra(AudioService.EXTRA_DURATION, Long.parseLong(durationMs));
            } catch (NumberFormatException e) {
                /* ignore */ }
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
            if (webView == null)
                return;

            // Handle seek from MediaSession seekbar
            if (AudioService.ACTION_SEEK.equals(action)) {
                final long seekPos = intent.getLongExtra(AudioService.EXTRA_SEEK_POS, 0);
                final double seekSec = seekPos / 1000.0;
                webView.post(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript(
                                "(function(){var m=document.querySelector('audio,video');" +
                                        "if(m){m.currentTime=" + seekSec + ";}})();",
                                null);
                    }
                });
                return;
            }

            // Play/pause: try MediaSession handler first, then audio element
            if (AudioService.ACTION_PLAY_PAUSE.equals(action)) {
                webView.post(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript(
                                "(function(){" +
                        // Strategy 1: call Spotify's own registered MediaSession handler
                                        "  if(window._mcCallHandler){" +
                                        "    var playing=false;" +
                                        "    document.querySelectorAll('audio,video').forEach(function(e){" +
                                        "      if(!e.paused&&!e.ended)playing=true;" +
                                        "    });" +
                                        "    if(window._mcCallHandler(playing?'pause':'play'))return;" +
                                        "  }" +
                        // Strategy 2: direct audio element control
                                        "  var els=document.querySelectorAll('audio,video');" +
                                        "  var toggled=false;" +
                                        "  els.forEach(function(el){" +
                                        "    if(!el.paused&&!el.ended){el.pause();toggled=true;}" +
                                        "  });" +
                                        "  if(!toggled)els.forEach(function(el){el.play().catch(function(){});});" +
                        // Strategy 3: click play/pause button in DOM
                                        "  if(!toggled&&els.length===0){" +
                                        "    var b=document.querySelector('[data-testid*=\"play\"],[data-testid*=\"pause\"],"
                                        +
                                        "      [aria-label*=\"Play\"],[aria-label*=\"Pause\"]," +
                                        "      [aria-label*=\"Riproduci\"],[aria-label*=\"Pausa\"]');" +
                                        "    if(b)b.click();" +
                                        "  }" +
                                        "})();",
                                null);
                    }
                });
                return;
            }

            // Next/prev: 4-strategy relay to the web app
            final boolean isNext = AudioService.ACTION_NEXT.equals(action);
            if (!isNext && !AudioService.ACTION_PREV.equals(action))
                return;
            webView.post(new Runnable() {
                @Override
                public void run() {
                    String action = isNext ? "nexttrack" : "previoustrack";
                    String kbKey = isNext ? "MediaTrackNext" : "MediaTrackPrevious";
                    int kbCode = isNext ? 176 : 177;
                    String nameSel = isNext
                            ? "[data-testid*='next'],[data-testid*='skip-forward']," +
                                    "[aria-label*='Next'],[aria-label*='next']," +
                                    "[aria-label*='Successivo'],[aria-label*='Avanti']"
                            : "[data-testid*='prev'],[data-testid*='skip-back']," +
                                    "[aria-label*='Prev'],[aria-label*='prev']," +
                                    "[aria-label*='Precedente'],[aria-label*='Indietro']";
                    webView.evaluateJavascript(
                            "(function(){" +
                    // Strategy 1: call Spotify's own registered MediaSession handler
                                    "  if(window._mcCallHandler&&window._mcCallHandler('" + action + "'))return;" +
                    // Strategy 2: keyboard events on both document and window
                                    "  var k=new KeyboardEvent('keydown',{key:'" + kbKey + "',keyCode:" + kbCode + "," +
                                    "    which:" + kbCode + ",bubbles:true,cancelable:true});" +
                                    "  document.dispatchEvent(k);window.dispatchEvent(k);" +
                    // Strategy 3: click named next/prev button
                                    "  var b=document.querySelector('" + nameSel + "');" +
                                    "  if(b){b.click();return;}" +
                    // Strategy 4: find play button and click its sibling
                                    "  var pb=document.querySelector('[data-testid*=\"play\"],[data-testid*=\"pause\"],"
                                    +
                                    "    [aria-label*=\"Play\"],[aria-label*=\"Pause\"]," +
                                    "    [aria-label*=\"Riproduci\"],[aria-label*=\"Pausa\"]');" +
                                    "  if(pb&&pb.parentElement){" +
                                    "    var btns=Array.from(pb.parentElement.querySelectorAll('button,[role=\"button\"]'));"
                                    +
                                    "    var idx=btns.indexOf(pb);" +
                                    "    if(idx>=0){var t=btns[" + (isNext ? "idx+1" : "idx-1") + "];if(t)t.click();}" +
                                    "  }" +
                                    "})();",
                            null);
                }
            });
        }
    };

    // ── Static WebViewClient ───────────────────────────────────────────────

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

        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.proceed();
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            // Install prototype hooks BEFORE page scripts run so we catch
            // Spotify's setActionHandler and metadata setter registrations.
            view.evaluateJavascript(HOOK_JS, null);
        }

        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // Re-run HOOK_JS in case onPageStarted fired too early,
            // then run the main bridge (guarded by _mcBridgeInit).
            view.evaluateJavascript(HOOK_JS, null);
            view.evaluateJavascript(INJECT_JS, null);
        }
    }

    // ── Static WebChromeClient ─────────────────────────────────────────────

    static class MonoChromeClient extends WebChromeClient {
        private final MainActivity host;

        MonoChromeClient(MainActivity host) {
            this.host = host;
        }

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

        public boolean onConsoleMessage(ConsoleMessage cm) {
            return true;
        }
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
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[] { "android.permission.POST_NOTIFICATIONS" }, 100);
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

        // Removed AudioManager focus request in onCreate so this app doesn't interrupt
        // other playing media

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
                            "  if(window._mcCallHandler){" +
                            "    var p=false;" +
                            "    document.querySelectorAll('audio,video').forEach(function(e){if(!e.paused&&!e.ended)p=true;});"
                            +
                            "    if(window._mcCallHandler(p?'pause':'play'))return;" +
                            "  }" +
                            "  var els=document.querySelectorAll('audio,video'),t=false;" +
                            "  els.forEach(function(el){if(!el.paused&&!el.ended){el.pause();t=true;}});" +
                            "  if(!t)els.forEach(function(el){el.play().catch(function(){});});" +
                            "})();",
                    null);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            final boolean isNext = (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT);
            String msAction = isNext ? "nexttrack" : "previoustrack";
            String kbKey = isNext ? "MediaTrackNext" : "MediaTrackPrevious";
            int kbCode = isNext ? 176 : 177;
            String nameSel = isNext
                    ? "[data-testid*='next'],[data-testid*='skip-forward']," +
                            "[aria-label*='Next'],[aria-label*='next'],[aria-label*='Successivo']"
                    : "[data-testid*='prev'],[data-testid*='skip-back']," +
                            "[aria-label*='Prev'],[aria-label*='prev'],[aria-label*='Precedente']";
            webView.evaluateJavascript(
                    "(function(){" +
                            "  if(window._mcCallHandler&&window._mcCallHandler('" + msAction + "'))return;" +
                            "  var k=new KeyboardEvent('keydown',{key:'" + kbKey + "',keyCode:" + kbCode + "," +
                            "    which:" + kbCode + ",bubbles:true,cancelable:true});" +
                            "  document.dispatchEvent(k);window.dispatchEvent(k);" +
                            "  var b=document.querySelector('" + nameSel + "');" +
                            "  if(b){b.click();return;}" +
                            "  var pb=document.querySelector('[data-testid*=\"play\"],[data-testid*=\"pause\"]," +
                            "    [aria-label*=\"Play\"],[aria-label*=\"Pause\"]," +
                            "    [aria-label*=\"Riproduci\"],[aria-label*=\"Pausa\"]');" +
                            "  if(pb&&pb.parentElement){" +
                            "    var btns=Array.from(pb.parentElement.querySelectorAll('button,[role=\"button\"]'));" +
                            "    var idx=btns.indexOf(pb);" +
                            "    if(idx>=0){var t=btns[" + (isNext ? "idx+1" : "idx-1") + "];if(t)t.click();}" +
                            "  }" +
                            "})();",
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
                    results = new Uri[] { data.getData() };
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    protected void onPause() {
        super.onPause();
        // DO NOT call webView.onPause() — keeps audio running in background for
        // antigravity audio focus fix!
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
                        "})();",
                null);

        // Removed AudioManager focus request in onResume so this app doesn't interrupt
        // other playing media
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
