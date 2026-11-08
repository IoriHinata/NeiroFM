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
    private var active = false
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
            val lines = listOf(
                "Вы слушаете Neiro FM. Оставайтесь с нами — дальше хорошая музыка.",
                "Небольшая пауза между треками. Устройтесь поудобнее, эфир продолжается.",
                "Это Neiro FM. Музыка, голоса и настроение — в одном эфире."
            )
            voice.speak(lines[Random.nextInt(lines.size)], listOf("alex", "maria", "max").random())
            handler.postDelayed(this, 25 * 60_000L)
        }
    }
    fun start() {
        if (active) return
        active = true
        handler.postDelayed(hourly, 3_600_000L - (System.currentTimeMillis() % 3_600_000L))
        handler.postDelayed(host, 90_000L)
    }
    fun stop() {
        active = false
        handler.removeCallbacks(hourly)
        handler.removeCallbacks(host)
    }
}
