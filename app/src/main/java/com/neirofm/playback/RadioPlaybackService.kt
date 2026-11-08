package com.neirofm.playback

import android.content.Intent
import android.app.Notification
import android.app.NotificationChannel
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
        const val ACTION_INSTALL_NEURAL_VOICE = "com.neirofm.action.INSTALL_NEURAL_VOICE"
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var voiceEngine: VoiceEngine? = null
    private var scheduler: BroadcastScheduler? = null
    @Volatile private var broadcastActive = false

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
            scheduler = BroadcastScheduler(engine, applicationContext) { text, presenter, done ->
                val wasPlaying = exo.isPlaying
                if (wasPlaying) exo.pause()
                engine.speak(text, presenter) {
                    Handler(Looper.getMainLooper()).post {
                        if (wasPlaying && broadcastActive) exo.play()
                        done()
                    }
                }
            }
        }
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
            ACTION_START_BROADCAST -> { broadcastActive = true; scheduler?.start() }
            ACTION_STOP_BROADCAST -> { broadcastActive = false; scheduler?.stop() }
            ACTION_TEST_VOICE -> scheduler?.testVoice()
            ACTION_INSTALL_NEURAL_VOICE -> installNeuralVoice()
        }
        return START_NOT_STICKY
    }

    private fun installNeuralVoice() {
        val channelId = "neirofm_voice_download"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Установка голосов NeiroFM", android.app.NotificationManager.IMPORTANCE_LOW)
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notificationBuilder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }
        val notification = notificationBuilder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("NeiroFM — установка голосов")
            .setContentText("Загружаются три русские нейросетевые модели")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                if (player?.isPlaying == true) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            startForeground(2107, notification, type)
        } else {
            startForeground(2107, notification)
        }
        voiceEngine?.installNeuralVoice {
            Handler(Looper.getMainLooper()).post { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || (!p.playWhenReady && p.mediaItemCount == 0)) stopSelf()
    }

    override fun onDestroy() {
        broadcastActive = false
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