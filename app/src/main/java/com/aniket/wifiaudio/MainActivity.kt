package com.aniket.wifiaudio

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    private val recordAudioPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchProjectionCapture()
            } else {
                binding.statusText.text = "Microphone/audio permission denied — capture can't start"
            }
        }

    // Service reports real capture/server errors here instead of failing silently.
    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioStreamService.ACTION_ERROR -> {
                    val message = intent.getStringExtra(AudioStreamService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"
                    binding.statusText.text = "Error: $message"
                }
                AudioStreamService.ACTION_STATUS -> {
                    val message = intent.getStringExtra(AudioStreamService.EXTRA_STATUS_MESSAGE) ?: ""
                    binding.statusText.text = message
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        binding.toggleButton.setOnClickListener {
            if (!streaming) {
                startCaptureFlow()
            } else {
                AudioStreamService.stop(this)
                streaming = false
                updateUi()
            }
        }

        updateUi()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(AudioStreamService.ACTION_ERROR)
            addAction(AudioStreamService.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(errorReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(errorReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(errorReceiver)
    }

    private fun startCaptureFlow() {
        val hasRecordAudio = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasRecordAudio) {
            launchProjectionCapture()
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchProjectionCapture() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
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
