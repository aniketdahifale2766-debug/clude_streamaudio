package com.aniket.wifiaudio

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.aniket.wifiaudio.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var streaming = false

    private val projectionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                AudioStreamService.start(this, result.resultCode, result.data!!)
                streaming = true
                updateUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        binding.toggleButton.setOnClickListener {
            if (!streaming) {
                val mgr = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            } else {
                AudioStreamService.stop(this)
                streaming = false
                updateUi()
            }
        }

        updateUi()
    }

    private fun updateUi() {
        binding.toggleButton.text = if (streaming) "Stop Streaming" else "Start Streaming"
        binding.statusText.text = if (streaming) "Streaming" else "Not streaming"
        binding.linkText.text = if (streaming) "Open: http://${localIpAddress()}:8080" else ""
    }

    private fun localIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
    }
}
