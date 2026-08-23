package com.aniket.wifiaudio.usb

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aniket.wifiaudio.databinding.ActivityUsbDualAudioBinding

class UsbDualAudioActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUsbDualAudioBinding

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            UsbDualAudioService.startHost(this, result.resultCode, result.data!!)
            binding.status.text = "Host started — connect the other phone by USB-C"
            val usbIps = UsbNetworkUtil.usbIpv4Addresses()
            binding.hostIp.text = if (usbIps.isEmpty()) {
                "USB IP not visible yet. Keep USB tethering enabled and check again."
            } else {
                "USB Host IP: ${usbIps.joinToString() }"
            }
        } else {
            binding.status.text = "Audio capture permission cancelled"
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbDualAudioService.ACTION_STATUS -> binding.status.text =
                    intent.getStringExtra(UsbDualAudioService.EXTRA_STATUS) ?: ""
                UsbDualAudioService.ACTION_ERROR -> binding.status.text =
                    intent.getStringExtra(UsbDualAudioService.EXTRA_ERROR) ?: "Error"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsbDualAudioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.hostButton.setOnClickListener {
            val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }

        binding.clientButton.setOnClickListener {
            val entered = binding.hostAddress.text.toString().trim()
            val address = entered.ifEmpty { UsbNetworkUtil.defaultGateway(this) }
            if (address.isNullOrBlank()) {
                Toast.makeText(this, "USB host address not found. Enter the Host IP.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            UsbDualAudioService.startClient(this, address)
            binding.status.text = "Client connecting to $address..."
        }

        binding.stopButton.setOnClickListener {
            UsbDualAudioService.stop(this)
            binding.status.text = "USB audio stopped"
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(UsbDualAudioService.ACTION_STATUS)
            addAction(UsbDualAudioService.ACTION_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(receiver)
    }
}
