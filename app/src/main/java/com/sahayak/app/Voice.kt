package com.sahayak.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Speech in (SpeechRecognizer) and speech out (TextToSpeech). Call from the main thread. */
class Voice(private val context: Context, private val languageTag: () -> String) {

    private val ttsReady = CompletableDeferred<Boolean>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>() // TTS callbacks arrive on a binder thread

    private val tts = TextToSpeech(context) { status ->
        ttsReady.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { pending.remove(id)?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { pending.remove(id)?.complete(Unit) }
        })
    }

    /** Speaks [text] and returns once it has finished. */
    suspend fun speak(text: String) {
        if (!ttsReady.await() || text.isBlank()) return
        tts.language = Locale.forLanguageTag(languageTag())
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        done.await()
    }

    fun stopSpeaking() = tts.stop()

    /** Listens once and returns what was said, or null if nothing was understood. */
    suspend fun listen(): String? = suspendCancellableCoroutine { cont ->
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            cont.resume(null); return@suspendCancellableCoroutine
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        fun finish(result: String?) {
            recognizer.destroy()
            if (cont.isActive) cont.resume(result)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                finish(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }
            override fun onError(error: Int) = finish(null)
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        recognizer.startListening(intent)
        cont.invokeOnCancellation { recognizer.destroy() }
    }

    fun shutdown() = tts.shutdown()
}
