package com.neirofm.data

data class RadioSegment(
    val id: String,
    val type: Type,
    val title: String,
    val uri: String? = null,
    val durationHintMs: Long = 0L
) {
    enum class Type { MUSIC, PODCAST, ADVERTISEMENT, HOST, WEATHER, TIME }
}

object BroadcastCatalog {
    fun presenterLinks(): List<String> = listOf(
        "А мы продолжаем. Следующий трек уже в эфире — прибавляйте звук.",
        "Вы на волне Neiro FM. Пусть эта музыка станет саундтреком вашего дня.",
        "Никуда не переключайтесь. Впереди ещё много интересного."
    )
    fun adBreaks(): List<String> = listOf("Небольшая рекламная пауза. Спасибо, что остаетесь с Neiro FM.")
    fun podcastIntro(title: String): String = "А сейчас время подкаста: $title."
}
