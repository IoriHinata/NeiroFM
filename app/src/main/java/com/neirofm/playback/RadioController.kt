package com.neirofm.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.neirofm.data.BroadcastQueuePlanner
import kotlin.random.Random

object RadioController {
    private const val PREFS = "neirofm"
    private fun key(type: String) = when (type) {
        "podcasts" -> "podcasts"
        "ads" -> "ads"
        else -> "music"
    }

    fun setLibrary(context: Context, type: String, uris: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(key(type), uris.toSet()).apply()
    }

    fun library(context: Context, type: String = "music"): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(key(type), emptySet())?.toList().orEmpty()

    private fun serviceAction(context: Context, action: String) {
        context.startService(Intent(context, RadioPlaybackService::class.java).setAction(action))
    }

    private fun controller(context: Context, action: (MediaController) -> Unit) {
        val token = SessionToken(context, ComponentName(context, RadioPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ runCatching { action(future.get()) } }, MoreExecutors.directExecutor())
    }

    fun start(context: Context) {
        serviceAction(context, RadioPlaybackService.ACTION_START_BROADCAST)
        val music = library(context, "music").shuffled()
        val podcasts = library(context, "podcasts").shuffled()
        val ads = library(context, "ads").shuffled()
        val planned = BroadcastQueuePlanner.build(music, podcasts, ads)
        val queue = planned.mapIndexed { index, entry ->
            val type = when (entry.type) {
                com.neirofm.data.QueueEntry.Type.MUSIC -> "MUSIC"
                com.neirofm.data.QueueEntry.Type.PODCAST -> "PODCAST"
                com.neirofm.data.QueueEntry.Type.ADVERTISEMENT -> "ADVERTISEMENT"
            }
            val title = Uri.parse(entry.uri).lastPathSegment?.substringAfterLast('/') ?: "аудиозапись"
            MediaItem.Builder()
                .setMediaId("$type|$index|$title")
                .setUri(Uri.parse(entry.uri))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build()
        }
        if (queue.isEmpty()) {
            serviceAction(context, RadioPlaybackService.ACTION_TEST_VOICE)
            return
        }
        controller(context) { c ->
            c.clearMediaItems()
            c.setMediaItems(queue)
            val randomEntry = BroadcastQueuePlanner.randomStartIndex(queue.size) ?: 0
            c.seekToDefaultPosition(randomEntry)
            c.prepare()
            c.addListener(object : Player.Listener {
                private var positioned = false
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (!positioned && playbackState == Player.STATE_READY) {
                        positioned = true
                        val duration = c.duration
                        if (duration > 20_000L) c.seekTo((duration * Random.nextDouble(0.08, 0.72)).toLong())
                        c.removeListener(this)
                    }
                }
            })
            c.play()
        }
    }

    fun pause(context: Context) {
        serviceAction(context, RadioPlaybackService.ACTION_STOP_BROADCAST)
        controller(context) { it.pause() }
    }

    fun resume(context: Context) {
        serviceAction(context, RadioPlaybackService.ACTION_START_BROADCAST)
        controller(context) { it.play() }
    }

    fun stop(context: Context) {
        serviceAction(context, RadioPlaybackService.ACTION_STOP_BROADCAST)
        controller(context) { it.stop(); it.clearMediaItems() }
    }

    fun testVoice(context: Context) = serviceAction(context, RadioPlaybackService.ACTION_TEST_VOICE)
}