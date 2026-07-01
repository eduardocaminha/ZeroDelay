package com.zerodelay.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject

/**
 * Plays a live by loading the real YouTube watch page in a WebView and injecting
 * the ZeroDelay catch-up engine (a port of engine/controller.js + inject.js).
 *
 * Why the watch page and not an embed: CazéTV disables third-party embedding
 * (IFrame error 152), and direct HLS extraction is PoToken-gated (segment 403,
 * see android/spike/FINDINGS.md). The real watch page is the only context that
 * both plays (it mints its own PoToken) and exposes the private player API
 * (getStatsForNerds / setPlaybackRate / seekToLiveHead) the engine needs.
 *
 * A desktop User-Agent gets the full desktop player; a consent cookie avoids the
 * interstitial. Risk: YouTube may still gate a WebView with a bot check / sign-in.
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_VIDEO_ID = "videoId"
        const val EXTRA_TITLE = "title"
        private const val OVERLAY_TIMEOUT_MS = 6000L
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var overlay: View
    private lateinit var info: TextView
    private lateinit var modeButton: Button
    private lateinit var liveButton: Button

    private var videoId: String = ""
    private var modeIndex = Modes.DEFAULT_INDEX
    private var injected = false

    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        web = findViewById(R.id.playerWeb)
        overlay = findViewById(R.id.overlay)
        info = findViewById(R.id.info)
        modeButton = findViewById(R.id.modeButton)
        liveButton = findViewById(R.id.liveButton)

        videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        modeButton.setOnClickListener { cycleMode() }
        liveButton.setOnClickListener { goLive() }
        updateModeButton()

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.userAgentString = DESKTOP_UA
        web.addJavascriptInterface(Bridge(), "Android")

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setCookie("https://www.youtube.com", "SOCS=CAI")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                inject()
                // Re-inject once after the SPA settles, in case the player wasn't
                // in the DOM at page-finished. The engine guards double-install.
                ui.postDelayed({ inject() }, 4000)
            }
        }

        info.text = getString(R.string.loading)
        overlay.visibility = View.VISIBLE
        web.loadUrl("https://www.youtube.com/watch?v=$videoId")
    }

    override fun onStop() {
        super.onStop()
        web.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(hideOverlay)
        web.destroy()
        super.onDestroy()
    }

    private fun inject() {
        web.evaluateJavascript(ENGINE_JS, null)
        injected = true
        applyMode()
    }

    // --- JS bridge ----------------------------------------------------------

    inner class Bridge {
        @JavascriptInterface
        fun onStats(json: String) {
            ui.post { showStats(json) }
        }
    }

    private fun showStats(json: String) {
        val s = runCatching { JSONObject(json) }.getOrNull() ?: return
        val isLive = s.optBoolean("isLive", false)
        val lat = s.optDouble("lat", Double.NaN)
        val health = s.optDouble("health", Double.NaN)
        val rate = s.optDouble("rate", 1.0)
        val err = s.optString("err", "")
        val latStr = if (lat.isNaN()) "--" else String.format("%.1fs", lat)
        val hStr = if (health.isNaN()) "--" else String.format("%.1fs", health)
        val line1 = "Latência: $latStr  ·  Buffer: $hStr  ·  Velocidade: ${String.format("%.2f", rate)}x"
        val line2 = "live=$isLive" + if (err.isNotEmpty()) "  err=$err" else ""
        info.text = "$line1\n$line2"
    }

    // --- Modes / live -------------------------------------------------------

    private fun cycleMode() {
        modeIndex = (modeIndex + 1) % Modes.LIST.size
        updateModeButton()
        applyMode()
        scheduleOverlayHide()
    }

    private fun updateModeButton() {
        modeButton.text = getString(R.string.mode_prefix, getString(Modes.LIST[modeIndex].labelRes))
    }

    private fun applyMode() {
        if (!injected) return
        val m = Modes.LIST[modeIndex]
        web.evaluateJavascript(
            "window.zd&&window.zd.setMode(${m.enabled},${m.auto},${m.bufferTarget},${m.speed},${m.skip},${m.skipThresholdSec})",
            null,
        )
    }

    private fun goLive() {
        web.evaluateJavascript("window.zd&&window.zd.goLive()", null)
        scheduleOverlayHide()
    }

    // --- Overlay / D-pad ----------------------------------------------------

    private fun scheduleOverlayHide() {
        ui.removeCallbacks(hideOverlay)
        ui.postDelayed(hideOverlay, OVERLAY_TIMEOUT_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (overlay.visibility != View.VISIBLE) {
                        overlay.visibility = View.VISIBLE
                        modeButton.requestFocus()
                        scheduleOverlayHide()
                        return true
                    }
                    scheduleOverlayHide()
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (overlay.visibility == View.VISIBLE) {
                        overlay.visibility = View.GONE
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

// Injected catch-up engine: a faithful port of engine/controller.js (EMA +
// hysteresis) and the inject.js loop, driving the real watch-page player.
private const val ENGINE_JS = """
(function(){
  if(window.__zd_installed) return; window.__zd_installed=true;
  var BUFFER_FLOOR=1.5,BUFFER_BACKOFF=2.5,BUFFER_RESUME=4.0,CATCH_UP_BAND=1.5,MIN_LATENCY=2.0;
  var buffer_headroom_ok=true,buffer_ema=null,catching_up=false,auto_target=6.0,auto_cooldown=0;
  var applied_rate=1.0,yielded=false;
  var mode={enabled:true,auto:true,bufferTarget:6.0,speed:1.25,skip:true,skipAt:30.0};
  var st={isLive:false,lat:null,health:null,rate:1.0,err:''};
  function accel_ok(h){ if(!isFinite(h))return false; if(h<=BUFFER_BACKOFF)buffer_headroom_ok=false; else if(h>=BUFFER_RESUME)buffer_headroom_ok=true; return buffer_headroom_ok; }
  function auto_buf(h){ if(isFinite(h)&&h<1.0){auto_target=Math.min(9.0,auto_target+1.0);auto_cooldown=240;} else if(auto_cooldown>0){auto_cooldown--;} else if(buffer_ema!==null&&buffer_ema>auto_target+2.0){auto_target=Math.max(4.0,auto_target-0.01);} return auto_target; }
  function calcRate(speed,lat,h,target,auto){ if(!isFinite(h)||!isFinite(lat))return 1.0; buffer_ema=(buffer_ema===null)?h:(buffer_ema*0.9+h*0.1); if(lat<MIN_LATENCY)return 1.0; var t=auto?auto_buf(h):target; if(buffer_ema>t+CATCH_UP_BAND)catching_up=true; else if(buffer_ema<=t)catching_up=false; if(!catching_up)return 1.0; if(h<BUFFER_FLOOR||!accel_ok(h))return 1.0; return speed; }
  function P(){ return document.getElementById('movie_player'); }
  function applyRate(pl,desired){ if(!pl.setPlaybackRate||!pl.getPlaybackRate)return; var cur=pl.getPlaybackRate(); if(Math.abs(cur-applied_rate)>0.01){ if(Math.abs(cur-1.0)<0.01){applied_rate=1.0;yielded=false;} else {yielded=true;applied_rate=cur;} } if(yielded)return; if(Math.abs(desired-applied_rate)>0.01){pl.setPlaybackRate(desired);applied_rate=desired;} }
  var kicked=false;
  function tick(){
    try{
      var pl=P();
      if(pl){
        if(!kicked&&pl.playVideo){ pl.playVideo(); kicked=true; }
        if(pl.getStatsForNerds){
          var s=pl.getStatsForNerds();
          if(s&&s.live_latency_style===''){
            var lat=parseFloat(s.live_latency_secs), h=parseFloat(s.buffer_health_seconds);
            st.isLive=true; st.lat=lat; st.health=h; st.err='';
            var ps=pl.getPlayerStateObject?pl.getPlayerStateObject():null;
            if(mode.enabled){ applyRate(pl, calcRate(mode.speed,lat,h, mode.auto?0:mode.bufferTarget, mode.auto)); }
            else { applyRate(pl,1.0); }
            if(mode.skip&&pl.seekToLiveHead&&isFinite(lat)&&lat>=mode.skipAt){ if(!ps||ps.isPlaying){ pl.seekToLiveHead(); if(pl.playVideo)pl.playVideo(); } }
            if(pl.getPlaybackRate) st.rate=pl.getPlaybackRate();
          } else { st.isLive=false; }
        }
      }
    }catch(e){ st.err=(''+e).slice(0,70); }
    try{ Android.onStats(JSON.stringify(st)); }catch(e){}
    setTimeout(tick,250);
  }
  window.zd={
    setMode:function(en,auto,bt,sp,skip,skipAt){ mode={enabled:en,auto:auto,bufferTarget:bt,speed:sp,skip:skip,skipAt:skipAt}; },
    goLive:function(){ try{ var pl=P(); if(pl&&pl.seekToLiveHead){ pl.seekToLiveHead(); if(pl.playVideo)pl.playVideo(); } }catch(e){} }
  };
  tick();
})();
"""
