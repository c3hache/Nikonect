package dev.nikonect.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FocusBox(val x: Float, val y: Float, val width: Float, val height: Float, val focused: Boolean)

data class FocusArea(val left: Float, val top: Float, val width: Int, val height: Int, val wholeW: Int, val wholeH: Int)
internal object Z50Focus {
    fun area(data: ByteArray): FocusArea? {
        if (data.size < 384) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        if (b.getShort(0).toInt() != 1) b.order(ByteOrder.LITTLE_ENDIAN)
        fun u(at: Int) = b.getShort(at).toInt() and 65535
        val jpeg = b.getInt(8)
        if (u(0) != 1 || u(2) != 0 || jpeg !in 384..data.size-3 || data[jpeg] != 0xff.toByte() || data[jpeg+1] != 0xd8.toByte()) return null
        if (u(20) !in 1..u(16) || u(22) !in 1..u(18)) return null
        return FocusArea(u(24)-u(20)/2f, u(26)-u(22)/2f, u(20), u(22), u(16), u(18))
    }

    fun boxes(data: ByteArray): List<FocusBox> {
        if (data.size < 384) return emptyList()
        val header = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        if (header.getShort(0).toInt() != 1) header.order(ByteOrder.LITTLE_ENDIAN)
        fun u16(at: Int) = header.getShort(at).toInt() and 65535
        if (u16(0) != 1 || u16(2) != 0) return emptyList()
        val jpeg = header.getInt(8)
        if (jpeg !in 384..data.size - 3 || data[jpeg] != 0xff.toByte() || data[jpeg + 1] != 0xd8.toByte()) return emptyList()
        val count = data[44].toInt() and 255
        val w = u16(20)
        val h = u16(22)
        if (count !in 1..42 || w == 0 || h == 0) return emptyList()
        val left = u16(24) - w / 2f
        val top = u16(26) - h / 2f
        return buildList {
            repeat(count) { index ->
                val at = 48 + 8 * index
                val bw = u16(at)
                val bh = u16(at + 2)
                if (bw in 1..w && bh in 1..h) {
                    add(FocusBox((u16(at + 4) - bw / 2f - left) / w, (u16(at + 6) - bh / 2f - top) / h,
                        bw.toFloat() / w, bh.toFloat() / h, data[42].toInt() == 2))
                }
            }
        }
    }
}
