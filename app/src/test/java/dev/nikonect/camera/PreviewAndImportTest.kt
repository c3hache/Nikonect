package dev.nikonect.camera

import org.junit.Assert.*
import org.junit.Test

class PreviewAndImportTest {
    @Test fun jpgAlwaysAcceptedAndNefIsOptIn() {
        assertTrue(ImportPolicy.accept("DSC_001.JPG", false))
        assertTrue(ImportPolicy.accept("DSC_001.jpeg", false))
        assertFalse(ImportPolicy.accept("DSC_001.NEF", false))
        assertTrue(ImportPolicy.accept("DSC_001.nef", true))
        assertFalse(ImportPolicy.accept("DSC_001.MOV", true))
        assertFalse(ImportPolicy.accept("DCIM", true))
    }
    @Test fun galleryNeverTreatsNefAsJpeg() {
        assertTrue(ImportPolicy.isJpeg("DSC_002.JpG"))
        assertFalse(ImportPolicy.isJpeg("DSC_002.NEF"))
        assertFalse(ImportPolicy.isJpeg("fake.jpg.nef"))
    }
    @Test fun priorityStopsTransfersOnlyWhileLive() {
        assertTrue(ImportPolicy.deferForLive(true, true))
        assertFalse(ImportPolicy.deferForLive(false, true))
        assertFalse(ImportPolicy.deferForLive(true, false))
    }
    @Test fun pacingSubtractsUsbTimeInsteadOfAddingDelay() {
        assertEquals(23L, FramePacing.remainingMillis(0, 10_000_000, 30))
        assertEquals(0L, FramePacing.remainingMillis(0, 50_000_000, 30))
        assertEquals(26L, FramePacing.remainingMillis(0, 40_000_000, 15))
    }
    @Test fun jpegSearchHandlesChangingHeaderAndSmallPayloads() {
        val bytes = ByteArray(64)
        bytes[16] = 0xff.toByte(); bytes[17] = 0xd8.toByte(); bytes[18] = 0xff.toByte()
        assertEquals(16, PreviewMath.jpegOffset(bytes))
        assertEquals(16, PreviewMath.jpegOffset(bytes, 22))
        assertEquals(16, PreviewMath.jpegOffset(bytes, 16))
        assertEquals(-1, PreviewMath.jpegOffset(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
        assertEquals(-1, PreviewMath.jpegOffset(ByteArray(0)))
    }
    @Test fun histogramBinsCoverBlackWhiteAndRgbWithoutOverflow() {
        assertEquals(0, PreviewMath.lumaBin(0xFF000000.toInt()))
        assertEquals(63, PreviewMath.lumaBin(0xFFFFFFFF.toInt()))
        assertEquals(19, PreviewMath.lumaBin(0xFFFF0000.toInt()))
        assertEquals(37, PreviewMath.lumaBin(0xFF00FF00.toInt()))
        assertEquals(7, PreviewMath.lumaBin(0xFF0000FF.toInt()))
    }
}
