package dev.nikonect.camera

import dev.nikonect.ptp.CameraProperty

/** Standard ISO is the user setting; Nikon ISOControlSensitivity is a distinct readback. */
object CameraIso {
    val properties = listOf(0xD054 to "自动 ISO", 0xD0B5 to "感光度控制值")
    fun automatic(properties: List<CameraProperty>): Boolean? {
        val value = properties.firstOrNull { it.code == 0xD054 }?.current?.value ?: return null
        return when (value) { 0L -> false; 1L -> true; else -> null }
    }
    fun display(properties: List<CameraProperty>, fresh: Boolean = true): CameraProperty? {
        val setting = properties.find { it.code == 0x500F } ?: return null
        if (automatic(properties) != true) return setting
        val measured = properties.find { it.code == 0xD0B5 && !it.writable }?.current?.value
            ?.takeIf { fresh && it in 25..3_276_800 }
        return setting.copy(writable = false, displayOverride = "AUTO ${measured ?: "—"}")
    }
}

val movieCodes = mapOf(0x5007 to 0xD1A9, 0x500D to 0xD1A8, 0x500F to 0xD1AA, 0x5010 to 0xD1AB, 0x5005 to 0xD23A)
val CameraState.movieMode get() = properties.any { it.code == 0xD1A6 && it.current.value == 1L }
fun CameraState.property(code: Int): CameraProperty? = if (movieMode && code in movieCodes) {
    properties.find { it.code == movieCodes[code] }?.let { p ->
        val exposure = properties.find { it.code == 0x500E }?.current?.value
        if (code == 0x500D && (!live || exposure != 1L)) p.copy(writable = false)
        else if (code == 0x5007 && (!live || exposure !in listOf(1L, 3L))) p.copy(writable = false)
        else if (code == 0x500F && exposure != 1L) p.copy(writable = false, displayOverride = "AUTO")
        else if (code == 0x500F && properties.any { it.code == 0xD0AD && it.current.value == 1L })
            p.copy(writable = false, displayOverride = "AUTO")
        else p
    }
} else if (code == 0x500F)
    CameraIso.display(properties, parameterWarning == null && System.currentTimeMillis() - lastParameterRead < 5000)
    else properties.find { it.code == code }
