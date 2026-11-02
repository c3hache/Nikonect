package dev.nikonect.ptp

import android.hardware.usb.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream

class UsbPtpSession(manager: UsbManager, device: UsbDevice) : PtpSession {
    private val lock = Mutex()
    private val connection: UsbDeviceConnection
    private val usbInterface: UsbInterface
    private val input: UsbEndpoint
    private val output: UsbEndpoint
    private val stream: PtpStream
    @Volatile private var closed = false
    override val healthy get() = !closed && stream.healthy
    override val transportDescription get() = "USB interface=${usbInterface.id}, IN=0x%02X, OUT=0x%02X, read=%d bytes".format(input.address, output.address, PtpStream.READ_BYTES)
    init {
        usbInterface = (0 until device.interfaceCount).map(device::getInterface).firstOrNull { candidate ->
            candidate.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE && (0 until candidate.endpointCount).map(candidate::getEndpoint).let { eps ->
                eps.any { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN } &&
                    eps.any { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            }
        } ?: error("未找到 PTP USB 接口")
        connection = manager.openDevice(device) ?: error("无法打开 USB，相机可能被其他应用占用")
        if (!connection.claimInterface(usbInterface, true)) { connection.close(); error("无法占用 PTP 接口") }
        val eps = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        input = eps.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        output = eps.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
        stream = PtpStream(object : PtpPacketIo {
            override fun read(buffer: ByteArray): Int { check(!closed); return connection.bulkTransfer(input, buffer, buffer.size, 5000) }
            override fun write(buffer: ByteArray, offset: Int, count: Int): Int { check(!closed); return connection.bulkTransfer(output, buffer, offset, count, 5000) }
        })
    }
    override suspend fun execute(operation: Int, params: IntArray, dataOut: ByteArray?, dataIn: Boolean, sink: OutputStream?): ByteArray = lock.withLock {
        withContext(Dispatchers.IO) { check(!closed); stream.execute(operation, params, dataOut, dataIn, sink) }
    }
    override suspend fun closeReportedOldSession() = lock.withLock { withContext(Dispatchers.IO) { stream.closeReportedOldSession() } }
    override fun close() { if (!closed) { closed = true; runCatching { connection.releaseInterface(usbInterface) }; connection.close() } }
}
