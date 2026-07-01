package com.zerodelay.tv

/**
 * Catch-up mode, mirroring the ZeroDelay extension presets (common.js). Fed to
 * the injected engine, which is a port of engine/controller.js: it speeds up
 * (up to `speed`) while the smoothed buffer sits above `bufferTarget`, rests at
 * 1.0x otherwise, and `auto` lets the engine adapt the target to the connection.
 */
data class Mode(
    val id: String,
    val labelRes: Int,
    val enabled: Boolean,
    val auto: Boolean,
    val bufferTarget: Double,
    val speed: Double,
    val skip: Boolean,
    val skipThresholdSec: Double,
)

object Modes {
    val LIST: List<Mode> = listOf(
        Mode("off", R.string.mode_off, enabled = false, auto = false, bufferTarget = 6.0, speed = 1.25, skip = false, skipThresholdSec = 30.0),
        Mode("auto", R.string.mode_auto, enabled = true, auto = true, bufferTarget = 6.0, speed = 1.25, skip = true, skipThresholdSec = 30.0),
        Mode("suave", R.string.mode_suave, enabled = true, auto = false, bufferTarget = 8.0, speed = 1.25, skip = true, skipThresholdSec = 30.0),
        Mode("balanced", R.string.mode_balanced, enabled = true, auto = false, bufferTarget = 6.0, speed = 1.25, skip = true, skipThresholdSec = 30.0),
        Mode("aggressive", R.string.mode_aggressive, enabled = true, auto = false, bufferTarget = 4.5, speed = 1.25, skip = true, skipThresholdSec = 30.0),
        Mode("min", R.string.mode_min, enabled = true, auto = false, bufferTarget = 3.5, speed = 1.25, skip = true, skipThresholdSec = 30.0),
    )

    const val DEFAULT_INDEX = 1 // Automático
}
