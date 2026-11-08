package com.neirofm.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.neirofm.broadcast.BroadcastScheduler
import com.neirofm.data.PresenterScripts
import com.neirofm.voice.VoiceEngine

class RadioPlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_START_BROADCAST = "com.neirofm.action.START_BROADCAST"
        const val ACTION_STOP_BROADCAST = "com.neirofm.action.STOP_BROADCAST"
        const val ACTION_TEST_VOICE = "com.neirofm.action.TEST_VOICE"
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var voiceEngine: VoiceEngine? = null
    private var scheduler: BroadcastScheduler? = null

    override fun onCreate() {
        super.onCreate()
        val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        val exo = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(attributes, true)
            setHandleAudioBecomingNoisy(true)
        }
        player = exo
        mediaSession = MediaSession.Builder(this, exo).build()
        voiceEngine = VoiceEngine(applicationContext)
        voiceEngine?.let { scheduler = BroadcastScheduler(it) }
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val announcement = PresenterScripts.introFor(mediaItem?.mediaId.orEmpty()) ?: return
                exo.pause()
                scheduler?.announce(announcement.second, announcement.first) {
                    exo.play()
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_BROADCAST -> scheduler?.start()
            ACTION_STOP_BROADCAST -> scheduler?.stop()
            ACTION_TEST_VOICE -> scheduler?.testVoice()
        }
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || (!p.playWhenReady && p.mediaItemCount == 0)) stopSelf()
    }

    override fun onDestroy() {
        scheduler?.stop()
        scheduler = null
        voiceEngine?.shutdown()
        voiceEngine = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}