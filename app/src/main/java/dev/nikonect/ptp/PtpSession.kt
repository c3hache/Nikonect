package dev.nikonect.ptp

import java.io.OutputStream

interface PtpSession : AutoCloseable {
    val healthy: Boolean
    val transportDescription: String
    suspend fun execute(operation: Int, params: IntArray = intArrayOf(), dataOut: ByteArray? = null,
                        dataIn: Boolean = false, sink: OutputStream? = null): ByteArray
    suspend fun closeReportedOldSession()
}
