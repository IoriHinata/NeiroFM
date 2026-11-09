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

data class StudioState(
    val mode: BroadcastMode, val internet: Boolean, val offlineForMs: Long,
    val bufferedMs: Long, val generatedCount: Int, val modelReady: Boolean,
    val modelInstalling: Boolean, val onlineConfigured: Boolean, val lastMessage: String
)

/**
 * Free-first coordinator: every presenter line is synthesized on-device by Piper/sherpa-onnx.
 * Network access is used only to fetch voice weights once; no cloud TTS endpoint is called.
 */
class HybridBroadcastCoordinator(context: Context, private val voice: VoiceEngine, private val onAudioReady: (MediaItem) -> Unit) {
    private val app = context.applicationContext
    private val store = AudioBufferStore(app)
    private val watchdog = NetworkWatchdog(app)
    private val prefs = app.getSharedPreferences("neirofm_studio", Context.MODE_PRIVATE)
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
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 2_000L)
    }
    fun stop() { active = false; handler.removeCallbacks(ticker) }

    fun state(): StudioState {
        val buffered = store.pendingDurationMs()
        return StudioState(BroadcastMode.OFFLINE_LOCAL, watchdog.validated, watchdog.offlineDurationMs(),
            buffered, generatedCount, voice.ready, voice.installing, false,
            prefs.getString("message", "Бесплатный локальный нейросинтез").orEmpty())
    }

    private fun publish(message: String? = null) {
        val s = state()
        val text = message ?: when {
            s.modelReady -> "Бесплатный локальный нейросинтез активен на устройстве"
            s.modelInstalling -> "Загружаются русские голосовые модели; после этого интернет не понадобится"
            else -> "Ожидание установки бесплатных локальных голосов"
        }
        prefs.edit().putString("message", text).putLong("buffered_ms", s.bufferedMs)
            .putBoolean("internet", s.internet).putString("mode", s.mode.name)
            .putLong("offline_ms", s.offlineForMs).putInt("generated_count", generatedCount)
            .putBoolean("online_configured", false).putBoolean("model_ready", s.modelReady)
            .putBoolean("model_installing", s.modelInstalling).apply()
        app.sendBroadcast(Intent(ACTION_STUDIO).setPackage(app.packageName))
    }

    private fun fillIfNeeded() {
        if (!active || generating) return
        val s = state()
        if (!s.modelReady) {
            if (s.modelInstalling) {
                publish("Установка русских нейросетевых голосов")
            } else if (s.internet) {
                publish("Первичная установка бесплатных русских голосов")
                voice.installNeuralVoice {
                    handler.post {
                        publish(if (voice.ready) "Русские голоса готовы. Теперь синтез полностью автономный." else "Не удалось установить голосовые модели: " + (voice.lastError ?: "проверьте интернет и свободную память"))
                    }
                }
            } else {
                publish("Для первой загрузки голосовых моделей нужен интернет. После загрузки синтез будет работать офлайн.")
            }
            return
        }
        if (s.bufferedMs >= targetBufferMs + safetyBufferMs) return

        generating = true
        val script = nextScript()
        val id = UUID.randomUUID().toString()
        val output = File(app.filesDir, "broadcast-buffer/$id.wav")
        publish("Локальный Piper создаёт аудиофрагмент без API и оплаты")
        voice.synthesizeToFile(script.first, script.second, output, { file, duration ->
            registerAudio(id, file, script, duration)
            publish("Локальный аудиофайл готов")
            generating = false
        }, { error ->
            publish("Ошибка локального синтеза: $error")
            generating = false
        })
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
                it.type == RadioScript.Type.NIGHT || it.type == RadioScript.Type.GENERAL ||
                it.type == RadioScript.Type.GREETING || it.type == RadioScript.Type.ADVERTISEMENT ||
                it.type == RadioScript.Type.WEATHER
        }
        if (cursor < scripts.size) {
            val selected = scripts[cursor]
            cursor++
            prefs.edit().putInt("script_cursor", cursor).apply()
            return selected.text to selected.presenter
        }
        val program = ProgramCatalog.current()
        return ProgramCatalog.createPodcast(program) to program.presenter
    }

    fun onItemConsumed(mediaId: String?) {
        val id = mediaId?.takeIf { it.startsWith("buffer|") }?.substringAfter("buffer|") ?: return
        store.markConsumed(id); store.removeOld(); publish()
    }

    fun close() { stop(); watchdog.close(); store.close() }
    companion object { const val ACTION_STUDIO = "com.neirofm.action.STUDIO_STATUS" }
}
