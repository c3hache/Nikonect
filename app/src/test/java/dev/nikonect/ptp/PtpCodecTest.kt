package dev.nikonect.ptp

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PtpCodecTest {
    private class Dataset {
        val output = ByteArrayOutputStream()
        fun u8(v: Int) { output.write(v) }
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Int) { u16(v); u16(v ushr 16) }
        fun text(s: String) { u8(s.length + 1); output.write(s.toByteArray(Charsets.UTF_16LE)); u16(0) }
        fun array16(vararg v: Int) { u32(v.size); v.forEach(::u16) }
        fun bytes() = output.toByteArray()
    }

    @Test fun commandContainerIsLittleEndianAndIncludesAllParameters() {
        val bytes = Ptp.container(1, Ptp.OBJECT_HANDLES, 0x12345678, Ptp.le(-1, 0, 0))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, b.int); assertEquals(1, b.short.toInt()); assertEquals(Ptp.OBJECT_HANDLES, b.short.toInt())
        assertEquals(0x12345678, b.int); assertEquals(-1, b.int); assertEquals(0, b.int); assertEquals(0, b.int)
    }

    @Test fun deviceInfoReadsArraysAndUtf16ManufacturerWithoutOffsetDrift() {
        val d = Dataset().apply {
            u16(100); u32(10); u16(100); text("Nikon"); u16(0)
            array16(0x1001, 0x9201, 0x9203); array16(0x4002); array16(0x500F)
            array16(0x3801); array16(0x3801, 0xB101)
            text("尼康 Nikon"); text("Z 50"); text("2.50"); text("not exported")
        }
        val decoded = DeviceInfo.decode(d.bytes())
        assertEquals(10L, decoded.vendor); assertEquals("尼康 Nikon", decoded.manufacturer)
        assertEquals("Z 50", decoded.model); assertEquals("2.50", decoded.version)
        assertEquals(setOf(0x1001, 0x9201, 0x9203), decoded.operations); assertEquals(setOf(0x500F), decoded.properties)
    }

    @Test fun objectInfoFilenameBeginsAtByte52AndRawIsRecognized() {
        val d = Dataset().apply {
            u32(0x10001); u16(0xB101); u16(0); u32(12345678)
            repeat(40) { u8(0) }; text("DSC_0001.NEF")
        }
        val info = ObjectInfo.decode(d.bytes())
        assertEquals("DSC_0001.NEF", info.filename); assertEquals(12345678L, info.size); assertTrue(info.isPhoto)
        assertFalse(ObjectInfo(0x3001, 0, "DCIM").isPhoto)
    }

    @Test fun unsignedEnumerationEncodesTheNextPermittedValue() {
        val d = Dataset().apply {
            u16(0x500F); u16(4); u8(1); u16(100); u16(200); u8(2)
            u16(3); u16(100); u16(200); u16(400)
        }
        val p = CameraProperty.decode(0x500F, "ISO", d.bytes())
        assertTrue(p.writable); assertEquals(200L, p.current.value)
        assertEquals(400L, p.next(1).value); assertArrayEquals(byteArrayOf(0x90.toByte(), 1), p.next(1).encoded)
        assertEquals(100L, p.next(-1).value)
    }

    @Test fun signedRangeKeepsTwosComplementEncoding() {
        val d = Dataset().apply {
            u16(0x5010); u16(3); u8(1); u16(0); u16(-2); u8(1); u16(-6); u16(6); u16(2)
        }
        val p = CameraProperty.decode(0x5010, "EV", d.bytes())
        assertEquals(-2L, p.current.value); assertEquals(-4L, p.next(-1).value)
        assertArrayEquals(byteArrayOf(0xFC.toByte(), 0xFF.toByte()), p.next(-1).encoded)
    }

    @Test fun unsigned32PreservesHighBit() {
        assertEquals(4294967295L, Reader(byteArrayOf(-1, -1, -1, -1)).scalar(6).value)
        assertEquals(-1L, Reader(byteArrayOf(-1, -1, -1, -1)).scalar(5).value)
    }

    @Test fun oversizedArrayCountAndTruncatedStringsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Reader(byteArrayOf(-1, -1, -1, -1)).array16() }
        assertThrows(IllegalArgumentException::class.java) { Reader(byteArrayOf(4, 65, 0)).text() }
        assertThrows(IllegalArgumentException::class.java) { ObjectInfo.decode(ByteArray(51)) }
    }

    @Test fun readonlyDescriptorWithoutFormHasNoEditableChoices() {
        val d = Dataset().apply { u16(0x500F); u16(4); u8(0); u16(100); u16(100); u8(0) }
        val p = CameraProperty.decode(0x500F, "ISO", d.bytes())
        assertFalse(p.writable); assertTrue(p.options.isEmpty()); assertNull(p.range)
    }
}
