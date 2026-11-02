package dev.nikonect.ptp

import java.io.OutputStream

/** Small packet reads preserve the device/phone behavior used by the working 0.1 release. */
interface PtpPacketIo {
    fun read(buffer: ByteArray): Int
    fun write(buffer: ByteArray, offset: Int, count: Int): Int
}
class PtpStream(private val io: PtpPacketIo) {
    companion object { const val READ_BYTES = 16 * 1024 }
    private val pending = ByteArray(READ_BYTES)
    private var size = 0
    private var offset = 0
    private var nextId = 0
    private var sessionOpen = false
    var healthy = true; private set
    private fun readExact(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var copied = 0; var empty = 0
        while (copied < count) {
            if (offset >= size) {
                val n = io.read(pending)
                if (n < 0) error("USB 读取超时或相机断开（16KB 兼容读取，已读 $copied/$count 字节）")
                require(n <= pending.size)
                if (n == 0) { check(++empty <= 3) { "连续收到空 USB 数据包" }; continue }
                empty = 0; size = n; offset = 0
            }
            val n = minOf(count - copied, size - offset)
            pending.copyInto(bytes, copied, offset, offset + n); copied += n; offset += n
        }
        return bytes
    }
    private fun write(bytes: ByteArray) {
        var sent = 0
        while (sent < bytes.size) {
            val count = minOf(READ_BYTES, bytes.size - sent)
            check(io.write(bytes, sent, count) == count) { "USB 写入不完整" }; sent += count
        }
    }
    private data class Header(val length: Long, val type: Int, val code: Int)
    private fun header(id: Int): Header {
        val r = Reader(readExact(12))
        val size = r.u32(); val type = r.u16(); val code = r.u16(); val tx = r.u32().toInt()
        require(size in 12..512L * 1024 * 1024) { "PTP 容器长度异常" }
        require(tx == id) { "PTP 事务序号不匹配：收到 $tx，预期 $id" }
        return Header(size - 12, type, code)
    }
    fun execute(operation: Int, params: IntArray = intArrayOf(), dataOut: ByteArray? = null, dataIn: Boolean = false, sink: OutputStream? = null): ByteArray {
        check(healthy) { "PTP 通道已失效，请重新连接" }
        val id = if (sessionOpen) nextId++ else 0
        try {
            require(params.size <= 5)
            write(Ptp.container(1, operation, id, Ptp.le(*params)))
            dataOut?.let { write(Ptp.container(2, operation, id, it)) }
            var h = header(id); var data = ByteArray(0); var hadData = false
            if (h.type == 2) {
                require(dataIn && h.code == operation) { "非预期的 PTP 数据阶段" }; hadData = true
                if (sink == null) { require(h.length <= 16 * 1024 * 1024); data = readExact(h.length.toInt()) }
                else {
                    var left = h.length
                    while (left > 0) { val bytes = readExact(minOf(left, 64 * 1024).toInt()); sink.write(bytes); left -= bytes.size }
                }
                h = header(id)
            }
            require(h.type == 3 && h.length <= 20 && h.length % 4 == 0L) { "PTP 响应格式异常" }
            readExact(h.length.toInt())
            if (h.code != Ptp.OK) throw PtpError(h.code, operation)
            require(!dataIn || hadData) { "成功响应缺少数据阶段" }
            if (operation == Ptp.OPEN_SESSION) { sessionOpen = true; nextId = 1 }
            if (operation == Ptp.CLOSE_SESSION) { sessionOpen = false; nextId = 0 }
            return data
        } catch (e: PtpError) { throw e }
        catch (e: Exception) { healthy = false; throw IllegalStateException("操作 0x%04X：${e.message}".format(operation), e) }
    }
    /** Only called after the camera explicitly returns SessionAlreadyOpen (0x201E). */
    fun closeReportedOldSession() {
        sessionOpen = true; nextId = 1
        try { execute(Ptp.CLOSE_SESSION) }
        catch (e: PtpError) { if (e.response != 0x2003) throw e; sessionOpen = false; nextId = 0 }
    }
}
