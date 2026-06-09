// android-receiver/app/src/main/java/com/audioshare/core/network/UdpReceiver.kt
package com.audioshare.core.network

import com.audioshare.core.buffer.AudioPacket
import com.audioshare.core.buffer.JitterBuffer
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Listens on UDP, parses audio packets, pushes to JitterBuffer.
 * Packet format: [seq:4][ts:4][opus:N]
 */
class UdpReceiver(
    private val port: Int,
    private val jitterBuffer: JitterBuffer
) {
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        socket = DatagramSocket(port)
        job = scope.launch(Dispatchers.IO) {
            val recvBuf = ByteArray(512)
            val dgram   = DatagramPacket(recvBuf, recvBuf.size)

            while (isActive) {
                try {
                    socket!!.receive(dgram)
                    val len = dgram.length
                    if (len < 9) continue  // too short: 8 header + at least 1 opus byte

                    val data = dgram.data
                    val off  = dgram.offset

                    // Parse header (big-endian)
                    val seq = ((data[off+0].toLong() and 0xFF) shl 24) or
                              ((data[off+1].toLong() and 0xFF) shl 16) or
                              ((data[off+2].toLong() and 0xFF) shl 8)  or
                               (data[off+3].toLong() and 0xFF)

                    val ts  = ((data[off+4].toLong() and 0xFF) shl 24) or
                              ((data[off+5].toLong() and 0xFF) shl 16) or
                              ((data[off+6].toLong() and 0xFF) shl 8)  or
                               (data[off+7].toLong() and 0xFF)

                    val opusData = data.copyOfRange(off + 8, off + len)

                    jitterBuffer.push(AudioPacket(seq, ts, opusData))

                } catch (e: Exception) {
                    if (isActive) {
                        // Log but keep running
                        System.err.println("[UdpReceiver] error: ${e.message}")
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        socket?.close()
        socket = null
    }
}
