package com.aniket.wifiaudio.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UsbDualAudioService : Service() {
    companion object {
        const val ACTION_STATUS = "com.aniket.wifiaudio.usb.STATUS"
        const val EXTRA_STATUS = "status"
        const val ACTION_ERROR = "com.aniket.wifiaudio.usb.ERROR"
        const val EXTRA_ERROR = "error"
        const val EXTRA_MODE = "mode"
        const val EXTRA_HOST = "host"
        const val EXTRA_PROJECTION_RESULT = "projection_result"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val MODE_HOST = "host"
        const val MODE_CLIENT = "client"

        private const val CHANNEL_ID = "usb_dual_audio"
        private const val NOTIFICATION_ID = 4101
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val FRAME_MS = 20
        private const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * CHANNELS * 2
        private const val LOOKAHEAD_NS = 150_000_000L

        fun startHost(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, UsbDualAudioService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_HOST)
                putExtra(EXTRA_PROJECTION_RESULT, resultCode)
                putExtra(EXTRA_PROJECTION_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startClient(context: Context, host: String) {
            val intent = Intent(context, UsbDualAudioService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_CLIENT)
                putExtra(EXTRA_HOST, host)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsbDualAudioService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var projection: MediaProjection? = null
    private var running = false
    private var sequence = 0L
    @Volatile private var clientOffsetNs = 0L
    @Volatile private var haveClockSync = false
    private var clientPlaybackStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("USB audio idle"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_HOST
        if (mode == MODE_HOST) {
            val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT, -1) ?: -1
            val data = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
            if (resultCode < 0 || data == null) {
                fail("Screen/audio capture permission is required for Host")
                return START_NOT_STICKY
            }
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager?.getMediaProjection(resultCode, data)
            if (projection == null) {
                fail("Unable to create MediaProjection")
                return START_NOT_STICKY
            }
            scope.launch { runHost() }
        } else {
            scope.launch { runClient(intent?.getStringExtra(EXTRA_HOST)) }
        }
        return START_STICKY
    }

    private suspend fun runHost() {
        try {
            update("Host: waiting for USB client on ${UsbAudioProtocol.PORT}")
            serverSocket = ServerSocket(UsbAudioProtocol.PORT)
            socket = serverSocket!!.accept().apply { tcpNoDelay = true; keepAlive = true }
            output = DataOutputStream(socket!!.getOutputStream())
            input = DataInputStream(socket!!.getInputStream())
            update("Host: client connected, synchronizing")
            sendSessionStart()
            launchHostControlReader()
            prepareAudioTrack()
            captureAndSend()
        } catch (t: Throwable) {
            if (running) fail("Host: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun launchHostControlReader() {
        scope.launch {
            try {
                while (isActive && running) {
                    val header = UsbAudioProtocol.readHeader(input!!)
                    val payload = UsbAudioProtocol.readPayload(input!!, header.length)
                    when (header.type) {
                        UsbAudioProtocol.SYNC_REQUEST -> {
                            if (payload.size == 8) {
                                val clientSend = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).long
                                val hostRecv = System.nanoTime()
                                val hostSend = System.nanoTime()
                                val response = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                                    .putLong(clientSend).putLong(hostRecv).putLong(hostSend).array()
                                UsbAudioProtocol.writeMessage(output!!, UsbAudioProtocol.SYNC_RESPONSE, response)
                            }
                        }
                        UsbAudioProtocol.HEARTBEAT -> Unit
                        UsbAudioProtocol.SESSION_STOP -> stopSelf()
                    }
                }
            } catch (_: Throwable) {
                if (running) fail("Host: control connection lost")
            }
        }
    }

    private fun sendSessionStart() {
        val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(SAMPLE_RATE)
            .put(CHANNELS.toByte())
            .put(16.toByte())
            .putShort(FRAME_MS.toShort())
            .array()
        UsbAudioProtocol.writeMessage(output!!, UsbAudioProtocol.SESSION_START, payload)
    }

    private fun prepareAudioTrack() {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun captureAndSend() {
        val mediaProjection = projection ?: throw IllegalStateException("MediaProjection missing")
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 4))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        audioRecord!!.startRecording()
        update("Host: capturing and timestamping 48kHz stereo PCM")

        val buffer = ByteArray(FRAME_BYTES)
        var hostStartAt = 0L
        while (running) {
            val read = audioRecord!!.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) continue
            val frame = buffer.copyOf(read)
            val playbackTimestamp = System.nanoTime() + LOOKAHEAD_NS
            if (hostStartAt == 0L) hostStartAt = playbackTimestamp

            val payload = ByteBuffer.allocate(4 + 8 + 2 + frame.size).order(ByteOrder.BIG_ENDIAN)
                .putInt((sequence and 0xffffffffL).toInt())
                .putLong(playbackTimestamp)
                .putShort((frame.size / (CHANNELS * 2)).toShort())
                .put(frame)
                .array()
            UsbAudioProtocol.writeMessage(output!!, UsbAudioProtocol.AUDIO_FRAME, payload)

            if (System.nanoTime() >= hostStartAt) {
                audioTrack?.play()
                audioTrack?.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
            }
            sequence++
        }
    }

    private suspend fun runClient(host: String?) {
        try {
            val target = host ?: UsbNetworkUtil.defaultGateway(this)
                ?: throw IllegalStateException("USB host address not found")
            update("Client: connecting to $target:${UsbAudioProtocol.PORT}")
            socket = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                connect(InetSocketAddress(target, UsbAudioProtocol.PORT), 5000)
            }
            output = DataOutputStream(socket!!.getOutputStream())
            input = DataInputStream(socket!!.getInputStream())
            update("Client: connected, synchronizing clock")
            launchClientControlSender()
            receiveClientStream()
        } catch (t: Throwable) {
            if (running) fail("Client: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun launchClientControlSender() {
        scope.launch {
            try {
                repeat(8) {
                    val send = System.nanoTime()
                    val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(send).array()
                    UsbAudioProtocol.writeMessage(output!!, UsbAudioProtocol.SYNC_REQUEST, payload)
                    delay(200)
                }
            } catch (_: Throwable) { }
        }
        scope.launch {
            while (isActive && running) {
                delay(1000)
                try { UsbAudioProtocol.writeMessage(output!!, UsbAudioProtocol.HEARTBEAT) } catch (_: Throwable) { break }
            }
        }
    }

    private fun receiveClientStream() {
        var configured = false
        val pending = ArrayDeque<PendingFrame>()
        while (running) {
            val header = UsbAudioProtocol.readHeader(input!!)
            val payload = UsbAudioProtocol.readPayload(input!!, header.length)
            when (header.type) {
                UsbAudioProtocol.SESSION_START -> {
                    if (payload.size >= 8) {
                        configureClientAudio()
                        configured = true
                        update("Client: session ready, buffering")
                    }
                }
                UsbAudioProtocol.SYNC_RESPONSE -> handleSyncResponse(payload)
                UsbAudioProtocol.AUDIO_FRAME -> {
                    if (!configured || payload.size < 14) continue
                    val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                    val sequenceNumber = bb.int
                    val timestamp = bb.long
                    bb.short
                    val pcm = ByteArray(bb.remaining())
                    bb.get(pcm)
                    pending.addLast(PendingFrame(sequenceNumber, timestamp, pcm))
                    if (!clientPlaybackStarted && haveClockSync && pending.size >= 3) {
                        val first = pending.first()
                        val localTarget = first.timestamp - clientOffsetNs
                        val waitNs = localTarget - System.nanoTime()
                        if (waitNs > 0) {
                            try { Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt()) } catch (_: InterruptedException) { }
                        }
                        audioTrack?.play()
                        clientPlaybackStarted = true
                        update("Client: synchronized playback")
                    }
                    if (clientPlaybackStarted) {
                        while (pending.isNotEmpty()) {
                            val frame = pending.removeFirst()
                            audioTrack?.write(frame.pcm, 0, frame.pcm.size, AudioTrack.WRITE_BLOCKING)
                        }
                    } else if (pending.size > 10) {
                        pending.removeFirst()
                    }
                }
                UsbAudioProtocol.HEARTBEAT -> Unit
                UsbAudioProtocol.SESSION_STOP -> stopSelf()
            }
        }
    }

    private fun handleSyncResponse(payload: ByteArray) {
        if (payload.size != 24) return
        val now = System.nanoTime()
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val clientSend = bb.long
        val hostRecv = bb.long
        val hostSend = bb.long
        val rtt = (now - clientSend) - (hostSend - hostRecv)
        if (rtt <= 0) return
        val offset = ((hostRecv - clientSend) + (hostSend - now)) / 2
        if (!haveClockSync || rtt < 5_000_000L) {
            clientOffsetNs = offset
            haveClockSync = true
        }
    }

    private fun configureClientAudio() {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack?.release()
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun update(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun fail(message: String) {
        sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName).putExtra(EXTRA_ERROR, message))
        stopSelf()
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("USB-C Dual Audio")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "USB-C Dual Audio", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        running = false
        try { output?.let { UsbAudioProtocol.writeMessage(it, UsbAudioProtocol.SESSION_STOP) } } catch (_: Throwable) { }
        try { audioRecord?.stop() } catch (_: Throwable) { }
        audioRecord?.release()
        try { audioTrack?.stop() } catch (_: Throwable) { }
        audioTrack?.release()
        projection?.stop()
        try { socket?.close() } catch (_: Throwable) { }
        try { serverSocket?.close() } catch (_: Throwable) { }
        scheduler.shutdownNow()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class PendingFrame(val sequence: Int, val timestamp: Long, val pcm: ByteArray)
}
