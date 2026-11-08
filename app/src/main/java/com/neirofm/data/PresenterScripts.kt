package com.neirofm.data

object PresenterScripts {
    fun introFor(mediaId: String): Pair<String, String>? {
        val parts = mediaId.split("|", limit = 3)
        if (parts.size < 2) return null
        val title = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "следующий материал"
        return when (parts[0]) {
            "PODCAST" -> "maria" to "А сейчас в эфире подкаст «$title». Устраивайтесь поудобнее, начинаем."
            "ADVERTISEMENT" -> "alex" to "Небольшая рекламная пауза. Послушайте, возможно, это предложение будет вам полезно. $title"
            else -> null
        }
    }
}