package dev.nikonect.ptp

import android.net.Network
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PTP/IP command/data and event sockets. Each socket stays on the chosen Wi-Fi network. */
class WifiPtpSession(private val host: String, private val port: Int, network: Network?, guid: ByteArray, local: InetAddress? = null) : PtpSession {
    private val mutex = Mutex()
    private val command: Socket = network?.socketFactory?.createSocket() ?: Socket()
    private val event: Socket = network?.socketFactory?.createSocket() ?: Socket()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var closed = false
    @Volatile private var valid = true
    val peerId: String
    val peerName: String
    var pairingVerified = false
    private var sessionOpen = false
    private var nextId = 0
    override val healthy get() = !closed && valid
    override val transportDescription get() = "Wi-Fi PTP/IP $host:$port（实验性）"

    init {
        try {
            require(guid.size == 16)
            if (local != null) {
                command.bind(InetSocketAddress(local, 0))
                event.bind(InetSocketAddress(local, 0))
            }
            command.tcpNoDelay = true; command.soTimeout = 8000
            command.connect(InetSocketAddress(host, port), 6000)
            write(command, 1, guid + "Nikonect\u0000".toByteArray(Charsets.UTF_16LE) + Ptp.le(0x00010000))
            val (kind, length) = header(command)
            require(length <= 4096) { "无线握手响应过长" }
            val ack = readExact(command, length)
            check(kind == 2 && ack.size >= 24) { "无线握手被相机拒绝（类型 $kind）；请退出 SnapBridge，并检查相机 Wi-Fi 模式" }
            val id = Reader(ack).u32().toInt()
            peerId = ack.copyOfRange(4, 20).joinToString("") { "%02x".format(it) }
            peerName = ack.copyOfRange(20, ack.size - 4).toString(Charsets.UTF_16LE).trimEnd('\u0000')
            event.tcpNoDelay = true; event.soTimeout = 0
            event.connect(InetSocketAddress(host, port), 6000)
            write(event, 3, Ptp.le(id))
            event.soTimeout = 8000
            val (eventKind, eventLength) = header(event)
            require(eventLength <= 4096) { "无线事件握手响应过长" }
            readExact(event, eventLength)
            check(eventKind == 4) { "无线事件通道建立失败（类型 $eventKind）" }
            event.soTimeout = 0
            scope.launch {
                try {
                    while (isActive && !closed) {
                        val (type, bytes) = header(event)
                        require(bytes <= 65536)
                        readExact(event, bytes)
                        if (type == 13) write(event, 14, byteArrayOf())
                        else check(type == 8) { "非预期无线事件 $type" }
                    }
                } catch (_: Exception) { if (!closed) { valid = false; command.close() } }
            }
        } catch (e: Exception) { close(); throw e }
    }

    private fun readExact(socket: Socket, count: Int): ByteArray {
        val bytes = ByteArray(count)
        DataInputStream(socket.getInputStream()).readFully(bytes)
        return bytes
    }
    private fun header(socket: Socket): Pair<Int, Int> {
        val r = Reader(readExact(socket, 8)); val length = r.u32(); val type = r.u32().toInt()
        require(length in 8..(512L * 1024 * 1024 + 12)) { "无线数据包长度无效" }
        return type to (length - 8).toInt()
    }
    private fun write(socket: Socket, type: Int, payload: ByteArray) {
        socket.getOutputStream().apply { write(Ptp.le(payload.size + 8, type)); write(payload); flush() }
    }

    override suspend fun execute(operation: Int, params: IntArray, dataOut: ByteArray?, dataIn: Boolean, sink: OutputStream?): ByteArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(healthy) { "无线通道已断开，请重新连接" }
            val id = if (sessionOpen) nextId++ else 0
            try {
                require(params.size <= 5)
                val request = ByteBuffer.allocate(10 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(if (dataOut != null) 2 else 1).putShort(operation.toShort()).putInt(id)
                params.forEach { request.putInt(it) }
                write(command, 6, request.array())
                dataOut?.let {
                    write(command, 9, Ptp.le(id, it.size, 0))
                    write(command, 12, Ptp.le(id) + it)
                }
                val result = ByteArrayOutputStream()
                var expected = -1L; var received = 0L; var ended = false
                while (true) {
                    ensureActive()
                    val (type, length) = header(command)
                    when (type) {
                        13 -> { require(length == 0); write(command, 14, byteArrayOf()) }
                        9 -> {
                            require(dataIn && expected == -1L && length == 12)
                            val r = Reader(readExact(command, length)); require(r.u32().toInt() == id)
                            expected = r.u32() or (r.u32() shl 32)
                            require(expected in 0..512L * 1024 * 1024)
                            if (sink == null) require(expected <= 16 * 1024 * 1024)
                        }
                        10, 12 -> {
                            require(expected >= 0 && !ended && length >= 4)
                            require(Reader(readExact(command, 4)).u32().toInt() == id)
                            var left = length - 4
                            require(received + left <= expected)
                            while (left > 0) {
                                val bytes = readExact(command, minOf(left, 16 * 1024))
                                (sink ?: result).write(bytes); left -= bytes.size; received += bytes.size
                            }
                            if (type == 12) { require(received == expected); ended = true }
                        }
                        7 -> {
                            require(length in 6..26 && (length - 6) % 4 == 0)
                            val r = Reader(readExact(command, length)); val response = r.u16()
                            require(r.u32().toInt() == id) { "无线事务序号不匹配" }
                            if (response != Ptp.OK) throw PtpError(response, operation)
                            require(!dataIn || (ended && received == expected)) { "无线数据阶段不完整" }
                            if (operation == Ptp.OPEN_SESSION) { sessionOpen = true; nextId = 1 }
                            if (operation == Ptp.CLOSE_SESSION) { sessionOpen = false; nextId = 0 }
                            return@withContext result.toByteArray()
                        }
                        else -> error("非预期无线数据包 $type")
                    }
                }
                @Suppress("UNREACHABLE_CODE") byteArrayOf()
            } catch (e: PtpError) { throw e }
            catch (e: Exception) { valid = false; close(); throw IllegalStateException("无线操作 0x%04X：${e.message}".format(operation), e) }
        }
    }
    override suspend fun closeReportedOldSession() {
        sessionOpen = true; nextId = 1
        try { execute(Ptp.CLOSE_SESSION) }
        catch (e: PtpError) { if (e.response != 0x2003) throw e; sessionOpen = false; nextId = 0 }
    }
    override fun close() {
        closed = true; scope.cancel()
        runCatching { command.close() }; runCatching { event.close() }
    }
}
