package com.sixthsense.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device OCR via ML Kit's **bundled** Latin text-recognition model. Fully on the
 * phone — no Play Services download, works in airplane mode — so "read that sign"
 * stays within the on-device assistive runtime (CLAUDE.md). The recognizer runs on
 * a still camera frame on demand (not every frame).
 */
class OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Recognize text in [bitmap]; [onText] receives the (possibly blank) text, newlines flattened. */
    fun recognize(bitmap: Bitmap, onText: (String) -> Unit) {
        val img = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(img)
            .addOnSuccessListener { onText(it.text.replace('\n', ' ').trim()) }
            .addOnFailureListener { Log.w(TAG, "OCR failed: ${it.message}"); onText("") }
    }

    fun close() = runCatching { recognizer.close() }

    private companion object { const val TAG = "SixthSenseScene" }
}
