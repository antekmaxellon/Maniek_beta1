package com.example.maniek

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.graphics.PixelFormat
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class WakeWordService : Service() {

    companion object {
        const val CHANNEL_LISTEN = "maniek_listen"
        const val NOTIF_LISTEN_ID = 1
        const val ACTION_STOP = "com.example.maniek.STOP"

        @Volatile var isRunning = false
        @Volatile private var instance: WakeWordService? = null

        fun notifyCommandFinished() {
            instance?.pauseForCommand = false
        }
    }

    // ---- tuning ----
    private val threshold = 0.2f
    private val hitsRequired = 1
    private val debounceMs = 2000L
    private val stepMillis = 400

    @Volatile private var keepRunning = false
    @Volatile private var pauseForCommand = false
    private var worker: Thread? = null
    private var exemptionOverlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        attachExemptionOverlay()
    }
    private fun attachExemptionOverlay() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val view = View(this)
            val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val params = WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(view, params)
            exemptionOverlay = view
        } catch (e: Exception) {
            Log.e("Maniek", "could not attach exemption overlay", e)
        }
    }

    private fun detachExemptionOverlay() {
        try {
            exemptionOverlay?.let {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        } catch (_: Exception) {}
        exemptionOverlay = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            keepRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_LISTEN_ID, listeningNotification())
        startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        keepRunning = false
        isRunning = false
        instance = null
        detachExemptionOverlay()
        worker?.join(1000)
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTEN, "Maniek status", NotificationManager.IMPORTANCE_MIN)
        )
    }

    private fun listeningNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_LISTEN)
            .setContentTitle("Maniek")
            .setContentText("Listening…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openAppPendingIntent())
            .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ---------------- wake word detected ----------------

    private fun onDetected() {
        launchCommandActivity()
    }

    private fun launchCommandActivity() {
        val intent = Intent(this, CommandActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("Maniek", "could not launch CommandActivity", e)
            pauseForCommand = false
        }
    }

    // ---------------- wake word detection loop ----------------

    private fun startLoop() {
        if (keepRunning) return
        keepRunning = true
        isRunning = true

        worker = thread(name = "wakeword-service") {
            val sr = WakeWordDetector.SAMPLE_RATE
            val detector = try {
                WakeWordDetector(this)
            } catch (e: Exception) {
                Log.e("Maniek", "Model load failed in service", e)
                keepRunning = false; isRunning = false
                stopSelf()
                return@thread
            }

            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("Maniek", "No RECORD_AUDIO permission, stopping service")
                keepRunning = false; isRunning = false
                stopSelf()
                return@thread
            }

            val minBuf = AudioRecord.getMinBufferSize(
                sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            @Suppress("MissingPermission")
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sr * 4)
            )

            val ring = ShortArray((sr * 2.2).toInt())
            val chunk = ShortArray(sr * stepMillis / 1000)
            var filled = 0
            var lastDetect = 0L
            var hits = 0
            var recStoppedForCommand = false

            rec.startRecording()

            while (keepRunning) {
                if (pauseForCommand) {
                    if (!recStoppedForCommand) {
                        try { rec.stop() } catch (_: Exception) {}
                        recStoppedForCommand = true
                    }
                    Thread.sleep(100)
                    continue
                } else if (recStoppedForCommand) {
                    try { rec.startRecording() } catch (_: Exception) {}
                    recStoppedForCommand = false
                    filled = 0
                }

                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue

                System.arraycopy(ring, n, ring, 0, ring.size - n)
                System.arraycopy(chunk, 0, ring, ring.size - n, n)
                filled = minOf(filled + n, ring.size)
                if (filled < ring.size) continue

                val score = try {
                    detector.predict(ring)
                } catch (e: Exception) {
                    Log.e("Maniek", "predict failed in service", e)
                    break
                }

                val now = System.currentTimeMillis()
                hits = if (score >= threshold) hits + 1 else 0
                if (hits >= hitsRequired && now - lastDetect > debounceMs) {
                    lastDetect = now
                    hits = 0
                    try { rec.stop() } catch (_: Exception) {}
                    recStoppedForCommand = true
                    pauseForCommand = true
                    onDetected()
                }
            }

            rec.stop(); rec.release(); detector.close()
        }
    }
}