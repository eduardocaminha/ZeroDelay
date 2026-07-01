package com.zerodelay.tv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays one live with the hybrid catch-up engine:
 *  - ExoPlayer LiveConfiguration (per mode) does the smooth speed catch-up.
 *  - The ported ZeroDelay layer adds a skip-to-live watchdog + live indicators.
 *  - On playback error we re-extract the HLS URL (it can expire) and retry.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_VIDEO_ID = "videoId"
        const val EXTRA_TITLE = "title"
        private const val OVERLAY_TIMEOUT_MS = 6000L
        private const val TICK_MS = 500L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val ui = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var info: TextView
    private lateinit var modeButton: Button
    private lateinit var liveButton: Button

    private var videoId: String = ""
    private var hlsUrl: String? = null
    private var modeIndex = Modes.DEFAULT_INDEX
    private var reExtracting = false

    // Diagnostics surfaced in the overlay (temporary, to chase a black-screen).
    private var videoW = 0
    private var videoH = 0
    private var firstFrame = false
    private var lastError: String? = null
    private var errorCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            updateInfo()
            watchdogSkip()
            ui.postDelayed(this, TICK_MS)
        }
    }
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        info = findViewById(R.id.info)
        modeButton = findViewById(R.id.modeButton)
        liveButton = findViewById(R.id.liveButton)

        videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        modeButton.setOnClickListener { cycleMode() }
        liveButton.setOnClickListener { goLive() }
        updateModeButton()

        setupPlayer()
        loadStream()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(hideOverlay)
        scope.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }

    // --- Playback -----------------------------------------------------------

    private fun setupPlayer() {
        val p = ExoPlayer.Builder(this).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorCount++
                lastError = "${error.errorCodeName}: ${error.message?.take(90)}"
                onPlaybackError()
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoW = videoSize.width
                videoH = videoSize.height
            }
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }
        })
        playerView.player = p
        playerView.useController = false
        player = p
    }

    private fun loadStream() {
        info.text = getString(R.string.loading)
        overlay.visibility = View.VISIBLE
        scope.launch {
            val url = hlsUrl ?: run {
                val result = withContext(Dispatchers.IO) {
                    runCatching { YouTubeLive.resolveHls(videoId) }.getOrNull()
                }
                if (result?.url == null) {
                    info.text = getString(R.string.stream_error) +
                        (result?.diagnostic?.let { "\n$it" } ?: "")
                    return@launch
                }
                result.url
            }
            hlsUrl = url
            applyMode()
            ui.removeCallbacks(ticker)
            ui.post(ticker)
            scheduleOverlayHide()
        }
    }

    private fun applyMode() {
        val p = player ?: return
        val url = hlsUrl ?: return
        val mode = Modes.LIST[modeIndex]
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8) // googlevideo URLs have no .m3u8 suffix
            .apply { mode.liveConfig()?.let { setLiveConfiguration(it) } }
            .build()
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    private fun onPlaybackError() {
        if (reExtracting || errorCount > 3) return // stop re-extracting so the error stays visible
        reExtracting = true
        info.text = getString(R.string.reconnecting)
        overlay.visibility = View.VISIBLE
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { YouTubeLive.resolveHls(videoId) }.getOrNull()
            }
            reExtracting = false
            if (result?.url != null) {
                hlsUrl = result.url
                applyMode()
                scheduleOverlayHide()
            } else {
                info.text = getString(R.string.stream_error) +
                    (result?.diagnostic?.let { "\n$it" } ?: "")
            }
        }
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

    private fun goLive() {
        player?.seekToDefaultPosition()
        scheduleOverlayHide()
    }

    private fun watchdogSkip() {
        val p = player ?: return
        val mode = Modes.LIST[modeIndex]
        if (!mode.skip) return
        val off = p.currentLiveOffset
        if (off != C.TIME_UNSET && off > mode.skipThresholdMs) p.seekToDefaultPosition()
    }

    private fun updateInfo() {
        val p = player ?: return
        val off = p.currentLiveOffset
        val latency = if (off == C.TIME_UNSET) "--" else String.format("%.1fs", off / 1000.0)
        val buffered = p.totalBufferedDuration / 1000.0
        val speed = p.playbackParameters.speed
        val state = when (p.playbackState) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            Player.STATE_ENDED -> "ended"
            else -> "?"
        }
        val diag = "estado=$state play=${p.isPlaying} vídeo=${videoW}x$videoH frame=${if (firstFrame) "sim" else "não"}"
        val errLine = lastError?.let { "\nerr#$errorCount $it" } ?: ""
        info.text = getString(R.string.info_fmt, latency, buffered, speed) + "\n" + diag + errLine
    }

    // --- Overlay / D-pad ----------------------------------------------------

    private fun showOverlay() {
        overlay.visibility = View.VISIBLE
        modeButton.requestFocus()
        scheduleOverlayHide()
    }

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
                        showOverlay()
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
