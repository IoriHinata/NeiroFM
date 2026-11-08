package com.neirofm.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

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
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private val voicesByPresenter = mutableMapOf<String, Voice?>()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val engine = tts ?: return
        val voices = engine.voices.orEmpty().filter { it.locale.language == "ru" }
        val localVoices = voices.filterNot { it.isNetworkConnectionRequired }
        offlineRussianAvailable = localVoices.isNotEmpty()
        val selected = localVoices
        presenters.forEachIndexed { index, presenter ->
            voicesByPresenter[presenter.id] = selected.getOrNull(index % selected.size.coerceAtLeast(1))
        }
        if (selected.isEmpty()) {
            ready = true
            return
        }
        engine.language = Locale("ru", "RU")
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?, errorCode: Int) = Unit
        })
        ready = true
    }

    @Synchronized fun speak(text: String, presenterId: String = "alex") {
        val engine = tts ?: return
        if (!ready || text.isBlank()) return
        voicesByPresenter[presenterId]?.let { engine.voice = it }
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "neirofm-$presenterId-${System.nanoTime()}")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
