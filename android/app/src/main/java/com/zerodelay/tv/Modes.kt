package com.zerodelay.tv

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi

/**
 * One catch-up mode. Hybrid engine: ExoPlayer's LiveConfiguration does the
 * smooth speed catch-up toward `targetOffsetMs` (how far behind the live edge we
 * aim to play; smaller = closer to live = needs a better connection), bounded by
 * the speed band. The ported ZeroDelay layer adds skip-to-live on top (see
 * PlayerActivity). Mirrors the extension's presets in common.js.
 */
data class Mode(
    val id: String,
    val labelRes: Int,
    val targetOffsetMs: Long, // 0 = don't override the stream's own live offset
    val minSpeed: Float,
    val maxSpeed: Float,
    val skip: Boolean,
    val skipThresholdMs: Long = 30_000L,
) {
    @OptIn(UnstableApi::class)
    fun liveConfig(): MediaItem.LiveConfiguration? {
        if (targetOffsetMs <= 0L && minSpeed == 1f && maxSpeed == 1f) return null
        return MediaItem.LiveConfiguration.Builder().apply {
            if (targetOffsetMs > 0L) setTargetOffsetMs(targetOffsetMs)
            setMinPlaybackSpeed(minSpeed)
            setMaxPlaybackSpeed(maxSpeed)
        }.build()
    }
}

object Modes {
    // Same order/identity as the ZeroDelay extension. Index 1 (Automático) is
    // the default. Off = plain playback, no catch-up, no skip.
    val LIST: List<Mode> = listOf(
        Mode("off", R.string.mode_off, 0L, 1f, 1f, skip = false),
        Mode("auto", R.string.mode_auto, 6000L, 0.94f, 1.25f, skip = true),
        Mode("suave", R.string.mode_suave, 8000L, 0.97f, 1.10f, skip = true),
        Mode("balanced", R.string.mode_balanced, 6000L, 0.96f, 1.25f, skip = true),
        Mode("aggressive", R.string.mode_aggressive, 4500L, 0.95f, 1.40f, skip = true),
        Mode("min", R.string.mode_min, 3500L, 0.94f, 1.50f, skip = true),
    )

    const val DEFAULT_INDEX = 1 // Automático
}
