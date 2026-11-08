package com.neirofm.data

import kotlin.random.Random

data class QueueEntry(val uri: String, val type: Type) {
    enum class Type { MUSIC, ADVERTISEMENT, PODCAST }
}

object BroadcastQueuePlanner {
    fun build(music: List<String>, podcasts: List<String>, ads: List<String>): List<QueueEntry> {
        val queue = mutableListOf<QueueEntry>()
        music.forEachIndexed { index, uri ->
            queue += QueueEntry(uri, QueueEntry.Type.MUSIC)
            if (index > 0 && index % 5 == 0 && ads.isNotEmpty()) {
                queue += QueueEntry(ads[(index / 5 - 1) % ads.size], QueueEntry.Type.ADVERTISEMENT)
            }
            if (index > 0 && index % 8 == 0 && podcasts.isNotEmpty()) {
                queue += QueueEntry(podcasts[(index / 8 - 1) % podcasts.size], QueueEntry.Type.PODCAST)
            }
        }
        return queue
    }

    fun randomStartIndex(size: Int, random: Random = Random.Default): Int? =
        if (size <= 0) null else random.nextInt(size)
}
