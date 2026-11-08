package com.neirofm.data

import android.content.Context
import android.net.Uri

data class RadioScript(val id: String, val type: Type, val title: String, val presenter: String, val text: String) {
    enum class Type { GREETING, PODCAST, ADVERTISEMENT, WEATHER, TIME, MUSIC_LINK, NIGHT, MORNING, GENERAL }
}

object ScriptLibrary {
    private const val PREFS = "neirofm_scripts"
    private const val KEY = "items"

    fun classify(fileName: String): RadioScript.Type {
        val name = fileName.lowercase().replace('_', ' ').replace('-', ' ')
        return when {
            listOf("реклам", "advert", "ad ").any(name::contains) -> RadioScript.Type.ADVERTISEMENT
            listOf("подкаст", "podcast", "тема", "topic").any(name::contains) -> RadioScript.Type.PODCAST
            listOf("погод", "weather").any(name::contains) -> RadioScript.Type.WEATHER
            listOf("время", "час", "time", "hour").any(name::contains) -> RadioScript.Type.TIME
            listOf("привет", "знаком", "greeting", "hello").any(name::contains) -> RadioScript.Type.GREETING
            listOf("утро", "morning", "завтрак").any(name::contains) -> RadioScript.Type.MORNING
            listOf("ночь", "ночн", "night", "после полуночи").any(name::contains) -> RadioScript.Type.NIGHT
            listOf("трек", "музык", "music", "track").any(name::contains) -> RadioScript.Type.MUSIC_LINK
            else -> RadioScript.Type.GENERAL
        }
    }

    fun import(context: Context, uri: Uri, fileName: String, content: String): RadioScript? {
        val text = content.trim().take(20_000)
        if (text.isBlank()) return null
        val type = classify(fileName)
        val title = fileName.substringBeforeLast('.', fileName).replace('_', ' ').replace('-', ' ')
        val presenter = when (type) {
            RadioScript.Type.ADVERTISEMENT, RadioScript.Type.MUSIC_LINK -> "alex"
            RadioScript.Type.PODCAST, RadioScript.Type.MORNING, RadioScript.Type.GREETING -> "maria"
            RadioScript.Type.NIGHT -> "max"
            else -> listOf("alex", "maria", "max").random()
        }
        val item = RadioScript(uri.toString(), type, title, presenter, text)
        val all = get(context).filterNot { it.id == item.id } + item
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, all.joinToString("\n---NEIRO-SCRIPT---\n") { listOf(it.id, it.type.name, it.title, it.presenter, it.text).joinToString("\n") })
            .apply()
        return item
    }

    fun get(context: Context): List<RadioScript> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        return raw.split("\n---NEIRO-SCRIPT---\n").mapNotNull { block ->
            val p = block.split("\n", limit = 5)
            if (p.size != 5) null else runCatching {
                RadioScript(p[0], RadioScript.Type.valueOf(p[1]), p[2], p[3], p[4])
            }.getOrNull()
        }
    }

    fun forType(context: Context, type: RadioScript.Type) = get(context).filter { it.type == type }
}
