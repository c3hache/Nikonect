package dev.nikonect.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** This is local USB-request-to-decoded-frame timing, not measured scene-to-screen latency. */
data class PreviewFrame(val bitmap: Bitmap, val histogram: IntArray, val fps: Float, val pipelineMs: Int, val usbMs: Int, val decodeMs: Int, val receivedNanos: Long, val focusBoxes: List<FocusBox> = emptyList(), val area: FocusArea? = null)
data class EncodedFrame(val bytes: ByteArray, val startedNanos: Long, val receivedNanos: Long)

class PreviewDecoder {
    private var cachedOffset = -1
    fun decode(payload: ByteArray, maxEdge: Int): Bitmap {
        val offset = PreviewMath.jpegOffset(payload, cachedOffset)
        check(offset >= 0) { "没有 JPEG 取景帧，请导出诊断日志" }
        cachedOffset = offset
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload, offset, payload.size - offset, bounds)
        check(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        return BitmapFactory.decodeByteArray(payload, offset, payload.size - offset,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
            ?: error("取景图像解码失败")
    }
    companion object {
        fun histogram(bitmap: Bitmap): IntArray {
            val bins = IntArray(64)
            val stepX = (bitmap.width / 160).coerceAtLeast(1)
            val stepY = (bitmap.height / 100).coerceAtLeast(1)
            val line = IntArray(bitmap.width)
            for (y in 0 until bitmap.height step stepY) {
                bitmap.getPixels(line, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in 0 until bitmap.width step stepX) {
                    val pixel = line[x]
                    bins[PreviewMath.lumaBin(pixel)]++
                }
            }
            return bins
        }
    }
}


