package com.neirofm.broadcast

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.neirofm.data.ProgramCatalog
import com.neirofm.data.RadioScript
import com.neirofm.data.ScriptLibrary
import com.neirofm.voice.VoiceEngine
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class StudioState(
    val mode: BroadcastMode, val internet: Boolean, val offlineForMs: Long,
    val bufferedMs: Long, val generatedCount: Int, val modelReady: Boolean,
    val modelInstalling: Boolean, val onlineConfigured: Boolean, val lastMessage: String
)

class HybridBroadcastCoordinator(context: Context, private val voice: VoiceEngine, private val onAudioReady: (MediaItem) -> Unit) {
    private val app = context.applicationContext
    private val store = AudioBufferStore(app)
    private val watchdog = NetworkWatchdog(app)
    private val online = OnlineSpeechProvider(app)
    private val prefs = app.getSharedPreferences("neirofm_studio", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    @Volatile private var generating = false
    @Volatile private var restoredPending = false
    private var generatedCount = prefs.getInt("generated_count", 0)
    private var cursor = prefs.getInt("script_cursor", 0)
    private val targetBufferMs = 4L * 60L * 60L * 1000L
    private val safetyBufferMs = 30L * 60L * 1000L

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            watchdog.refresh()
            if (!restoredPending) {
                restoredPending = true
                store.pending().forEach { item ->
                    val media = MediaItem.Builder().setMediaId("buffer|${item.id}").setUri(File(item.path).toURI().toString())
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist("NeiroFM").build()).build()
                    onAudioReady(media)
                }
            }
            publish()
            fillIfNeeded()
            handler.postDelayed(this, 30_000L)
        }
    }

    fun start() {
        if (active) return
        active = true
        restoredPending = false
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 2_000L)
    }
    fun stop() { active = false; handler.removeCallbacks(ticker) }
    fun configureOnline(endpoint: String, model: String, key: String) = online.save(app, endpoint, model, key)

    fun state(): StudioState {
        val buffered = store.pendingDurationMs()
        val configured = online.configured()
        return StudioState(watchdog.mode(configured, buffered), watchdog.validated, watchdog.offlineDurationMs(),
            buffered, generatedCount, voice.ready, voice.installing, configured,
            prefs.getString("message", "Ожидание эфира").orEmpty())
    }

    private fun publish(message: String? = null) {
        val s = state()
        val text = message ?: when (s.mode) {
            BroadcastMode.ONLINE -> "Онлайн-синтез активен; пополняется аудиобуфер"
            BroadcastMode.BUFFER_ONLY -> "Воспроизводится запас готового эфира; ждём сеть или порог автономности"
            BroadcastMode.OFFLINE_LOCAL -> if (s.modelReady) "Автономный эфир: локальная модель готовит новые реплики" else "Нет сети более 4 часов; локальная модель ещё не готова"
        }
        prefs.edit().putString("message", text).putLong("buffered_ms", s.bufferedMs)
            .putBoolean("internet", s.internet).putString("mode", s.mode.name)
            .putLong("offline_ms", s.offlineForMs).putInt("generated_count", generatedCount).apply()
        app.sendBroadcast(Intent(ACTION_STUDIO).setPackage(app.packageName))
    }

    private fun fillIfNeeded() {
        if (generating || !active) return
        val s = state()
        if (s.bufferedMs >= targetBufferMs + safetyBufferMs) return
        val useOnline = s.mode == BroadcastMode.ONLINE
        val useLocal = s.mode == BroadcastMode.OFFLINE_LOCAL && voice.ready
        if (!useOnline && !useLocal) return
        generating = true
        val script = nextScript()
        val id = UUID.randomUUID().toString()
        val output = File(app.filesDir, "broadcast-buffer/$id.${if (useOnline) "mp3" else "wav"}")
        publish(if (useOnline) "Онлайн TTS создаёт аудиофрагмент" else "Локальный Piper создаёт аудиофрагмент")
        if (useOnline) {
            worker.execute {
                try {
                    val bytes = online.synthesize(script.first, script.second, output)
                    val duration = estimateDuration(script.first)
                    registerAudio(id, output, script, duration)
                    publish("Онлайн-аудиофайл готов (${bytes / 1024} КБ)")
                } catch (t: Throwable) {
                    publish("Ошибка онлайн-синтеза: ${t.message ?: t.javaClass.simpleName}")
                } finally { generating = false }
            }
        } else {
            voice.synthesizeToFile(script.first, script.second, output, { file, duration ->
                registerAudio(id, file, script, duration)
                publish("Локальный аудиофайл готов")
                generating = false
            }, { error ->
                publish("Ошибка локального синтеза: $error")
                generating = false
            })
        }
    }

    private fun registerAudio(id: String, file: File, script: Pair<String, String>, duration: Long) {
        val title = "NeiroFM — ${script.second} — выпуск ${generatedCount + 1}"
        store.add(BufferedAudio(id, file.absolutePath, title, script.second, "HOST", duration, System.currentTimeMillis()))
        generatedCount++
        prefs.edit().putInt("generated_count", generatedCount).apply()
        val media = MediaItem.Builder().setMediaId("buffer|$id").setUri(file.toURI().toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("NeiroFM").build()).build()
        onAudioReady(media)
        handler.postDelayed({ fillIfNeeded() }, 3_000L)
    }

    private fun nextScript(): Pair<String, String> {
        val scripts = ScriptLibrary.get(app).filter {
            it.type == RadioScript.Type.PODCAST || it.type == RadioScript.Type.MORNING ||
                it.type == RadioScript.Type.NIGHT || it.type == RadioScript.Type.GENERAL
        }
        if (scripts.isNotEmpty()) {
            val selected = scripts[cursor.mod(scripts.size)]
            cursor++
            prefs.edit().putInt("script_cursor", cursor).apply()
            return selected.text to selected.presenter
        }
        val program = ProgramCatalog.current()
        return ProgramCatalog.createPodcast(program) to program.presenter
    }

    private fun estimateDuration(text: String): Long = (text.trim().split(Regex("\\s+")).size * 60_000L / 135L).coerceAtLeast(30_000L)

    fun onItemConsumed(mediaId: String?) {
        val id = mediaId?.takeIf { it.startsWith("buffer|") }?.substringAfter("buffer|") ?: return
        store.markConsumed(id); store.removeOld(); publish()
    }

    fun close() { stop(); watchdog.close(); worker.shutdownNow(); store.close() }
    companion object { const val ACTION_STUDIO = "com.neirofm.action.STUDIO_STATUS" }
}
