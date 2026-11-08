package com.neirofm.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlin.random.Random

object RadioController {
    private const val PREFS = "neirofm"
    private const val KEY_MUSIC = "music"
    private const val KEY_PODCASTS = "podcasts"
    private const val KEY_ADS = "ads"

    fun setLibrary(context: Context, type: String, uris: List<String>) {
        val key = when (type) {
            "podcasts" -> KEY_PODCASTS
            "ads" -> KEY_ADS
            else -> KEY_MUSIC
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(key, uris.toSet()).apply()
    }

    fun library(context: Context, type: String = "music"): List<String> {
        val key = when (type) {
            "podcasts" -> KEY_PODCASTS
            "ads" -> KEY_ADS
            else -> KEY_MUSIC
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(key, emptySet())?.toList().orEmpty()
    }

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
        val queue = mutableListOf<MediaItem>()
        music.forEachIndexed { index, uri ->
            queue += MediaItem.fromUri(Uri.parse(uri))
            if (index > 0 && index % 5 == 0 && ads.isNotEmpty()) queue += MediaItem.fromUri(Uri.parse(ads[(index / 5 - 1) % ads.size]))
            if (index > 0 && index % 8 == 0 && podcasts.isNotEmpty()) queue += MediaItem.fromUri(Uri.parse(podcasts[(index / 8 - 1) % podcasts.size]))
        }
        if (queue.isEmpty()) {
            serviceAction(context, RadioPlaybackService.ACTION_TEST_VOICE)
            return
        }
        controller(context) { c ->
            c.clearMediaItems()
            c.setMediaItems(queue)
            val randomEntry = Random.nextInt(music.size.coerceAtLeast(1))
            c.seekToDefaultPosition(randomEntry.coerceAtMost(queue.lastIndex))
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
