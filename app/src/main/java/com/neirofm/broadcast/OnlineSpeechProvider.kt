package com.neirofm.broadcast

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class OnlineSpeechChunk(val file: File, val text: String)

/** OpenAI-compatible speech endpoint. No key is bundled in the APK. */
class OnlineSpeechProvider(context: Context) {
    private val prefs = context.getSharedPreferences("neirofm_online_tts", Context.MODE_PRIVATE)
    private val endpoint get() = prefs.getString("endpoint", "https://api.openai.com/v1/audio/speech")!!
    private val model get() = prefs.getString("model", "gpt-4o-mini-tts")!!
    private val apiKey get() = decrypt(prefs.getString("key_enc", null))

    fun configured(): Boolean = !apiKey.isNullOrBlank() && endpoint.startsWith("https://")

    fun save(context: Context, endpoint: String, model: String, apiKey: String) {
        require(endpoint.startsWith("https://")) { "Для TTS требуется HTTPS" }
        prefs.edit().putString("endpoint", endpoint.trim()).putString("model", model.trim())
            .putString("key_enc", encrypt(apiKey.trim())).apply()
    }

    fun synthesize(text: String, presenter: String, outputBase: File): List<OnlineSpeechChunk> {
        val key = apiKey ?: error("Не задан API-ключ онлайн-синтеза")
        val voice = when(presenter) { "maria" -> "coral"; "max" -> "ash"; else -> "alloy" }
        val instructions = when(presenter) {
            "maria" -> "Speak Russian clearly, warmly and expressively as a radio host."
            "max" -> "Speak Russian in a calm, low-energy late-night radio style."
            else -> "Speak Russian naturally and clearly as a radio host."
        }
        val chunks = splitText(text, 3500)
        val created = mutableListOf<OnlineSpeechChunk>()
        try {
            chunks.forEachIndexed { index, chunk ->
                val output = File(outputBase.parentFile, outputBase.name + "_%03d.mp3".format(index + 1))
                val body = JSONObject().put("model", model).put("voice", voice).put("input", chunk)
                    .put("instructions", instructions).put("response_format", "mp3").toString().toByteArray(Charsets.UTF_8)
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 20000; readTimeout = 180000
                    doOutput = true; setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "audio/mpeg")
                }
                try {
                    connection.outputStream.use { it.write(body) }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        val message = connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(600)
                        error("Онлайн TTS HTTP $code: ${message ?: "ошибка сервиса"}")
                    }
                    output.parentFile?.mkdirs()
                    connection.inputStream.use { input -> output.outputStream().buffered().use { input.copyTo(it) } }
                    check(output.length() > 512L) { "Онлайн TTS вернул пустой аудиофайл" }
                    created += OnlineSpeechChunk(output, chunk)
                } finally { connection.disconnect() }
            }
            return created
        } catch (t: Throwable) {
            created.forEach { it.file.delete() }
            throw t
        }
    }

    private fun splitText(value: String, maxChars: Int): List<String> {
        var remaining = value.trim()
        val chunks = mutableListOf<String>()
        while (remaining.length > maxChars) {
            var cut = remaining.lastIndexOfAny(charArrayOf('.', '!', '?', ';', '\n'), maxChars)
            if (cut < maxChars / 2) cut = remaining.lastIndexOf(' ', maxChars)
            if (cut <= 0) cut = maxChars
            chunks += remaining.substring(0, cut + 1).trim()
            remaining = remaining.substring(cut + 1).trim()
        }
        if (remaining.isNotBlank()) chunks += remaining
        return chunks
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("neirofm_tts_key", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("neirofm_tts_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val raw = Base64.decode(value, Base64.NO_WRAP); val iv = raw.copyOfRange(0,12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        }.getOrNull()
    }
}
