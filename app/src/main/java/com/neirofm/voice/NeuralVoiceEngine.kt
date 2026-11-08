package com.neirofm.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Russian Piper voices are bundled in the APK assets by CI. The first run
 * extracts them to app-private storage; synthesis itself is fully offline.
 */
class NeuralVoiceEngine(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "neirofm-voices")
    private val worker = Executors.newSingleThreadExecutor()
    private val engines = mutableMapOf<String, OfflineTts>()
    @Volatile var ready = false
        private set
    @Volatile var installing = false
        private set
    @Volatile var status = "Подготовка встроенных русских голосов"
        private set

    private data class Model(val id: String, val archive: String, val modelFile: String)
    private val models = listOf(
        Model("alex", "vits-piper-ru_RU-dmitri-medium", "ru_RU-dmitri-medium.onnx"),
        Model("maria", "vits-piper-ru_RU-irina-medium", "ru_RU-irina-medium.onnx"),
        Model("max", "vits-piper-ru_RU-denis-medium", "ru_RU-denis-medium.onnx")
    )

    init {
        ready = models.all { modelFilesPresent(it) }
        if (ready) status = "Три встроенных русских голоса готовы офлайн"
        else install()
    }

    private fun modelFilesPresent(model: Model): Boolean {
        val dir = File(root, model.archive)
        return File(dir, model.modelFile).isFile &&
            File(dir, "tokens.txt").isFile &&
            File(dir, "espeak-ng-data").isDirectory
    }

    @Synchronized
    fun install(onStatus: (String) -> Unit = {}, onComplete: () -> Unit = {}) {
        if (ready) { onComplete(); return }
        if (installing) return
        installing = true
        worker.execute {
            try {
                root.mkdirs()
                models.forEach { model ->
                    if (!modelFilesPresent(model)) {
                        onStatus("Подготовка голоса: ${model.id}")
                        installArchive(model)
                    }
                }
                ready = models.all { modelFilesPresent(it) }
                check(ready) { "Не все файлы голосовых моделей установлены" }
                status = "Три встроенных русских голоса готовы офлайн"
                onStatus(status)
            } catch (e: Exception) {
                status = "Ошибка подготовки голосов: ${e.message ?: "неизвестная ошибка"}"
                onStatus(status)
            } finally {
                installing = false
                onComplete()
            }
        }
    }

    private fun installArchive(model: Model) {
        val target = File(root, model.archive)
        target.mkdirs()
        val archiveFile = File(app.cacheDir, "${model.archive}.tar.bz2")
        try {
            val assetName = "voices/${model.archive}.tar.bz2"
            val bundled = runCatching { app.assets.open(assetName) }.getOrNull()
            if (bundled != null) {
                bundled.use { input -> archiveFile.outputStream().use { output -> input.copyTo(output) } }
            } else {
                val url = URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${model.archive}.tar.bz2")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 300000
                    instanceFollowRedirects = true
                }
                try {
                    val code = connection.responseCode
                    check(code in 200..299) { "Сервер вернул HTTP $code" }
                    connection.inputStream.use { input -> archiveFile.outputStream().use { output -> input.copyTo(output) } }
                } finally { connection.disconnect() }
            }
            check(archiveFile.length() > 1024) { "Архив голоса пуст или повреждён" }
            TarArchiveInputStream(BufferedInputStream(BZip2CompressorInputStream(archiveFile.inputStream()))).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val normalized = entry.name.replace('\\', '/')
                    val relative = normalized.substringAfter('/', normalized).trimStart('/')
                    if (relative.isNotBlank()) {
                        val out = File(target, relative)
                        val canonicalTarget = target.canonicalFile
                        check(out.canonicalFile.toPath().startsWith(canonicalTarget.toPath())) { "Недопустимый путь в архиве" }
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var remaining = entry.size
                                while (remaining > 0) {
                                    val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (count < 0) throw IllegalStateException("Архив оборван")
                                    output.write(buffer, 0, count)
                                    remaining -= count
                                }
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
            check(modelFilesPresent(model)) { "В архиве отсутствуют обязательные файлы модели ${model.id}" }
        } finally {
            archiveFile.delete()
        }
    }

    fun speak(text: String, presenter: String, onComplete: (() -> Unit)? = null) {
        worker.execute {
            try {
                check(ready) { "Нейроголоса ещё устанавливаются" }
                val tts = engines.getOrPut(presenter) { createEngine(presenter) }
                val audio = tts.generate(text, 0, when (presenter) { "maria" -> 1.02f; "max" -> 0.96f; else -> 1.0f })
                play(audio.samples, audio.sampleRate)
            } catch (e: Exception) {
                status = "Ошибка нейросинтеза: ${e.message ?: "неизвестная ошибка"}"
            } finally { onComplete?.invoke() }
        }
    }

    private fun createEngine(presenter: String): OfflineTts {
        val model = models.first { it.id == presenter }
        val dir = File(root, model.archive)
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
        return OfflineTts(config = config)
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack(attributes, format, maxOf(minBuffer, 16384), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        try {
            track.play()
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(4096, samples.size - offset)
                track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                offset += count
            }
            track.stop()
        } finally { track.release() }
    }

    fun release() {
        engines.values.forEach { runCatching { it.release() } }
        engines.clear()
        worker.shutdownNow()
    }
}
