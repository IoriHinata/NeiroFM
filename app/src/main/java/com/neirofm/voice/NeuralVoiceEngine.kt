package com.neirofm.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
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
        Model("alex", "vits-piper-ru_RU-ruslan-medium", "ru_RU-ruslan-medium.onnx"),
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
        val archiveFile = File(app.cacheDir, "${model.archive}.tar.bz2")
        try {
            val assetName = "voices/${model.archive}.tar.bz2"
            val bundled = runCatching { app.assets.open(assetName) }.getOrNull()
            if (bundled != null) {
                bundled.use { input -> archiveFile.outputStream().use { input.copyTo(it) } }
            } else {
                val url = URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${model.archive}.tar.bz2")
                downloadModelArchive(url, archiveFile, model)
            }
            extractModelArchive(model, archiveFile)
        } finally { archiveFile.delete() }
    }

    /**
     * Imports several downloaded Piper archives in one operation.
     * ruslan -> Alex, irina -> Maria, denis -> Max.
     */
    @Synchronized
    fun installVoiceArchives(archives: List<Pair<String, Uri>>, onComplete: (Int, Int, String) -> Unit = { _, _, _ -> }) {
        if (installing) { onComplete(0, archives.size, "Другая установка голосов ещё выполняется. Дождитесь её завершения."); return }
        val selected = archives.mapNotNull { (name, uri) ->
            val n = name.lowercase()
            val model = when { "ruslan" in n -> models.first { it.id == "alex" }; "irina" in n -> models.first { it.id == "maria" }; "denis" in n -> models.first { it.id == "max" }; else -> null }
            model?.let { Triple(it, uri, name) }
        }.distinctBy { it.first.id }
        val unknown = archives.size - selected.size
        if (selected.isEmpty()) { onComplete(0, archives.size, "Не распознаны архивы. Выбери файлы с именами ruslan, irina и denis."); return }
        installing = true
        updateStatus("Подготовка импорта ${selected.size} голосов")
        worker.execute {
            var installed = 0
            var failed = unknown
            val errors = mutableListOf<String>()
            try {
                check(root.mkdirs() || root.isDirectory) { "Не удалось создать папку голосов" }
                selected.forEach { (model, uri, fileName) ->
                    try {
                        if (modelFilesPresent(model)) { installed++ } else {
                            val archiveFile = File(app.cacheDir, "${model.archive}-manual.tar.bz2")
                            try {
                                app.contentResolver.openInputStream(uri)?.use { input ->
                                    archiveFile.outputStream().buffered().use { output ->
                                        val buffer = ByteArray(64 * 1024); var total = 0L
                                        while (true) { val n = input.read(buffer); if (n < 0) break; if (n == 0) continue; total += n; check(total <= 250L * 1024L * 1024L) { "Архив $fileName превышает 250 МБ" }; output.write(buffer, 0, n) }
                                    }
                                } ?: error("Не удалось открыть $fileName")
                                check(archiveFile.length() > 1024L) { "$fileName пуст или повреждён" }
                                updateStatus("Распаковка голоса ${model.id}")
                                extractModelArchive(model, archiveFile)
                                installed++
                            } finally { archiveFile.delete() }
                        }
                    } catch (t: Throwable) { failed++; errors += "$fileName: ${t.message ?: t.javaClass.simpleName}" }
                }
                ready = models.all(::modelFilesPresent)
                val summary = if (ready) "Все три русских голоса установлены: Руслан, Ирина и Денис." else "Установлено голосов: $installed. Ошибок/неподходящих архивов: $failed. ${errors.joinToString("; ")}"
                updateStatus(summary, if (failed > 0) summary else null)
                onComplete(installed, failed, summary)
            } catch (t: Throwable) {
                ready = models.all(::modelFilesPresent)
                val message = "Ошибка импорта голосов: ${t.message ?: t.javaClass.simpleName}"
                updateStatus(message, message); onComplete(installed, failed + 1, message)
            } finally {
                installing = false
                prefs.edit().putBoolean("installing", false).putBoolean("ready", ready).apply()
                app.sendBroadcast(Intent("com.neirofm.action.VOICE_STATUS").setPackage(app.packageName).putExtra("status", status).putExtra("error", lastError))
            }
        }
    }

    fun installMaxFromUri(uri: Uri, onComplete: (Boolean, String) -> Unit = { _, _ -> }) {
        installVoiceArchives(listOf("denis" to uri)) { installed, failed, message -> onComplete(installed > 0 && failed == 0, message) }
    }
    private fun extractModelArchive(model: Model, archiveFile: File) {
        val target = File(root, model.archive)
        check(target.mkdirs() || target.isDirectory) { "Не удалось создать каталог ${model.id}" }
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
    }

    private fun downloadModelArchive(url: URL, archiveFile: File, model: Model) {
        var lastError: Throwable? = null
        for (attempt in 1..5) {
            archiveFile.delete()
            try {
                updateStatus("Загрузка локального голоса ${model.id}: попытка $attempt из 5")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 300000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "NeiroFM-Android")
                }
                try {
                    val code = connection.responseCode
                    check(code in 200..299) { "Загрузка ${model.id}: HTTP $code" }
                    connection.inputStream.use { input ->
                        archiveFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            var count: Int
                            while (input.read(buffer).also { count = it } >= 0) {
                                if (count == 0) continue
                                output.write(buffer, 0, count)
                                total += count
                                if (total % (8L * 1024L * 1024L) < buffer.size) {
                                    updateStatus("Загрузка ${model.id}: ${total / (1024L * 1024L)} МБ")
                                }
                            }
                        }
                    }
                    check(archiveFile.length() > 1024L) { "Архив голоса пуст: ${model.id}" }
                    return
                } finally { connection.disconnect() }
            } catch (t: Throwable) {
                lastError = t
                archiveFile.delete()
                if (attempt < 5) Thread.sleep(attempt * 2000L)
            }
        }
        throw IllegalStateException("Не удалось скачать голос ${model.id} после 5 попыток", lastError)
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

    fun synthesizeToFile(
        text: String,
        presenter: String,
        output: File,
        onReady: (File, Long) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (text.isBlank()) { onError("Пустой текст для синтеза"); return }
        queuedSpeech.incrementAndGet()
        try {
            worker.execute {
                try {
                    check(ready) { lastError ?: "Локальные голосовые модели не готовы" }
                    val id = presentersId(presenter)
                    val tts = engines.getOrPut(id) { createEngine(id) }
                    val audio = tts.generate(text, 0, when (id) { "maria" -> 1.02f; "max" -> 0.96f; else -> 1.0f })
                    check(audio.samples.isNotEmpty() && audio.sampleRate > 0) { "Sherpa-ONNX вернул пустой аудиобуфер" }
                    output.parentFile?.mkdirs()
                    java.io.DataOutputStream(java.io.BufferedOutputStream(output.outputStream())).use { out ->
                        val dataBytes = audio.samples.size * 2
                        out.writeBytes("RIFF"); writeLeInt(out, 36 + dataBytes)
                        out.writeBytes("WAVE"); out.writeBytes("fmt "); writeLeInt(out, 16)
                        writeLeShort(out, 1); writeLeShort(out, 1)
                        writeLeInt(out, audio.sampleRate); writeLeInt(out, audio.sampleRate * 2)
                        writeLeShort(out, 2); writeLeShort(out, 16)
                        out.writeBytes("data"); writeLeInt(out, dataBytes)
                        audio.samples.forEach { sample ->
                            val pcm = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
                            writeLeShort(out, pcm)
                        }
                    }
                    val duration = audio.samples.size.toLong() * 1000L / audio.sampleRate
                    check(output.length() > 44L) { "Не удалось записать WAV" }
                    onReady(output, duration)
                } catch (t: Throwable) {
                    val message = t.message ?: t.javaClass.simpleName
                    updateStatus("Ошибка локального синтеза: $message", message)
                    onError(message)
                } finally { queuedSpeech.decrementAndGet() }
            }
        } catch (t: Throwable) {
            queuedSpeech.decrementAndGet()
            onError(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun writeLeInt(out: java.io.DataOutputStream, value: Int) {
        out.writeByte(value and 0xff); out.writeByte((value ushr 8) and 0xff)
        out.writeByte((value ushr 16) and 0xff); out.writeByte((value ushr 24) and 0xff)
    }
    private fun writeLeShort(out: java.io.DataOutputStream, value: Int) {
        out.writeByte(value and 0xff); out.writeByte((value ushr 8) and 0xff)
    }

    fun release() {
        worker.shutdownNow()
        synchronized(engines) { engines.values.forEach { runCatching { it.release() } }; engines.clear() }
    }
}
