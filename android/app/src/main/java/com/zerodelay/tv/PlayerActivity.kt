package com.zerodelay.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject

/**
 * Plays a live via the YouTube IFrame player inside a WebView. The embedded
 * player is the real YouTube player, so it carries the PoToken that direct HLS
 * extraction can't (googlevideo 403s naked segment requests; see
 * android/spike/FINDINGS.md). The ZeroDelay catch-up runs as injected JS: it
 * speeds up toward the mode's target latency and skips to the live edge.
 *
 * Decisive unknown this build answers: whether the IFrame API allows a playback
 * rate > 1 on a LIVE stream. The overlay shows `avail=[...]` (the rates YouTube
 * offers). If it is only [1], embed catch-up is impossible and we pivot to
 * injecting into the full watch page instead.
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_VIDEO_ID = "videoId"
        const val EXTRA_TITLE = "title"
        private const val OVERLAY_TIMEOUT_MS = 6000L
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var overlay: View
    private lateinit var info: TextView
    private lateinit var modeButton: Button
    private lateinit var liveButton: Button

    private var videoId: String = ""
    private var modeIndex = Modes.DEFAULT_INDEX

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
        web.addJavascriptInterface(Bridge(), "Android")

        info.text = getString(R.string.loading)
        overlay.visibility = View.VISIBLE
        // Base URL youtube.com so the IFrame API's postMessage origin checks pass.
        web.loadDataWithBaseURL("https://www.youtube.com", buildHtml(videoId), "text/html", "utf-8", null)
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

    // --- JS bridge ----------------------------------------------------------

    inner class Bridge {
        @JavascriptInterface
        fun onStats(json: String) {
            ui.post { showStats(json) }
        }
    }

    private fun showStats(json: String) {
        val s = runCatching { JSONObject(json) }.getOrNull() ?: return
        val lat = s.optDouble("lat", Double.NaN)
        val rate = s.optDouble("rate", 1.0)
        val ps = s.optInt("ps", -99)
        val avail = s.optString("avail", "?")
        val err = s.optInt("err", 0)
        val latStr = if (lat.isNaN()) "--" else String.format("%.1fs", lat)
        val stateStr = when (ps) {
            -1 -> "unstarted"; 0 -> "ended"; 1 -> "playing"; 2 -> "paused"
            3 -> "buffering"; 5 -> "cued"; else -> "?"
        }
        val line1 = "Latência: $latStr  ·  Velocidade: ${String.format("%.2f", rate)}x"
        val line2 = "estado=$stateStr avail=$avail" + if (err != 0) " ERRO=$err" else ""
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
        val m = Modes.LIST[modeIndex]
        val target = m.targetOffsetMs / 1000.0
        val skipAt = m.skipThresholdMs / 1000.0
        js("window.zd && window.zd.setMode($target, ${m.maxSpeed}, ${m.skip}, $skipAt)")
    }

    private fun goLive() {
        js("window.zd && window.zd.goLive()")
        scheduleOverlayHide()
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

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

    // --- Embedded page ------------------------------------------------------

    // Default mode baked in matches Modes.DEFAULT_INDEX (Automático): target 6s,
    // max rate 1.25, skip at 30s. Kotlin re-sends on mode change.
    private fun buildHtml(vid: String): String = HTML_TEMPLATE.replace("__VID__", vid)
}

private const val HTML_TEMPLATE = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style>
</head><body>
<div id="p"></div>
<script>
var VIDEO_ID="__VID__";
var player, ready=false;
var mode={target:6, maxRate:1.25, skip:true, skipAt:30};
var st={rate:1, cur:0, dur:0, lat:null, ps:-1, avail:"?", err:0};
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{
    videoId:VIDEO_ID,
    playerVars:{autoplay:1, controls:0, rel:0, playsinline:1, modestbranding:1, fs:0},
    events:{
      onReady:function(){
        ready=true;
        try{player.playVideo();}catch(e){}
        try{st.avail=JSON.stringify(player.getAvailablePlaybackRates());}catch(e){}
        loop();
      },
      onStateChange:function(e){st.ps=e.data;},
      onError:function(e){st.err=e.data;}
    }
  });
}
function loop(){
  try{
    st.cur=player.getCurrentTime();
    st.dur=player.getDuration();
    st.rate=player.getPlaybackRate();
    st.lat=Math.max(0, st.dur-st.cur);
    if(mode.skip && st.lat>mode.skipAt){
      player.seekTo(st.dur, true);
    } else if(st.lat > mode.target+1.5){
      if(Math.abs(st.rate-mode.maxRate)>0.01) player.setPlaybackRate(mode.maxRate);
    } else if(st.lat <= mode.target){
      if(Math.abs(st.rate-1)>0.01) player.setPlaybackRate(1);
    }
  }catch(e){}
  try{Android.onStats(JSON.stringify(st));}catch(e){}
  setTimeout(loop, 500);
}
window.zd={
  setMode:function(t,m,skip,skipAt){mode={target:t, maxRate:m, skip:skip, skipAt:skipAt};},
  goLive:function(){try{player.seekTo(player.getDuration(), true);}catch(e){}}
};
var s=document.createElement('script');
s.src="https://www.youtube.com/iframe_api";
document.head.appendChild(s);
</script>
</body></html>
"""
