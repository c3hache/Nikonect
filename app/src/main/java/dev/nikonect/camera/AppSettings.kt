package dev.nikonect.camera

import android.content.SharedPreferences

enum class GridMode(val label: String) { NONE("关闭"), THIRDS("九宫格"), QUARTERS("四等分"), GOLDEN("黄金分割"), CROSS("中心十字") }
data class AppSettings(
    val targetFps: Int = 30,
    val previewSize: Int = 960,
    val histogram: Boolean = true,
    val fillPreview: Boolean = false,
    val grid: GridMode = GridMode.THIRDS,
    val autoImport: Boolean = true,
    val importNef: Boolean = false,
    val prioritizeLive: Boolean = true,
    val pollSeconds: Int = 2,
    val keepScreenOn: Boolean = true,
    val immersive: Boolean = true,
    val autoStartLive: Boolean = false,
    val landscapeMonitor: Boolean = false,
    val pauseInBackground: Boolean = true,
    val experimentalLive: Boolean = false
) {
    fun save(p: SharedPreferences) { p.edit().apply {
        putInt("fps", targetFps); putInt("size", previewSize); putBoolean("histogram", histogram); putBoolean("fill", fillPreview); putString("grid", grid.name)
        putBoolean("autoImport", autoImport); putBoolean("nef", importNef); putBoolean("priority", prioritizeLive); putInt("poll", pollSeconds)
        putBoolean("screen", keepScreenOn); putBoolean("immersive", immersive); putBoolean("autoStart", autoStartLive); putBoolean("landscape", landscapeMonitor); putBoolean("background", pauseInBackground); putBoolean("experimental", experimentalLive)
    }.apply() }
    companion object {
        fun load(p: SharedPreferences) = AppSettings(
            targetFps = p.getInt("fps", 30).takeIf { it in listOf(15, 24, 30) } ?: 30,
            previewSize = p.getInt("size", 960).takeIf { it in listOf(640, 960, 1280, 1600) } ?: 960,
            histogram = p.getBoolean("histogram", true), fillPreview = p.getBoolean("fill", false),
            grid = GridMode.entries.firstOrNull { it.name == p.getString("grid", "THIRDS") } ?: GridMode.THIRDS,
            autoImport = p.getBoolean("autoImport", true), importNef = p.getBoolean("nef", false),
            prioritizeLive = p.getBoolean("priority", true), pollSeconds = p.getInt("poll", 2).coerceIn(2, 10),
            keepScreenOn = p.getBoolean("screen", true), immersive = p.getBoolean("immersive", true),
            autoStartLive = p.getBoolean("autoStart", false), landscapeMonitor = p.getBoolean("landscape", false),
            pauseInBackground = p.getBoolean("background", true), experimentalLive = p.getBoolean("experimental", false),
        )
    }
}

/** The filter is evaluated before GetObject; turning NEF off prevents transferring its bytes. */
object ImportPolicy {
    fun accept(filename: String, nef: Boolean): Boolean = when (filename.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> true
        "nef" -> nef
        else -> false
    }
    fun isJpeg(filename: String) = filename.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")
    fun deferForLive(live: Boolean, priority: Boolean) = live && priority
}

/** Monotonic pacing subtracts USB time; never adds a fixed sleep after every frame. */
object FramePacing {
    fun remainingMillis(startNanos: Long, endNanos: Long, fps: Int): Long =
        ((1_000_000_000L / fps.coerceIn(1, 60) - (endNanos - startNanos).coerceAtLeast(0)) / 1_000_000L).coerceAtLeast(0)
}



