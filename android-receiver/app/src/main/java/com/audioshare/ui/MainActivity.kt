// android-receiver/app/src/main/java/com/audioshare/ui/MainActivity.kt
package com.audioshare.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.audioshare.core.service.AudioForegroundService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val ipInput   = findViewById<EditText>(R.id.etIp)
        val portInput = findViewById<EditText>(R.id.etPort)
        val btnStart  = findViewById<Button>(R.id.btnStart)
        val btnStop   = findViewById<Button>(R.id.btnStop)
        val tvStatus  = findViewById<TextView>(R.id.tvStatus)

        btnStart.setOnClickListener {
            val ip   = ipInput.text.toString().trim()
            val port = portInput.text.toString().toIntOrNull() ?: 5005

            if (ip.isEmpty()) {
                tvStatus.text = "Enter PC IP address"
                return@setOnClickListener
            }

            val intent = Intent(this, AudioForegroundService::class.java).apply {
                action = AudioForegroundService.ACTION_START
                putExtra(AudioForegroundService.EXTRA_IP,   ip)
                putExtra(AudioForegroundService.EXTRA_PORT, port)
            }
            startForegroundService(intent)
            tvStatus.text = "Connecting to $ip:$port…"
            btnStart.isEnabled = false
            btnStop.isEnabled  = true
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, AudioForegroundService::class.java).apply {
                action = AudioForegroundService.ACTION_STOP
            }
            startService(intent)
            tvStatus.text = "Stopped"
            btnStart.isEnabled = true
            btnStop.isEnabled  = false
        }
    }
}
