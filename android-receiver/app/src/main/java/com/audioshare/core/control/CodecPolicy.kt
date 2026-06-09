// android-receiver/app/src/main/java/com/audioshare/core/control/CodecPolicy.kt
package com.audioshare.core.control

import com.audioshare.core.buffer.JitterBuffer
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Slow control loop (~2-3s): computes rolling loss → sends FEC PLP update to PC.
 * Never reacts to single events. Quantized steps to avoid thrashing.
 */
class CodecPolicy(
    private val jitterBuffer: JitterBuffer
) {
    private var senderSocket: DatagramSocket? = null
    private var pcAddress: InetAddress? = null
    private var pcPort: Int = 0

    private var lastPlp   = 5
    private var job: Job? = null

    fun configure(pcIp: String, port: Int) {
        pcAddress = InetAddress.getByName(pcIp)
        pcPort    = port
        senderSocket = DatagramSocket()
    }

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2500)
                tick()
            }
        }
    }

    fun stop() {
        job?.cancel()
        senderSocket?.close()
    }

    private fun tick() {
        val loss = jitterBuffer.getLossPercent()
        val plp  = when {
            loss < 6f  ->  5
            loss < 16f -> 18
            else       -> 30
        }

        // Only send if changed
        if (plp != lastPlp) {
            lastPlp = plp
            sendControl(0x02, plp)
            android.util.Log.d("CodecPolicy", "loss=%.1f%% → PLP=$plp".format(loss))
        }
    }

    /**
     * Control packet: [type:1][value:3 bytes big-endian]
     */
    private fun sendControl(type: Int, value: Int) {
        val addr = pcAddress ?: return
        val buf = byteArrayOf(
            type.toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr  8) and 0xFF).toByte(),
            ((value       ) and 0xFF).toByte()
        )
        try {
            val pkt = DatagramPacket(buf, buf.size, addr, pcPort)
            senderSocket?.send(pkt)
        } catch (e: Exception) {
            System.err.println("[CodecPolicy] send error: ${e.message}")
        }
    }
}
