package com.neirofm.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlin.random.Random

object RadioController {
    private const val PREFS = "neirofm"
    private const val KEY_LIBRARY = "library"

    fun setLibrary(context: Context, uris: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_LIBRARY, uris.toSet()).apply()
    }

    fun library(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_LIBRARY, emptySet())?.toList().orEmpty()

    private fun controller(context: Context, action: (MediaController) -> Unit) {
        val token = SessionToken(context, ComponentName(context, RadioPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ runCatching { action(future.get()) } }, MoreExecutors.directExecutor())
    }

    fun start(context: Context) {
        val songs = library(context).shuffled()
        controller(context) { c ->
            if (songs.isNotEmpty()) {
                c.clearMediaItems()
                c.setMediaItems(songs.map { MediaItem.fromUri(Uri.parse(it)) })
                val randomEntry = Random.nextInt(songs.size)
                c.seekToDefaultPosition(randomEntry)
                c.prepare()
                c.addListener(object : Player.Listener {
                    private var positioned = false
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (!positioned && playbackState == Player.STATE_READY && c.currentMediaItemIndex == randomEntry) {
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
    }

    fun pause(context: Context) = controller(context) { it.pause() }
    fun resume(context: Context) = controller(context) { it.play() }
    fun stop(context: Context) = controller(context) { it.stop(); it.clearMediaItems() }
}
