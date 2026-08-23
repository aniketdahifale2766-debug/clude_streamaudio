package com.aniket.wifiaudio

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.time.Duration
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures device system audio via MediaProjection + AudioPlaybackCaptureConfiguration
 * and broadcasts raw PCM frames to any connected WebSocket client on the local network.
 *
 * Each client gets its own bounded, non-blocking outgoing queue and sender coroutine,
 * decoupled from the audio-capture loop. A slow/congested client can only ever drop
 * its own frames — it can never stall capture or delivery to other listeners.
 */
class AudioStreamService : Service() {

    companion object {
        private const val TAG = "AudioStreamService"
        private const val CHANNEL_ID = "audio_stream_channel"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 48000
        private const val PORT = 8080

        // Bump this string on every change that gets pushed, so it's obvious from the
        // notification/UI whether you're actually running the build you think you are.
        const val BUILD_STAMP = "build-6-per-client-queues"

        const val ACTION_ERROR = "com.aniket.wifiaudio.ACTION_ERROR"
        const val ACTION_STATUS = "com.aniket.wifiaudio.ACTION_STATUS"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_STATUS_MESSAGE = "status_message"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, AudioStreamService::class.java)
            intent.putExtra("resultCode", resultCode)
            intent.putExtra("data", data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioStreamService::class.java))
        }
    }

    /** One per connected WebSocket client: its own queue + sender coroutine. */
    private class ClientConnection(
        val session: DefaultWebSocketServerSession,
        val scope: CoroutineScope
    ) {
        // Small bounded queue: at ~20ms/frame, 25 frames = ~500ms of slack before
        // this specific client starts dropping frames. Capture and other clients
        // are never affected by that.
        val queue = Channel<ByteArray>(capacity = 25)
        val dropped = AtomicInteger(0)
        val sent = AtomicInteger(0)

        val senderJob: Job = scope.launch {
            for (frame in queue) {
                try {
                    session.send(Frame.Binary(true, frame))
                    sent.incrementAndGet()
                } catch (e: Exception) {
                    break
                }
            }
        }

        fun offer(frame: ByteArray) {
            val result = queue.trySend(frame)
            if (!result.isSuccess) dropped.incrementAndGet()
        }

        fun close() {
            senderJob.cancel()
            queue.close()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: ApplicationEngine? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private val clients = CopyOnWriteArraySet<ClientConnection>()
    private var captureJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationManager = getSystemService(NotificationManager::class.java)
        startForeground(NOTIF_ID, buildNotification("Starting… ($BUILD_STAMP)"))

        try {
            startServer()
            updateNotification("Server up on port $PORT ($BUILD_STAMP)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start embedded server", e)
            reportError("Server failed to start: ${e.message}")
        }

        val resultCode = intent?.getIntExtra("resultCode", Int.MIN_VALUE) ?: Int.MIN_VALUE
        val data = intent?.getParcelableExtra<Intent>("data")
        if (resultCode == Activity.RESULT_OK && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection stopped")
                }
            }, null)
            try {
                startCapture()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio capture", e)
                reportError("Audio capture failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            reportError("No screen-capture permission result received (resultCode=$resultCode)")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        captureJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        mediaProjection?.stop()
        clients.forEach { it.close() }
        clients.clear()
        server?.stop(1000, 2000)
        serviceScope.cancel()
    }

    private fun reportError(message: String) {
        Log.e(TAG, message)
        updateNotification("ERROR: $message")
        val intent = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun reportStatus(message: String) {
        updateNotification(message)
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(NOTIF_ID, buildNotification(text))
    }

    private fun startCapture() {
        val projection = mediaProjection ?: run {
            reportError("No MediaProjection available")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            reportError("Playback capture requires Android 10+")
            return
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize <= 0) {
            reportError("Unsupported audio format for this device (minBufSize=$minBufSize)")
            return
        }

        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufSize * 4)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            reportError("AudioRecord failed to initialize (state=${record.state}). Check RECORD_AUDIO permission was granted.")
            record.release()
            return
        }

        audioRecord = record
        record.startRecording()

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            reportError("AudioRecord did not enter recording state")
            return
        }

        reportStatus("Capture started, waiting for audio… ($BUILD_STAMP)")

        // ~20ms chunks at 48kHz/16-bit/stereo = 48000*0.02*2*2 bytes = 3840 bytes
        val chunkSize = 3840
        val buffer = ByteArray(chunkSize)

        captureJob = serviceScope.launch {
            var framesRead = 0
            var nonSilentFrames = 0
            var lastReportAt = System.currentTimeMillis()

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, chunkSize) ?: -1
                if (read > 0) {
                    framesRead++
                    val frame = buffer.copyOf(read)
                    var isSilent = true
                    for (b in frame) {
                        if (b.toInt() != 0) { isSilent = false; break }
                    }
                    if (!isSilent) nonSilentFrames++

                    // Non-blocking hand-off: each client has its own queue, so one
                    // slow client can never stall this loop or any other client.
                    for (client in clients) {
                        client.offer(frame)
                    }
                } else if (read < 0) {
                    reportError("AudioRecord.read returned error code $read")
                    break
                }

                val now = System.currentTimeMillis()
                if (now - lastReportAt > 2000) {
                    val totalSent = clients.sumOf { it.sent.get() }
                    val totalDropped = clients.sumOf { it.dropped.get() }
                    reportStatus(
                        "reads=$framesRead sent=$totalSent dropped=$totalDropped nonSilent=$nonSilentFrames clients=${clients.size}"
                    )
                    lastReportAt = now
                }
            }
        }
    }

    private fun startServer() {
        server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(30)
            }
            routing {
                get("/") {
                    call.respondText(
                        this@AudioStreamService.assets.open("index.html").bufferedReader().readText(),
                        ContentType.Text.Html
                    )
                }
                webSocket("/stream") {
                    val client = ClientConnection(this, serviceScope)
                    clients.add(client)
                    reportStatus("Client connected, total=${clients.size}")
                    try {
                        for (frame in incoming) {
                            // client -> server messages not used, just keep connection open
                        }
                    } finally {
                        client.close()
                        clients.remove(client)
                        reportStatus("Client disconnected, total=${clients.size}")
                    }
                }
            }
        }.start(wait = false)
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Audio Streaming", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Audio Stream")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }
}
