package com.sixthsense.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * On-device text-to-speech for the agent's spoken answer (the last leg of the voice
 * loop: speech in → scene-grounded answer → spoken out). Uses Android's built-in
 * [TextToSpeech] — fully on-device, no network.
 */
class Tts(context: Context) {

    @Volatile private var ready = false
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { engine?.language = Locale.US }
                ready = true
            }
        }
    }

    /** Speak [text] now, interrupting any current utterance. No-op until the engine is ready. */
    fun speak(text: String) {
        if (text.isBlank()) return
        engine?.takeIf { ready }?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sixthsense")
    }

    fun shutdown() {
        runCatching { engine?.stop(); engine?.shutdown() }
        engine = null
        ready = false
    }
}
