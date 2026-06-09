// android-receiver/app/src/main/java/com/audioshare/core/service/AudioForegroundService.kt
package com.audioshare.core.service

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.audioshare.core.audio.AudioPipeline
import com.audioshare.core.audio.DecodeThread
import com.audioshare.core.buffer.JitterBuffer
import com.audioshare.core.buffer.PcmRingBuffer
import com.audioshare.core.control.AdaptiveReflex
import com.audioshare.core.control.CodecPolicy
import com.audioshare.core.network.UdpReceiver
import com.audioshare.ui.MainActivity
import kotlinx.coroutines.*

class AudioForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.audioshare.START"
        const val ACTION_STOP  = "com.audioshare.STOP"
        const val EXTRA_IP     = "pc_ip"
        const val EXTRA_PORT   = "port"
        const val NOTIF_CHANNEL = "audioshare_channel"
        const val NOTIF_ID      = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var jitterBuffer: JitterBuffer? = null
    private var pcmRing: PcmRingBuffer? = null
    private var udpReceiver: UdpReceiver? = null
    private var decodeThread: DecodeThread? = null
    private var pipeline: AudioPipeline? = null
    private var reflex: AdaptiveReflex? = null
    private var codecPolicy: CodecPolicy? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ip   = intent.getStringExtra(EXTRA_IP) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 5005)
                startAudio(ip, port)
            }
            ACTION_STOP -> stopAudio()
        }
        return START_STICKY
    }

    private fun startAudio(pcIp: String, port: Int) {
        acquireWakeLock()
        startForeground(NOTIF_ID, buildNotification("Receiving from $pcIp:$port"))

        val jb  = JitterBuffer(targetMs = 200)
        val pcm = PcmRingBuffer()

        val udp  = UdpReceiver(port, jb)
        val dec  = DecodeThread(jb, pcm)
        val ap   = AudioPipeline()
        val ref  = AdaptiveReflex(jb, ap)
        val cp   = CodecPolicy(jb).also { it.configure(pcIp, port + 1) }

        udp.start(scope)
        dec.start()
        ap.start()
        ref.start(scope)
        cp.start(scope)

        jitterBuffer = jb
        pcmRing      = pcm
        udpReceiver  = udp
        decodeThread = dec
        pipeline     = ap
        reflex       = ref
        codecPolicy  = cp
    }

    private fun stopAudio() {
        codecPolicy?.stop()
        reflex?.stop()
        pipeline?.stop()
        decodeThread?.stop()
        udpReceiver?.stop()
        scope.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopAudio()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Notification ---

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            NOTIF_CHANNEL,
            "AudioShare",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Audio streaming service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("AudioShare")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // --- WakeLock (partial — CPU only, no screen) ---

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AudioShare::StreamLock"
        ).also { it.acquire(4 * 60 * 60 * 1000L) } // 4h max
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
