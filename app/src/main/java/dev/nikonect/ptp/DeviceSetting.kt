package dev.nikonect.ptp

import java.math.BigInteger

/** Typed values are kept lossless, including unsigned 64/128-bit integers. */
data class DeviceSetting(
    val code: Int, val type: Int, val writable: Boolean, val current: String,
    val choices: List<String>, val limits: List<BigInteger>
) {
    fun encode(input: String): ByteArray {
        require(writable) { "相机当前只读" }
        require(choices.isEmpty() || input in choices) { "相机允许的选项已改变" }
        if (type == 0xffff) {
            require(input.length <= 254 && '\u0000' !in input) { "字符串过长或含无效字符" }
            return if (input.isEmpty()) byteArrayOf(0) else byteArrayOf((input.length + 1).toByte()) +
                input.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        }
        if (type == 0x4002) {
            val hex = input.filterNot(Char::isWhitespace)
            require(hex.length % 2 == 0 && hex.length <= 131072) { "请输入成对的十六进制字节" }
            val data = hex.chunked(2).map { it.toInt(16).also { b -> require(b in 0..255) }.toByte() }.toByteArray()
            return Ptp.le(data.size) + data
        }
        val size = width(type)
        val value = input.toBigIntegerOrNull() ?: error("请输入整数")
        val signed = type % 2 == 1
        require(value.bitLength() <= size * 8 - (if (signed) 1 else 0) && (signed || value.signum() >= 0)) { "数值超出类型范围" }
        if (limits.isNotEmpty()) {
            val (min, max, step) = limits
            require(value in min..max && step.signum() > 0 && (value - min).mod(step) == BigInteger.ZERO) { "数值不符合相机范围或步长" }
        }
        return ByteArray(size) { value.shiftRight(8 * it).toByte() }
    }

    companion object {
        private fun width(type: Int): Int {
            require(type in 1..10) { "未知数值类型" }
            return 1 shl ((type - 1) / 2)
        }
        fun value(r: Reader, type: Int): String = when (type) {
            0xffff -> r.text()
            0x4002 -> {
                val count = r.u32()
                require(count <= 65536 && count <= r.remaining)
                r.take(count.toInt()).joinToString(" ") { "%02X".format(it) }
            }
            else -> {
                val bytes = r.take(width(type)).reversedArray()
                (if (type % 2 == 0) BigInteger(1, bytes) else BigInteger(bytes)).toString()
            }
        }
        fun decode(code: Int, bytes: ByteArray): DeviceSetting {
            val r = Reader(bytes)
            require(r.u16() == code)
            val type = r.u16()
            val writable = r.u8() == 1
            value(r, type)
            val current = value(r, type)
            var choices = emptyList<String>()
            var limits = emptyList<BigInteger>()
            when (r.u8()) {
                0 -> Unit
                1 -> limits = List(3) { value(r, type).toBigInteger() }
                2 -> choices = List(r.u16().also { require(it <= 16384) }) { value(r, type) }
                else -> error("未识别的相机约束，已禁止写入")
            }
            return DeviceSetting(code, type, writable, current, choices, limits)
        }
    }
}
