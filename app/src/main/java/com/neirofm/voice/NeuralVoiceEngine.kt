package com.neirofm.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline Piper/sherpa-onnx speech engine.
 * All extraction, model creation, synthesis and PCM playback are serialized on one worker.
 * Android TTS is deliberately not used as a silent fallback.
 */
class NeuralVoiceEngine(context: Context) {
    companion object { private const val TAG = "NeiroFM.NeuralVoice" }

    private val app = context.applicationContext
    private val root = File(app.filesDir, "neirofm-voices")
    private val worker = Executors.newSingleThreadExecutor()
    private val engines = mutableMapOf<String, OfflineTts>()
    private val installCallbacks = mutableListOf<() -> Unit>()
    private val queuedSpeech = AtomicInteger(0)
    private val prefs = app.getSharedPreferences("neirofm_voice", Context.MODE_PRIVATE)
    @Volatile var ready = false
        private set
    @Volatile var installing = false
        private set
    @Volatile var status = "Подготовка встроенных русских голосов"
        private set
    @Volatile var lastError: String? = null
        private set
    val isSpeaking: Boolean get() = queuedSpeech.get() > 0
    val loadedPresenterCount: Int get() = synchronized(engines) { engines.size }

    private data class Model(val id: String, val archive: String, val modelFile: String)
    private val models = listOf(
        Model("alex", "vits-piper-ru_RU-dmitri-medium", "ru_RU-dmitri-medium.onnx"),
        Model("maria", "vits-piper-ru_RU-irina-medium", "ru_RU-irina-medium.onnx"),
        Model("max", "vits-piper-ru_RU-denis-medium", "ru_RU-denis-medium.onnx")
    )

    init {
        ready = models.all(::modelFilesPresent)
        if (ready) updateStatus("Голосовые модели установлены. Проверка движка при первом воспроизведении.")
        else install()
    }

    private fun updateStatus(value: String, error: String? = null) {
        status = value
        lastError = error
        prefs.edit().putString("status", value).putString("error", error).putBoolean("ready", ready)
            .putBoolean("installing", installing).apply()
        Log.i(TAG, value)
        if (error != null) Log.e(TAG, error)
        app.sendBroadcast(Intent("com.neirofm.action.VOICE_STATUS").setPackage(app.packageName)
            .putExtra("status", value).putExtra("error", error))
    }

    private fun modelFilesPresent(model: Model): Boolean {
        val dir = File(root, model.archive)
        val onnx = File(dir, model.modelFile)
        return onnx.isFile && onnx.length() > 1024 &&
            File(dir, "tokens.txt").isFile && File(dir, "tokens.txt").length() > 0 &&
            File(dir, "espeak-ng-data").isDirectory
    }

    @Synchronized
    fun install(onStatus: (String) -> Unit = {}, onComplete: () -> Unit = {}) {
        if (ready) { onComplete(); return }
        if (installing) {
            installCallbacks += onComplete
            Log.i(TAG, "Запрос завершения добавлен к текущей установке")
            return
        }
        installCallbacks += onComplete
        installing = true
        updateStatus("Установка русских нейросетевых голосов")
        worker.execute {
            try {
                check(root.mkdirs() || root.isDirectory) { "Не удалось создать каталог моделей" }
                models.forEach { model ->
                    if (!modelFilesPresent(model)) {
                        val message = "Подготовка голоса: ${model.id}"
                        updateStatus(message)
                        onStatus(message)
                        installArchive(model)
                    }
                }
                check(models.all(::modelFilesPresent)) { "Не все файлы голосовых моделей установлены" }
                ready = true
                updateStatus("Три русские модели установлены. Нейросинтез проверяется при первом запуске ведущего.")
                onStatus(status)
            } catch (t: Throwable) {
                ready = false
                val message = t.message ?: t.javaClass.simpleName
                updateStatus("Ошибка установки нейроголосов: $message", message)
            } finally {
                val callbacks = synchronized(this) {
                    installing = false
                    installCallbacks.toList().also { installCallbacks.clear() }
                }
                prefs.edit().putBoolean("installing", false).putBoolean("ready", ready).apply()
                callbacks.forEach { callback -> runCatching { callback() } }
            }
        }
    }

    private fun installArchive(model: Model) {
        val target = File(root, model.archive)
        check(target.mkdirs() || target.isDirectory) { "Не удалось создать каталог ${model.id}" }
        val archiveFile = File(app.cacheDir, "${model.archive}.tar.bz2")
        try {
            val assetName = "voices/${model.archive}.tar.bz2"
            val bundled = runCatching { app.assets.open(assetName) }.getOrNull()
            if (bundled != null) {
                bundled.use { input -> archiveFile.outputStream().use { input.copyTo(it) } }
                Log.i(TAG, "Модель ${model.id} извлекается из APK assets")
            } else {
                val url = URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${model.archive}.tar.bz2")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 300000
                    instanceFollowRedirects = true
                }
                try {
                    val code = connection.responseCode
                    check(code in 200..299) { "Загрузка ${model.id}: HTTP $code" }
                    connection.inputStream.use { input -> archiveFile.outputStream().use { input.copyTo(it) } }
                } finally { connection.disconnect() }
            }
            check(archiveFile.length() > 1024) { "Архив голоса пуст или повреждён: ${model.id}" }
            TarArchiveInputStream(BufferedInputStream(BZip2CompressorInputStream(archiveFile.inputStream()))).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    check(!entry.isSymbolicLink && !entry.isLink) { "Ссылки в архиве модели запрещены" }
                    val normalized = entry.name.replace('\\', '/')
                    val relative = normalized.substringAfter('/', normalized).trimStart('/')
                    if (relative.isNotBlank()) {
                        val out = File(target, relative)
                        val canonicalRoot = target.canonicalFile.toPath()
                        check(out.canonicalFile.toPath().startsWith(canonicalRoot)) { "Недопустимый путь в архиве" }
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var remaining = entry.size
                                while (remaining > 0) {
                                    val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (count < 0) throw IllegalStateException("Архив модели оборван")
                                    output.write(buffer, 0, count)
                                    remaining -= count
                                }
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
            check(modelFilesPresent(model)) { "В архиве отсутствуют ONNX, tokens.txt или espeak-ng-data для ${model.id}" }
        } finally {
            archiveFile.delete()
        }
    }

    fun speak(
        text: String,
        presenter: String,
        onComplete: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (text.isBlank()) { onComplete?.invoke(); return }
        queuedSpeech.incrementAndGet()
        try {
            worker.execute {
                try {
                    check(ready) { lastError ?: "Нейросетевые модели не установлены. Android TTS не используется." }
                    val id = presentersId(presenter)
                    val tts = engines.getOrPut(id) { createEngine(id) }
                    val audio = tts.generate(text, 0, when (id) { "maria" -> 1.02f; "max" -> 0.96f; else -> 1.0f })
                    check(audio.samples.isNotEmpty() && audio.sampleRate > 0) { "Sherpa-ONNX вернул пустой аудиобуфер" }
                    play(audio.samples, audio.sampleRate)
                    updateStatus("Нейроголос работает: ${presenterName(id)}")
                } catch (t: Throwable) {
                    val message = t.message ?: t.javaClass.simpleName
                    updateStatus("Ошибка нейросинтеза: $message", message)
                    Log.e(TAG, "Не удалось озвучить реплику ведущего $presenter", t)
                    onError?.invoke(message)
                } finally {
                    queuedSpeech.decrementAndGet()
                    onComplete?.invoke()
                }
            }
        } catch (t: Throwable) {
            queuedSpeech.decrementAndGet()
            val message = t.message ?: t.javaClass.simpleName
            updateStatus("Очередь речи недоступна: $message", message)
            onError?.invoke(message)
            onComplete?.invoke()
        }
    }

    private fun presentersId(id: String) = if (models.any { it.id == id }) id else "alex"
    private fun presenterName(id: String) = when (id) { "maria" -> "Мария"; "max" -> "Макс"; else -> "Алекс" }

    private fun createEngine(presenter: String): OfflineTts {
        val model = models.first { it.id == presenter }
        val dir = File(root, model.archive)
        check(modelFilesPresent(model)) { "Файлы модели ${model.id} отсутствуют после распаковки" }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, model.modelFile).absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1
        )
        val engine = OfflineTts(config = config)
        Log.i(TAG, "Sherpa-ONNX runtime загружен для ведущего $presenter")
        updateStatus("Нейросетевой движок загружен: $presenter")
        return engine
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        val channel = AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate).setChannelMask(channel).build()
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channel, AudioFormat.ENCODING_PCM_FLOAT)
        check(minBuffer > 0) { "Android не поддерживает PCM_FLOAT для частоты $sampleRate" }
        val track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, 16384)).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Не удалось инициализировать AudioTrack" }
            track.setVolume(1.0f)
            track.play()
            var offset = 0
            while (offset < samples.size) {
                val requested = minOf(4096, samples.size - offset)
                val written = track.write(samples, offset, requested, AudioTrack.WRITE_BLOCKING)
                check(written > 0) { "Ошибка записи PCM в AudioTrack: $written" }
                offset += written
            }
            track.stop()
        } finally {
            runCatching { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
            track.release()
        }
    }

    fun release() {
        worker.shutdownNow()
        synchronized(engines) { engines.values.forEach { runCatching { it.release() } }; engines.clear() }
    }
}
