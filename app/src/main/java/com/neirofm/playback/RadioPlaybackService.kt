package com.neirofm.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.neirofm.broadcast.BroadcastScheduler
import com.neirofm.voice.VoiceEngine

class RadioPlaybackService : MediaSessionService() {
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
        voiceEngine?.let { engine ->
            val schedule = BroadcastScheduler(engine)
            scheduler = schedule
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) schedule.start() else schedule.stop()
                }
            })
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
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
