package com.example.maniek

import android.app.Activity
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.graphics.drawable.toDrawable

class CommandActivity : Activity() {

    private var recognizer: SpeechRecognizer? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_command)

        window.setBackgroundDrawable(
            android.graphics.Color.TRANSPARENT.toDrawable()
        )
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        startRecognition()


        Handler(mainLooper).postDelayed({ finishCommand() }, 6000)
    }

    private fun startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finishCommand()
            return
        }

        val googleComponent = android.content.ComponentName(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        )

        val googleAvailable = try {
            packageManager.getPackageInfo(googleComponent.packageName, 0)
            true
        } catch (e: Exception) {
            false
        }

        val r = if (googleAvailable) {
            SpeechRecognizer.createSpeechRecognizer(this, googleComponent)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }

        recognizer = r

        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onError(error: Int) { finishCommand() }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    AppCommandHandler.handle(this@CommandActivity, text)
                }

                Handler(mainLooper).postDelayed({ finishCommand() }, 400)
            }
        })

        r.startListening(intent)
    }

    private fun finishCommand() {
        if (finished) return
        finished = true

        try { recognizer?.destroy() } catch (_: Exception) {}
        WakeWordService.notifyCommandFinished()
        finish()
    }

    override fun onDestroy() {
        finishCommand()
        super.onDestroy()
    }
}