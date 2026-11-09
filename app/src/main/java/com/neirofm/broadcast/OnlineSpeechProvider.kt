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

    fun synthesize(text: String, presenter: String, output: File): Long {
        val key = apiKey ?: error("Не задан API-ключ онлайн-синтеза")
        val voice = when(presenter) { "maria" -> "coral"; "max" -> "ash"; else -> "alloy" }
        val instructions = when(presenter) {
            "maria" -> "Speak Russian clearly, warmly and expressively as a radio host."
            "max" -> "Speak Russian in a calm, low-energy late-night radio style."
            else -> "Speak Russian naturally and clearly as a radio host."
        }
        val chunks = splitText(text, 3500)
        val raw = File(output.parentFile, output.name + ".pcm")
        try {
            raw.parentFile?.mkdirs()
            raw.outputStream().buffered().use { rawOut ->
                chunks.forEachIndexed { index, chunk ->
                    val body = JSONObject().put("model", model).put("voice", voice).put("input", chunk)
                        .put("instructions", instructions).put("response_format", "pcm").toString().toByteArray(Charsets.UTF_8)
                    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"; connectTimeout = 20000; readTimeout = 180000
                        doOutput = true; setRequestProperty("Authorization", "Bearer $key")
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Accept", "application/octet-stream")
                    }
                    try {
                        connection.outputStream.use { it.write(body) }
                        val code = connection.responseCode
                        if (code !in 200..299) {
                            val message = connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(600)
                            error("Онлайн TTS HTTP $code: ${message ?: "ошибка сервиса"}")
                        }
                        connection.inputStream.use { it.copyTo(rawOut) }
                    } finally { connection.disconnect() }
                }
            }
            check(raw.length() > 512L) { "Онлайн TTS вернул пустой аудиофайл" }
            output.parentFile?.mkdirs()
            val dataBytes = raw.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            java.io.DataOutputStream(output.outputStream().buffered()).use { out ->
                out.writeBytes("RIFF"); writeLeInt(out, 36 + dataBytes)
                out.writeBytes("WAVE"); out.writeBytes("fmt "); writeLeInt(out, 16)
                writeLeShort(out, 1); writeLeShort(out, 1)
                writeLeInt(out, 24000); writeLeInt(out, 48000)
                writeLeShort(out, 2); writeLeShort(out, 16)
                out.writeBytes("data"); writeLeInt(out, dataBytes)
                raw.inputStream().buffered().use { it.copyTo(out) }
            }
            check(output.length() > 44L) { "Не удалось собрать WAV из онлайн TTS" }
            return output.length()
        } catch (t: Throwable) {
            output.delete()
            throw t
        } finally { raw.delete() }
    }

    private fun splitText(value: String, maxChars: Int): List<String> {
        var remaining = value.trim()
        val chunks = mutableListOf<String>()
        while (remaining.length > maxChars) {
            var cut = remaining.lastIndexOfAny(charArrayOf('.', '!', '?', ';', '\\n'), maxChars)
            if (cut < maxChars / 2) cut = remaining.lastIndexOf(' ', maxChars)
            if (cut <= 0) cut = maxChars
            chunks += remaining.substring(0, cut + 1).trim()
            remaining = remaining.substring(cut + 1).trim()
        }
        if (remaining.isNotBlank()) chunks += remaining
        return chunks
    }

    private fun writeLeInt(out: java.io.DataOutputStream, value: Int) {
        out.writeByte(value and 0xff); out.writeByte((value ushr 8) and 0xff)
        out.writeByte((value ushr 16) and 0xff); out.writeByte((value ushr 24) and 0xff)
    }
    private fun writeLeShort(out: java.io.DataOutputStream, value: Int) {
        out.writeByte(value and 0xff); out.writeByte((value ushr 8) and 0xff)
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
