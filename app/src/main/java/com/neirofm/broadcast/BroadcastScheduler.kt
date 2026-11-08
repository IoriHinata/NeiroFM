package com.neirofm.broadcast

import android.os.Handler
import android.os.Looper
import com.neirofm.voice.VoiceEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class BroadcastScheduler(private val voice: VoiceEngine) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var presenterIndex = 0

    private val hourly = object : Runnable {
        override fun run() {
            if (!active) return
            val now = System.currentTimeMillis()
            val nextHour = ((now / 3_600_000L) + 1L) * 3_600_000L
            handler.postDelayed(this, (nextHour - now).coerceAtLeast(1_000L))
            val hour = SimpleDateFormat("H", Locale("ru")).format(Date(now))
            voice.speak("С вами Neiro FM. Сейчас ровно $hour часов. Хорошего прослушивания.", "maria")
        }
    }

    private val host = object : Runnable {
        override fun run() {
            if (!active) return
            val scripts = listOf(
                "Добро пожаловать на Neiro FM! У микрофона Алекс. Оставайтесь с нами — впереди музыка и хорошее настроение.",
                "Привет! Это Мария, и вы слушаете Neiro FM. Давайте проведём это время вместе.",
                "На связи Макс. Вы слушаете Neiro FM — меньше суеты, больше музыки."
            )
            val ids = listOf("alex", "maria", "max")
            val index = presenterIndex++ % scripts.size
            voice.speak(scripts[index], ids[index])
            handler.postDelayed(this, Random.nextLong(6, 11) * 60_000L)
        }
    }

    fun start() {
        if (active) return
        active = true
        val now = System.currentTimeMillis()
        handler.postDelayed(hourly, (3_600_000L - now % 3_600_000L).coerceAtLeast(1_000L))
        handler.postDelayed(host, 2_000L)
    }

    fun announce(text: String, presenter: String, onComplete: (() -> Unit)? = null) {
        voice.speak(text, presenter, onComplete)
    }

    fun testVoice() {
        voice.speak("Проверка голоса Neiro FM. Если вы слышите это сообщение, диктор работает.", "maria")
    }

    fun stop() {
        active = false
        handler.removeCallbacks(hourly)
        handler.removeCallbacks(host)
    }
}