package com.neirofm

import com.neirofm.data.BroadcastQueuePlanner
import com.neirofm.data.QueueEntry
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastQueuePlannerTest {
    @Test fun podcastsCanPlayWhenMusicLibraryIsEmpty() {
        val queue = BroadcastQueuePlanner.build(emptyList(), listOf("podcast"), listOf("ad"))
        assertEquals(1, queue.size)
        assertEquals(QueueEntry.Type.PODCAST, queue.single().type)
    }

    @Test fun insertsAdsAndPodcastsAtConfiguredIntervals() {
        val music = (1..17).map { "music-$it" }
        val queue = BroadcastQueuePlanner.build(music, listOf("podcast"), listOf("ad"))
        assertEquals(17, queue.count { it.type == QueueEntry.Type.MUSIC })
        assertEquals(3, queue.count { it.type == QueueEntry.Type.ADVERTISEMENT })
        assertEquals(2, queue.count { it.type == QueueEntry.Type.PODCAST })
    }

    @Test fun randomStartIsAlwaysWithinQueueBounds() {
        repeat(100) {
            val index = BroadcastQueuePlanner.randomStartIndex(7, Random(it))
            assertTrue(index != null && index in 0..6)
        }
    }

    @Test fun randomStartForEmptyQueueIsNull() {
        assertNull(BroadcastQueuePlanner.randomStartIndex(0))
    }
}

