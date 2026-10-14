package com.remotecontrol.shared

/** Session 10b: limits and presets of the Controller settings screen (same ranges as the viewer's Controls panel). */
object SettingsLogic {
    const val FPS_MIN = 1
    const val FPS_MAX = 30
    const val QUALITY_MIN = 20
    const val QUALITY_MAX = 95
    val SIZES = listOf(480, 720, 1080, 1440)

    const val DEFAULT_FPS = 5
    const val DEFAULT_QUALITY = 60
    const val DEFAULT_SIZE = 720

    fun clampFps(v: Int) = v.coerceIn(FPS_MIN, FPS_MAX)
    fun clampQuality(v: Int) = v.coerceIn(QUALITY_MIN, QUALITY_MAX)

    /** Nearest preset to [v] (ties go to the smaller one). */
    fun snapSize(v: Int): Int = SIZES.minByOrNull { kotlin.math.abs(it - v) } ?: DEFAULT_SIZE

    /** The preset after [v] (wraps around). */
    fun nextSize(v: Int): Int = SIZES[(SIZES.indexOf(snapSize(v)) + 1) % SIZES.size]
}
