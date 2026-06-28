package com.sixthsense.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Push-to-talk speech input using Android's **on-device** recognizer (offline /
 * airplane-mode friendly). This is NOT Whisper and NOT a cloud service — it's the
 * platform's on-device STT, which keeps voice input inside the on-device runtime.
 *
 * SpeechRecognizer must be created and driven on the MAIN thread; [listen] and the
 * callbacks all run there.
 */
class SpeechInput(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    @Volatile
    var listening = false
        private set

    fun available(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context) ||
            (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))

    /** Listen once. [onResult] gets the transcript (may be blank); [onError] a short message. Main thread. */
    fun listen(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (listening) return
        val rec = try {
            if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else
                SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Throwable) {
            onError("speech recognizer unavailable")
            return
        }
        recognizer?.destroy()
        recognizer = rec
        listening = true
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                listening = false
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                onResult(text)
            }
            override fun onError(error: Int) { listening = false; onError("speech error $error") }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)   // keep it on-device / airplane mode
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            rec.startListening(intent)
        } catch (e: Throwable) {
            listening = false
            Log.w(TAG, "startListening failed: ${e.message}")
            onError("could not start listening")
        }
    }

    fun shutdown() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
    }

    private companion object { const val TAG = "SixthSenseMCP" }
}
