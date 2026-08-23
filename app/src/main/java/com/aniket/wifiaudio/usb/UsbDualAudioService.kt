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
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun startClient(context: Context, host: String) {
            val intent = Intent(context, UsbDualAudioService::class.java).apply {
                putExtra(EXTRA_MODE, MODE_CLIENT)
                putExtra(EXTRA_HOST, host)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
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
    private var mode: String = MODE_HOST
    private var hostAddress: String? = null
    private var sequence = 0L
    private var running = false
    private var clientOffsetNs = 0L
    private var clientPlaybackStarted = false
    private var hostPlaybackStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("USB audio idle"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_HOST
        hostAddress = intent?.getStringExtra(EXTRA_HOST)
        running = true
        if (mode == MODE_HOST) {
            val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT, -1) ?: -1
            val data = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
            if (resultCode < 0 || data == null) {
                fail("Screen/audio capture permission is required for Host")
                return START_NOT_STICKY
            }
            projection = getSystemService(MediaProjectionManagerCompat::class.java)?.create(resultCode, data)
            if (projection == null) {
                fail("Unable to create MediaProjection")
                return START_NOT_STICKY
            }
            scope.launch { runHost() }
        } else {
            scope.launch { runClient(hostAddress) }
        }
        return START_STICKY
    }

    private suspend fun runHost() {
        try {
            update("Host: waiting for USB client on ${UsbAudioProtocol.PORT}")
            serverSocket = ServerSocket(UsbAudioProtocol.PORT)
            socket = serverSocket!!.accept().apply {
                tcpNoDelay = true
                keepAlive = true
            }
            output = DataOutputStream(socket!!.getOutputStream())
            input = DataInputStream(socket!!.getInputStream())
            update("Host: client connected, synchronizing")
            sendSessionStart()
            launchControlReaderHost()
            prepareHostAudio()
            startHostPlaybackAfterLookahead()
            captureAndSend()
        } catch (t: Throwable) {
            if (running) fail("Host: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun launchControlReaderHost() {
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
                        UsbAudioProtocol.HEARTBEAT -> UsbAudioProtocol.writeMessage(output!!, UsbAudioProtocol.HEARTBEAT)
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
            .putInt(SAMPLE_RATE).put( CHANNELS.toByte()).put(16.toByte()).putShort(FRAME_MS.toShort()).array()
        UsbAudioProtocol.writeMessage(output!!, UsbAudioProtocol.SESSION_START, payload)
    }

    private fun prepareHostAudio() {
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
        audioTrack!!.play()
    }

    private fun startHostPlaybackAfterLookahead() {
        scheduler.schedule({ hostPlaybackStarted = true }, 150, TimeUnit.MILLISECONDS)
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
        val buffer = ByteArray(FRAME_BYTES)
        update("Host: synchronized capture running")
        while (running) {
            val read = audioRecord!!.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) continue
            val frame = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
            val playbackTimestamp = System.nanoTime() + LOOKAHEAD_NS
            val payload = ByteBuffer.allocate(4 + 8 + 2 + frame.size).order(ByteOrder.BIG_ENDIAN)
                .putInt((sequence and 0xffffffffL).toInt())
                .putLong(playbackTimestamp)
                .putShort((frame.size / (CHANNELS * 2)).toShort())
                .put(frame)
                .array()
            UsbAudioProtocol.writeMessage(output!!, UsbAudioProtocol.AUDIO_FRAME, payload)
            if (hostPlaybackStarted) audioTrack?.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
            sequence++
        }
    }

    private suspend fun runClient(host: String?) {
        try {
            val target = host ?: UsbNetworkUtil.defaultGateway(this) ?: throw IllegalStateException("USB host address not found")
            update("Client: connecting to $target:${UsbAudioProtocol.PORT}")
            socket = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                connect(InetSocketAddress(target, UsbAudioProtocol.PORT), 5000)
            }
            output = DataOutputStream(socket!!.getOutputStream())
            input = DataInputStream(socket!!.getInputStream())
            update("Client: connected, synchronizing clock")
            launchClientSyncLoop()
            receiveClientStream()
        } catch (t: Throwable) {
            if (running) fail("Client: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun launchClientSyncLoop() {
        scope.launch {
            try {
                repeat(8) {
                    val send = System.nanoTime()
                    val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(send).array()
                    UsbAudioProtocol.writeMessage(output!!, UsbAudioProtocol.SYNC_REQUEST, payload)
                    delay(250)
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
        val jitter = ArrayDeque<ByteArray>()
        var firstTimestamp = 0L
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
                UsbAudioProtocol.SYNC_RESPONSE -> {
                    if (payload.size == 24) {
                        val now = System.nanoTime()
                        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                        val clientSend = bb.long
                        val hostRecv = bb.long
                        val hostSend = bb.long
                        val rtt = (now - clientSend) - (hostSend - hostRecv)
                        val offset = ((hostRecv - clientSend) + (hostSend - now)) / 2
                        if (rtt > 0) clientOffsetNs = offset
                    }
                }
                UsbAudioProtocol.AUDIO_FRAME -> {
                    if (!configured || payload.size < 14) continue
                    val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                    bb.int
                    val timestamp = bb.long
                    bb.short
                    val pcm = ByteArray(bb.remaining())
                    bb.get(pcm)
                    if (firstTimestamp == 0L) firstTimestamp = timestamp
                    jitter.addLast(pcm)
                    if (!clientPlaybackStarted) {
                        val localTarget = timestamp - clientOffsetNs
                        val waitNs = localTarget - System.nanoTime()
                        if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
                        audioTrack?.play()
                        clientPlaybackStarted = true
                        update("Client: synchronized playback")
                    }
                    while (jitter.size > 3) audioTrack?.write(jitter.removeFirst(), 0, jitter.firstOrNull()?.size ?: 0, AudioTrack.WRITE_BLOCKING)
                    val last = jitter.removeFirstOrNull()
                    if (last != null) audioTrack?.write(last, 0, last.size, AudioTrack.WRITE_BLOCKING)
                }
                UsbAudioProtocol.HEARTBEAT -> Unit
                UsbAudioProtocol.SESSION_STOP -> stopSelf()
            }
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
        val intent = Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message)
        sendBroadcast(intent)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    private fun fail(message: String) {
        sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName).putExtra(EXTRA_ERROR, message))
        stopSelf()
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
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
        audioRecord?.stop()
        audioRecord?.release()
        audioTrack?.stop()
        audioTrack?.release()
        projection?.stop()
        socket?.close()
        serverSocket?.close()
        scheduler.shutdownNow()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class MediaProjectionManagerCompat(private val context: Context) {
        fun create(resultCode: Int, data: Intent): MediaProjection? {
            val manager = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
            return manager?.getMediaProjection(resultCode, data)
        }
    }
}
