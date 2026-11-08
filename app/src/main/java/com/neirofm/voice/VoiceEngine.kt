package com.neirofm.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
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

    private val pending = ConcurrentLinkedQueue<Pair<String, String>>()
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private val voicesByPresenter = mutableMapOf<String, Voice?>()

    override fun onInit(result: Int) {
        if (result != TextToSpeech.SUCCESS) {
            status = "Системный синтез речи недоступен"
            return
        }
        val engine = tts ?: return
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
            speakNow(item.first, item.second)
        }
    }

    @Synchronized
    fun speak(text: String, presenterId: String = "alex") {
        if (text.isBlank()) return
        if (!ready) {
            pending.add(text to presenterId)
            return
        }
        speakNow(text, presenterId)
    }

    @Synchronized
    private fun speakNow(text: String, presenterId: String) {
        val engine = tts ?: return
        val voice = voicesByPresenter[presenterId]
        if (voice != null) engine.voice = voice
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "neirofm-$presenterId-${System.nanoTime()}")
    }

    fun shutdown() {
        pending.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
