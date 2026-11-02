package dev.nikonect.camera

object PreviewMath {
    fun jpegOffset(bytes: ByteArray, cached: Int = -1): Int {
        fun valid(offset: Int) = offset >= 0 && offset + 2 < bytes.size && bytes[offset] == 0xff.toByte() && bytes[offset + 1] == 0xd8.toByte() && bytes[offset + 2] == 0xff.toByte()
        if (valid(cached)) return cached
        return (0 until minOf((bytes.size - 2).coerceAtLeast(0), 65536)).firstOrNull(::valid) ?: -1
    }
    fun lumaBin(pixel: Int): Int = ((((pixel shr 16) and 255) * 77 + ((pixel shr 8) and 255) * 150 + (pixel and 255) * 29) shr 8) / 4
}
