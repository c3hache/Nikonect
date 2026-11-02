package dev.nikonect.ptp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.EOFException
import java.net.SocketException
import java.io.IOException

/** Nikon's Connect-to-PC wizard uses separate authentication and finalization sessions. */
internal object WtuPairing {
    private const val GET_CODE = 0x952B
    private const val COMPLETE = 0x935A

    fun decodeCode(data: ByteArray): String {
        val reader = Reader(data)
        val length = reader.u32()
        require(length in 1..16 && length <= reader.remaining) { "相机返回了无效的配对码长度" }
        return buildString {
            repeat(length.toInt()) {
                val d = reader.u8()
                append(when (d) {
                    in 0..9 -> '0' + d
                    in 48..57 -> d.toChar()
                    else -> error("无法识别机身配对码格式")
                })
            }
        }
    }

    private suspend fun finish(session: PtpSession, phase: Int) {
        try {
            session.execute(COMPLETE, intArrayOf(phase))
        } catch (e: Exception) {
            if (e is CancellationException || generateSequence<Throwable>(e) { it.cause }.none {
                it is EOFException || it is SocketException
            }) throw e
            // Some cameras close the socket while committing. Reconnection is still required.
            return
        }
        try { session.execute(Ptp.CLOSE_SESSION) } catch (e: Exception) { if (e is CancellationException) throw e }
    }

    suspend fun negotiate(initial: PtpSession, required: Boolean, trusted: Boolean,
        reconnect: suspend () -> PtpSession, confirm: suspend (String?) -> Unit,
        status: (String) -> Unit): PtpSession {
        initial.execute(Ptp.OPEN_SESSION, intArrayOf(1))
        val challenge = try { initial.execute(GET_CODE, dataIn = true) }
        catch (e: PtpError) {
            if (e.response !in setOf(0x2005, 0x200F, 0x201C, 0x201D, 0xA001) || required && !trusted)
                throw IllegalStateException("机身未进入可确认的 WTU 配对阶段（0x%04X），请重新打开机身配对向导".format(e.response), e)
            initial.execute(Ptp.CLOSE_SESSION)
            return initial
        }
        confirm(decodeCode(challenge))
        status("正在向机身提交配对确认")
        finish(initial, 0x2002)
        initial.close()
        var last: Exception? = null
        for (attempt in 1..5) {
            delay(400L * attempt)
            try {
                reconnect().use { final ->
                    final.execute(Ptp.OPEN_SESSION, intArrayOf(1))
                    finish(final, 0x2001)
                }
                last = null
                break
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { last = e }
        }
        if (last != null) throw IOException("机身未完成最终配对，请重新进入配对向导", last)
        confirm(null)
        status("正在验证配对后的相机会话")
        for (attempt in 1..5) {
            delay(400L * attempt)
            try { return reconnect() }
            catch (e: CancellationException) { throw e }
            catch (e: IOException) { if (attempt == 5) throw e }
        }
        error("无法恢复相机会话")
    }
}
