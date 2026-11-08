package com.neirofm.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import android.net.Uri
import android.content.ComponentName

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
                val items = songs.map { MediaItem.fromUri(Uri.parse(it)) }
                c.setMediaItems(items, 0, 0L)
                c.prepare()
                c.play()
            }
        }
    }

    fun pause(context: Context) = controller(context) { it.pause() }
    fun resume(context: Context) = controller(context) { it.play() }
    fun stop(context: Context) = controller(context) { it.stop() }
}
