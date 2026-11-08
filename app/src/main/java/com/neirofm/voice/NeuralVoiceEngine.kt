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
 * Optional first-run download. After installation all speech generation is local.
 * Voice models are distributed separately from the APK to keep the installer small.
 */
class NeuralVoiceEngine(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "neirofm-voices")
    private val worker = Executors.newSingleThreadExecutor()
    private val engines = mutableMapOf<String, OfflineTts>()
    @Volatile var ready = false
        private set
    @Volatile var status = "Русские нейроголоса не установлены"
        private set

    private data class Model(val id: String, val archive: String, val modelFile: String)
    private val models = listOf(
        Model("alex", "vits-piper-ru_RU-ruslan-medium", "ru_RU-ruslan-medium.onnx"),
        Model("maria", "vits-piper-ru_RU-irina-medium", "ru_RU-irina-medium.onnx"),
        Model("max", "vits-piper-ru_RU-dmitri-medium", "ru_RU-dmitri-medium.onnx")
    )

    init {
        ready = models.all { File(File(root, it.archive), it.modelFile).exists() }
        if (ready) status = "Три русские нейросетевые модели готовы офлайн"
    }

    fun install(onStatus: (String) -> Unit = {}, onComplete: () -> Unit = {}) {
        worker.execute {
            try {
                root.mkdirs()
                models.forEach { model ->
                    val folder = File(root, model.archive)
                    val expected = File(folder, model.modelFile)
                    if (!expected.exists()) {
                        onStatus("Загрузка голоса: ${model.id}")
                        downloadAndExtract(model, folder)
                    }
                }
                ready = models.all { File(File(root, it.archive), it.modelFile).exists() }
                status = if (ready) "Три русских нейроголоса готовы офлайн" else "Не все модели установлены"
                onStatus(status)
                onComplete()
            } catch (e: Exception) {
                status = "Ошибка установки нейроголосов: ${e.message ?: "неизвестная ошибка"}"
                onStatus(status)
                onComplete()
            }
        }
    }

    fun speak(text: String, presenter: String, onComplete: (() -> Unit)? = null) {
        worker.execute {
            try {
                val tts = engines.getOrPut(presenter) { createEngine(presenter) }
                val audio = tts.generate(text, 0, when (presenter) { "maria" -> 1.02f; "max" -> 0.96f; else -> 1.0f })
                play(audio.samples, audio.sampleRate)
            } catch (e: Exception) {
                status = "Ошибка нейросинтеза: ${e.message ?: "неизвестная ошибка"}"
            } finally {
                onComplete?.invoke()
            }
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
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
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
        } finally {
            track.release()
        }
    }

    private fun downloadAndExtract(model: Model, target: File) {
        target.mkdirs()
        val archiveFile = File(root, "${model.archive}.tar.bz2")
        val url = URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${model.archive}.tar.bz2")
        val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 20000; readTimeout = 300000 }
        try {
            connection.inputStream.use { input -> archiveFile.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
        try {
            TarArchiveInputStream(BufferedInputStream(BZip2CompressorInputStream(archiveFile.inputStream()))).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val relative = entry.name.substringAfter('/', entry.name).trimStart('/')
                    if (relative.isNotBlank()) {
                        val out = File(target, relative)
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var remaining = entry.size
                                while (remaining > 0) {
                                    val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    remaining -= count
                                }
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
        } finally {
            archiveFile.delete()
        }
        check(File(target, model.modelFile).exists()) { "В архиве отсутствует ${model.modelFile}" }
        check(File(target, "tokens.txt").exists()) { "В архиве отсутствует tokens.txt" }
    }

    fun release() {
        engines.values.forEach { runCatching { it.release() } }
        engines.clear()
        worker.shutdownNow()
    }
}
