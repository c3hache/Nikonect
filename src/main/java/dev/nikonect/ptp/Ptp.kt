package dev.nikonect.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Ptp {
    const val OK = 0x2001
    const val BUSY = 0x2019
    const val DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val STORAGE_IDS = 0x1004
    const val OBJECT_HANDLES = 0x1007
    const val OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A
    const val CAPTURE = 0x100E
    const val NIKON_CAPTURE_MEDIA = 0x9207
    const val PROP_DESC = 0x1014
    const val PROP_GET = 0x1015
    const val PROP_SET = 0x1016
    const val LV_START = 0x9201
    const val LV_END = 0x9202
    const val LV_IMAGE = 0x9203
    fun le(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it) } }.array()
    fun container(type: Int, code: Int, tx: Int, payload: ByteArray): ByteArray = ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(12 + payload.size).putShort(type.toShort()).putShort(code.toShort()).putInt(tx).put(payload).array()
}

class PtpError(val response: Int, operation: Int) : Exception("PTP 0x%04X，操作 0x%04X".format(response, operation))
class Reader(private val bytes: ByteArray) {
    var position = 0
    val remaining get() = bytes.size - position
    fun take(n: Int): ByteArray { require(n >= 0 && n <= remaining) { "PTP 数据长度无效" }; return bytes.copyOfRange(position, position + n).also { position += n } }
    fun u8() = take(1)[0].toInt() and 255
    fun u16(): Int = u8() or (u8() shl 8)
    fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
    fun array16(): Set<Int> { val count = u32(); require(count <= remaining / 2); return List(count.toInt()) { u16() }.toSet() }
    fun array32(): List<Int> { val count = u32(); require(count <= remaining / 4); return List(count.toInt()) { u32().toInt() } }
    fun text(): String { val n = u8(); if (n == 0) return ""; return take(n * 2).dropLast(2).toByteArray().toString(Charsets.UTF_16LE) }
    fun scalar(type: Int): Scalar {
        val size = when (type) { 1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; else -> error("暂不支持属性类型 0x%04X".format(type)) }
        val raw = take(size)
        var number = 0L
        raw.forEachIndexed { i, b -> number = number or ((b.toLong() and 255) shl (8 * i)) }
        if (type % 2 == 1 && raw.last().toInt() < 0) number -= 1L shl (8 * size)
        return Scalar(number, raw)
    }
}

data class Scalar(val value: Long, val encoded: ByteArray)
data class DeviceInfo(val vendor: Long, val manufacturer: String, val model: String, val version: String, val operations: Set<Int>, val properties: Set<Int>, val serial: String = "") {
    companion object {
        fun decode(bytes: ByteArray): DeviceInfo {
            val r = Reader(bytes)
            r.u16(); val vendor = r.u32(); r.u16(); r.text(); r.u16()
            val ops = r.array16(); r.array16(); val props = r.array16(); r.array16(); r.array16()
            val maker = r.text(); val model = r.text(); val version = r.text()
            return DeviceInfo(vendor, maker, model, version, ops, props, if (r.remaining > 0) r.text() else "")
        }
    }
}
data class ObjectInfo(val format: Int, val size: Long, val filename: String, val storage: Int = 0, val parent: Int = 0) {
    val isPhoto get() = format in setOf(0x3800, 0x3801, 0x380D, 0xB101) || filename.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "nef", "nrw", "tif", "tiff", "heif", "heic")
    companion object {
        fun decode(bytes: ByteArray): ObjectInfo {
            val r = Reader(bytes)
            val storage = r.u32().toInt(); val format = r.u16(); r.u16(); val size = r.u32()
            r.take(26); val parent = r.u32().toInt(); r.take(10)
            return ObjectInfo(format, size, r.text(), storage, parent)
        }
    }
}
data class CameraProperty(val code: Int, val label: String, val type: Int, val writable: Boolean, val current: Scalar, val options: List<Scalar>, val range: Triple<Long, Long, Long>?, val displayOverride: String? = null) {
    fun next(direction: Int): Scalar {
        if (options.isNotEmpty()) {
            val ordered = options.distinctBy { it.value }.sortedBy { if (code == 0xD1A8) (it.value ushr 16).toDouble() / (it.value and 65535).coerceAtLeast(1) else it.value.toDouble() }
            if (code == 0xD1A8) {
                val index = ordered.indexOfFirst { it.value == current.value }
                return if (index < 0) current else ordered.getOrNull(index + direction) ?: current
            }
            return (if (direction > 0) ordered.firstOrNull { it.value > current.value }
                    else ordered.lastOrNull { it.value < current.value }) ?: current
        }
        val bounds = range ?: error("相机没有提供可调范围")
        val (min, max, step) = bounds
        require(step > 0 && max >= min)
        require(current.value in min..max) { "当前值超出相机报告范围，请刷新参数" }
        val candidate = min + ((current.value - min) / step + 1) * step
        val target = if (direction > 0) (if (candidate <= max) candidate else current.value)
                     else (min + ((current.value - min - 1).coerceAtLeast(0) / step) * step).coerceAtLeast(min)
        val bytes = ByteArray(current.encoded.size) { (target ushr (it * 8)).toByte() }
        return Scalar(target, bytes)
    }
    fun canChange(direction: Int): Boolean = writable && runCatching { next(direction).value != current.value }.getOrDefault(false)
    val display: String get() = displayOverride ?: when (code) {
        0xD1A8 -> if ((current.value and 65535) == 0L || (current.value ushr 16) == 0L) "—" else "${current.value ushr 16}/${current.value and 65535} s"
        0xD1A9 -> if (current.value in 1..65534) "f/%.1f".format(current.value / 100.0) else "—"
        0x5007 -> "f/%.2f".format(current.value / 100.0)
        0x500D -> when { current.value == 0xFFFFFFFFL -> "Bulb"; current.value == 0xFFFFFFFDL -> "Time"; current.value in 1..9999 -> "1/%.0f s".format(10000.0 / current.value); else -> "%.2f s".format(current.value / 10000.0) }
        0x5001 -> "${current.value}%"
        0x500E -> when (current.value.toInt()) { 1 -> "M"; 2 -> "P"; 3 -> "A"; 4 -> "S"; 0x8010 -> "AUTO"; else -> "模式 ${current.value}" }
        0xD1AB, 0x5010 -> "%+.1f EV".format(current.value / 1000.0)
        0xD23A, 0x5005 -> when (current.value.toInt()) { 2 -> "自动白平衡"; 4 -> "日光"; 6 -> "白炽灯"; 0x8010 -> "阴天"; else -> "WB ${current.value}" }
        else -> current.value.toString()
    }
    companion object {
        fun decode(code: Int, label: String, bytes: ByteArray): CameraProperty {
            val r = Reader(bytes)
            require(r.u16() == code)
            val type = r.u16(); val writable = r.u8() == 1
            r.scalar(type); val current = r.scalar(type)
            val form = r.u8()
            var range: Triple<Long, Long, Long>? = null; var options = emptyList<Scalar>()
            when (form) {
                1 -> range = Triple(r.scalar(type).value, r.scalar(type).value, r.scalar(type).value)
                2 -> { val count = r.u16(); require(count <= 4096); options = List(count) { r.scalar(type) } }
            }
            return CameraProperty(code, label, type, writable, current, options, range)
        }
    }
}
