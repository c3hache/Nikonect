package dev.nikonect.ptp

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PtpStreamTest {
    private class FakeIo(val incoming: ByteArray, val chunk: Int = 16384) : PtpPacketIo {
        var cursor = 0
        val readSizes = mutableListOf<Int>()
        val commands = mutableListOf<Triple<Int, Int, Int>>()
        override fun read(buffer: ByteArray): Int {
            readSizes += buffer.size
            if (buffer.size > 16384 || cursor >= incoming.size) return -1
            val count = minOf(buffer.size, chunk, incoming.size - cursor)
            incoming.copyInto(buffer, 0, cursor, cursor + count); cursor += count; return count
        }
        override fun write(buffer: ByteArray, offset: Int, count: Int): Int {
            val r = Reader(buffer.copyOfRange(offset, offset + count)); r.u32(); val type = r.u16(); val op = r.u16(); val tx = r.u32().toInt()
            commands += Triple(type, op, tx); return count
        }
    }
    private fun response(code: Int = Ptp.OK, tx: Int = 0) = Ptp.container(3, code, tx, byteArrayOf())
    private fun data(op: Int, value: ByteArray, tx: Int = 0) = Ptp.container(2, op, tx, value)

    @Test fun workingCompatibilityReadSizeAndBufferedContainersArePreserved() {
        val value = ByteArray(157) { it.toByte() }
        val io = FakeIo(data(Ptp.DEVICE_INFO, value) + response() + response())
        val stream = PtpStream(io)
        assertArrayEquals(value, stream.execute(Ptp.DEVICE_INFO, dataIn = true))
        stream.execute(Ptp.OPEN_SESSION, intArrayOf(1))
        assertTrue(io.readSizes.all { it == 16384 }); assertTrue(stream.healthy)
        assertEquals(listOf(Triple(1, Ptp.DEVICE_INFO, 0), Triple(1, Ptp.OPEN_SESSION, 0)), io.commands)
    }
    @Test fun fragmentedHeadersAndDataAcrossManyShortReadsWork() {
        val value = ByteArray(301) { (it * 3).toByte() }
        val io = FakeIo(data(Ptp.DEVICE_INFO, value) + response(), 3)
        assertArrayEquals(value, PtpStream(io).execute(Ptp.DEVICE_INFO, dataIn = true))
    }
    @Test fun explicitlyReportedStaleSessionClosesThenReopensWithCorrectIds() {
        val io = FakeIo(response(0x201E) + response(tx = 1) + response() + data(Ptp.PROP_GET, byteArrayOf(42), 1) + response(tx = 1))
        val stream = PtpStream(io)
        val error = assertThrows(PtpError::class.java) { stream.execute(Ptp.OPEN_SESSION, intArrayOf(1)) }
        assertEquals(0x201E, error.response); assertTrue(stream.healthy)
        stream.closeReportedOldSession(); stream.execute(Ptp.OPEN_SESSION, intArrayOf(1))
        assertArrayEquals(byteArrayOf(42), stream.execute(Ptp.PROP_GET, intArrayOf(0x500F), dataIn = true))
        assertEquals(listOf(Triple(1, Ptp.OPEN_SESSION, 0), Triple(1, Ptp.CLOSE_SESSION, 1), Triple(1, Ptp.OPEN_SESSION, 0), Triple(1, Ptp.PROP_GET, 1)), io.commands)
    }
    @Test fun busyResponseCanBeRetriedWithoutPoisoningOrAdvancingOutsideSessionId() {
        val io = FakeIo(response(Ptp.BUSY) + response())
        val stream = PtpStream(io)
        assertThrows(PtpError::class.java) { stream.execute(Ptp.OPEN_SESSION, intArrayOf(1)) }
        stream.execute(Ptp.OPEN_SESSION, intArrayOf(1)); assertTrue(stream.healthy)
        assertEquals(listOf(0, 0), io.commands.map { it.third })
    }
    @Test fun readTimeoutNamesTheFailingOperationAndInvalidatesTheLink() {
        val io = FakeIo(byteArrayOf()); val stream = PtpStream(io)
        val error = assertThrows(IllegalStateException::class.java) { stream.execute(Ptp.DEVICE_INFO, dataIn = true) }
        assertTrue(error.message!!.contains("0x1001")); assertTrue(error.message!!.contains("16KB")); assertFalse(stream.healthy)
        assertThrows(IllegalStateException::class.java) { stream.execute(Ptp.OPEN_SESSION) }
        assertEquals(1, io.commands.size)
    }
    @Test fun largeObjectStreamsCorrectlyUsingOnlyCompatibilitySizedUsbReads() {
        val value = ByteArray(80 * 1024 + 7) { (it % 251).toByte() }
        val io = FakeIo(data(Ptp.GET_OBJECT, value) + response(), 8192)
        val out = ByteArrayOutputStream()
        PtpStream(io).execute(Ptp.GET_OBJECT, intArrayOf(22), dataIn = true, sink = out)
        assertArrayEquals(value, out.toByteArray()); assertTrue(io.readSizes.all { it <= 16384 })
    }
    @Test fun mismatchedTransactionIsRejectedWithoutReusingDesynchronizedData() {
        val stream = PtpStream(FakeIo(response(tx = 99)))
        assertThrows(IllegalStateException::class.java) { stream.execute(Ptp.OPEN_SESSION, intArrayOf(1)) }
        assertFalse(stream.healthy)
    }
    @Test fun aShortWriteFailsBeforeAnyRead() {
        var reads = 0
        val stream = PtpStream(object : PtpPacketIo {
            override fun write(buffer: ByteArray, offset: Int, count: Int) = count - 1
            override fun read(buffer: ByteArray): Int { reads++; return -1 }
        })
        assertThrows(IllegalStateException::class.java) { stream.execute(Ptp.DEVICE_INFO, dataIn = true) }
        assertEquals(0, reads); assertFalse(stream.healthy)
    }
}
