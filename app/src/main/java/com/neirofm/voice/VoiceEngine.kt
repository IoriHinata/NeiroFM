package com.neirofm.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

data class Presenter(val id: String, val name: String, val style: String)

class VoiceEngine(context: Context) : TextToSpeech.OnInitListener {
    val presenters = listOf(
        Presenter("alex", "Алекс", "спокойный музыкальный ведущий"),
        Presenter("maria", "Мария", "энергичная ведущая"),
        Presenter("max", "Макс", "ироничный ночной ведущий")
    )
    @Volatile var ready = false
        private set
    @Volatile var offlineRussianAvailable = false
        private set
    @Volatile var status: String = "Инициализация голосового движка"
        private set

    private data class PendingSpeech(val text: String, val presenter: String, val done: (() -> Unit)?)
    private val pending = ConcurrentLinkedQueue<PendingSpeech>()
    private val completionCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private val voicesByPresenter = mutableMapOf<String, Voice?>()
    private val neuralEngine = NeuralVoiceEngine(context.applicationContext)

    override fun onInit(result: Int) {
        if (result != TextToSpeech.SUCCESS) {
            status = "Системный синтез речи недоступен"
            return
        }
        val engine = tts ?: return
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { completionCallbacks.remove(it)?.invoke() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { completionCallbacks.remove(it)?.invoke() } }
        })
        val russianVoices = engine.voices.orEmpty().filter { it.locale.language == "ru" }
        val offlineVoices = russianVoices.filterNot { it.isNetworkConnectionRequired }
        offlineRussianAvailable = offlineVoices.isNotEmpty()
        val selected = if (offlineVoices.isNotEmpty()) offlineVoices else russianVoices
        presenters.forEachIndexed { index, presenter ->
            voicesByPresenter[presenter.id] = selected.getOrNull(index % selected.size.coerceAtLeast(1))
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val languageResult = engine.setLanguage(Locale("ru", "RU"))
        ready = true
        status = when {
            offlineRussianAvailable && languageResult >= TextToSpeech.LANG_AVAILABLE -> "Русский офлайн-голос готов"
            selected.isNotEmpty() -> "Русский голос найден, но может требовать интернет"
            else -> "Русский голос не установлен. Установите офлайн-пакет русского TTS в настройках Android"
        }
        while (true) {
            val item = pending.poll() ?: break
            speakNow(item.text, item.presenter, item.done)
        }
    }

    @Synchronized
    fun speak(text: String, presenterId: String = "alex", onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) return
        if (neuralEngine.ready) { neuralEngine.speak(text, presenterId, onComplete); return }
        if (!ready) {
            pending.add(PendingSpeech(text, presenterId, onComplete))
            return
        }
        speakNow(text, presenterId, onComplete)
    }

    @Synchronized
    private fun speakNow(text: String, presenterId: String, onComplete: (() -> Unit)? = null) {
        val engine = tts ?: return
        val voice = voicesByPresenter[presenterId]
        if (voice != null) engine.voice = voice
        when (presenterId) {
            "maria" -> { engine.setPitch(1.12f); engine.setSpeechRate(1.02f) }
            "max" -> { engine.setPitch(0.88f); engine.setSpeechRate(0.94f) }
            else -> { engine.setPitch(0.98f); engine.setSpeechRate(0.98f) }
        }
        val utteranceId = "neirofm-$presenterId-${System.nanoTime()}"
        if (onComplete != null) completionCallbacks[utteranceId] = onComplete
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result == TextToSpeech.ERROR) completionCallbacks.remove(utteranceId)?.invoke()
    }

    fun installNeuralVoice() { neuralEngine.install { status = it } }

    fun shutdown() {
        neuralEngine.release()
        pending.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
