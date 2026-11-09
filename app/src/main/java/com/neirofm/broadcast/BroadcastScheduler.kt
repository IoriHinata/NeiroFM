package com.neirofm.broadcast

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.neirofm.data.ProgramCatalog
import com.neirofm.data.RadioScript
import com.neirofm.data.ScriptLibrary
import com.neirofm.voice.VoiceEngine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class BroadcastScheduler(
    private val voice: VoiceEngine,
    private val context: Context,
    private val say: (String, String, () -> Unit) -> Unit = { text, presenter, done -> voice.speak(text, presenter, done) },
    private val onQueueIdle: () -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var lastProgram = ""
    private var lastPodcastId = ""
    private data class Speech(val text: String, val presenter: String, val done: () -> Unit)
    private val speechQueue = ArrayDeque<Speech>()
    private var speechInProgress = false

    private fun enqueue(text: String, presenter: String, done: () -> Unit = {}) {
        handler.post {
            if (text.isBlank()) { done(); return@post }
            speechQueue.addLast(Speech(text, presenter, done))
            drainSpeechQueue()
        }
    }

    private fun drainSpeechQueue() {
        if (speechInProgress) return
        if (speechQueue.isEmpty()) { onQueueIdle(); return }
        val next = speechQueue.removeFirst()
        speechInProgress = true
        try {
            say(next.text, next.presenter) {
                handler.post {
                    speechInProgress = false
                    runCatching { next.done() }
                    if (speechQueue.isEmpty()) onQueueIdle() else drainSpeechQueue()
                }
            }
        } catch (t: Throwable) {
            speechInProgress = false
            if (speechQueue.isEmpty()) onQueueIdle() else drainSpeechQueue()
        }
    }

    private val hourly = object : Runnable {
        override fun run() {
            if (!active) return
            val now = Calendar.getInstance()
            val hour = now.get(Calendar.HOUR_OF_DAY)
            val minute = now.get(Calendar.MINUTE)
            val suffix = String.format(Locale.US, "%02d", hour)
            val scripts = ScriptLibrary.get(context).filter { it.type == RadioScript.Type.TIME }
            val exact = scripts.firstOrNull { it.title.lowercase(Locale.ROOT).endsWith(suffix) && it.title.lowercase(Locale.ROOT).contains("time") }
            val alternative = scripts.filter { it.title.lowercase(Locale.ROOT).contains("time alt") }.randomOrNull()
            val announcement = exact?.text
                ?: alternative?.text?.replace("{HH}", String.format(Locale.US, "%02d", hour))?.replace("{MM}", String.format(Locale.US, "%02d", minute))
                ?: "С вами NeiroFM. Сейчас ${SimpleDateFormat("H", Locale.getDefault()).format(Date())} часов. Хорошего прослушивания."
            enqueue(announcement, exact?.presenter ?: alternative?.presenter ?: "maria")
            val next = ((System.currentTimeMillis() / 3_600_000L) + 1) * 3_600_000L
            handler.postDelayed(this, (next - System.currentTimeMillis()).coerceAtLeast(1000))
        }
    }

    private val links = object : Runnable {
        override fun run() {
            if (!active) return
            val program = ProgramCatalog.current()
            if (program.name != lastProgram) {
                val greeting = if (lastProgram.isBlank()) ScriptLibrary.get(context).filter { it.type == RadioScript.Type.GREETING }.randomOrNull() else null
                lastProgram = program.name
                val intro = greeting?.text ?: "Добро пожаловать на NeiroFM!"
                val speaker = greeting?.presenter ?: program.presenter
                enqueue("$intro Вы слушаете программу «${program.name}». ${program.description}. У микрофона ${presenterName(program.presenter)}.", speaker)
            } else {
                val lines = listOf(
                    "Мы продолжаем эфир NeiroFM. Оставайтесь с нами — впереди музыка и новые истории.",
                    "Пусть этот момент станет немного лучше с хорошей музыкой. Вы на NeiroFM.",
                    "Спасибо, что проводите это время с нами. Мы уже готовим для вас следующую историю."
                )
                enqueue(lines.random(), program.presenter)
            }
            handler.postDelayed(this, Random.nextLong(7, 12) * 60_000L)
        }
    }

    private val ads = object : Runnable {
        override fun run() {
            if (!active) return
            val script = ScriptLibrary.get(context).filter { it.type == RadioScript.Type.ADVERTISEMENT }.randomOrNull()
            if (script != null) enqueue("Небольшой промоблок NeiroFM. ${script.text}", script.presenter)
            handler.postDelayed(this, Random.nextLong(28, 42) * 60_000L)
        }
    }

    private val weather = object : Runnable {
        override fun run() {
            if (!active) return
            val script = ScriptLibrary.get(context).filter { it.type == RadioScript.Type.WEATHER }.randomOrNull()
            if (script != null) enqueue(script.text, script.presenter)
            handler.postDelayed(this, Random.nextLong(150, 210) * 60_000L)
        }
    }

    private val podcast = object : Runnable {
        override fun run() {
            if (!active) return
            val program = ProgramCatalog.current()
            val imported = ScriptLibrary.get(context).filter {
                it.type == RadioScript.Type.PODCAST ||
                    (program.startHour == 6 && it.type == RadioScript.Type.MORNING) ||
                    (program.startHour == 22 && it.type == RadioScript.Type.NIGHT)
            }
            val script = imported.filterNot { it.id == lastPodcastId }.randomOrNull() ?: imported.randomOrNull()
            if (script != null) {
                lastPodcastId = script.id
                enqueue(script.text, script.presenter)
            } else enqueue(ProgramCatalog.createPodcast(program), program.presenter)
            handler.postDelayed(this, Random.nextLong(24, 36) * 60_000L)
        }
    }

    fun start() {
        if (active) return
        active = true
        val now = System.currentTimeMillis()
        handler.postDelayed(hourly, (3_600_000L - now % 3_600_000L).coerceAtLeast(1000))
        handler.postDelayed(links, 2500)
        handler.postDelayed(podcast, Random.nextLong(60, 150) * 1000L)
        handler.postDelayed(ads, Random.nextLong(20, 35) * 60_000L)
        handler.postDelayed(weather, Random.nextLong(30, 60) * 60_000L)
    }

    fun announce(text: String, presenter: String, onComplete: (() -> Unit)? = null) =
        enqueue(text, presenter) { onComplete?.invoke() }

    fun testVoice() = enqueue("Проверка голоса NeiroFM. Вы слышите одного из ведущих нашей радиостанции.", "maria")

    fun stop() {
        active = false
        handler.removeCallbacks(hourly)
        handler.removeCallbacks(links)
        handler.removeCallbacks(podcast)
        handler.removeCallbacks(ads)
        handler.removeCallbacks(weather)
        speechQueue.clear()
    }

    private fun presenterName(id: String) = when (id) { "maria" -> "Мария"; "max" -> "Макс"; else -> "Алекс" }
}
