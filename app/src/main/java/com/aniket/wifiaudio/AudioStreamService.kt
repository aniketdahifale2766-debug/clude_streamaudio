package com.aniket.wifiaudio

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
import java.time.Duration
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Captures device system audio via MediaProjection + AudioPlaybackCaptureConfiguration
 * and broadcasts raw PCM frames to any connected WebSocket client on the local network.
 */
class AudioStreamService : Service() {

    companion object {
        private const val CHANNEL_ID = "audio_stream_channel"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 48000
        private const val PORT = 8080

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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: ApplicationEngine? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private val clients = CopyOnWriteArraySet<DefaultWebSocketServerSession>()
    private var captureJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        if (resultCode != -1 && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
            startCapture()
            startServer()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        captureJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        mediaProjection?.stop()
        server?.stop(1000, 2000)
        serviceScope.cancel()
    }

    private fun startCapture() {
        val projection = mediaProjection ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

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

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufSize * 4)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        audioRecord?.startRecording()

        // ~20ms chunks at 48kHz/16-bit/stereo = 48000*0.02*2*2 bytes = 3840 bytes
        val chunkSize = 3840
        val buffer = ByteArray(chunkSize)

        captureJob = serviceScope.launch {
            while (isActive) {
                val read = audioRecord?.read(buffer, 0, chunkSize) ?: -1
                if (read > 0) {
                    val frame = buffer.copyOf(read)
                    for (client in clients) {
                        try {
                            client.send(Frame.Binary(true, frame))
                        } catch (e: Exception) {
                            clients.remove(client)
                        }
                    }
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
                    clients.add(this)
                    try {
                        for (frame in incoming) {
                            // client -> server messages not used, just keep connection open
                        }
                    } finally {
                        clients.remove(this)
                    }
                }
            }
        }.start(wait = false)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Audio Streaming", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Audio Stream")
            .setContentText("Streaming system audio on your local network")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }
}
