package com.ivankolang.tunomusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSessionService

class MusicPlaybackService : MediaSessionService() {
    private var mediaSession: androidx.media3.session.MediaSession? = null
    private var cloudSession: MediaSession? = null
    private var cloudTitle = "SoundCloud"
    private var cloudArtist = "Tuno Music"
    private var cloudArtwork = ""
    private var cloudDuration = 0L
    private var cloudPosition = 0L
    private var cloudPlaying = false

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        mediaSession = androidx.media3.session.MediaSession.Builder(this, player).build()
        createCloudNotificationChannel()
        cloudSession = MediaSession(this, "TunoSoundCloud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    cloudPlaying = true
                    updateCloudSession()
                    startForeground(CLOUD_NOTIFICATION_ID, buildCloudNotification())
                    sendCloudCommand(ACTION_CLOUD_PLAY)
                }
                override fun onPause() {
                    cloudPlaying = false
                    updateCloudSession()
                    startForeground(CLOUD_NOTIFICATION_ID, buildCloudNotification())
                    sendCloudCommand(ACTION_CLOUD_PAUSE)
                }
                override fun onSkipToNext() = sendCloudCommand(ACTION_CLOUD_NEXT)
                override fun onSkipToPrevious() = sendCloudCommand(ACTION_CLOUD_PREVIOUS)
                override fun onStop() {
                    cloudPlaying = false
                    updateCloudSession()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    sendCloudCommand(ACTION_CLOUD_STOP)
                }
            })
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WIDGET_TOGGLE -> {
                val source = getSharedPreferences("tuno_widget", MODE_PRIVATE).getString("source", "none")
                if (source == "cloud") {
                    cloudPlaying = !cloudPlaying
                    updateCloudSession()
                    startForeground(CLOUD_NOTIFICATION_ID, buildCloudNotification())
                    sendCloudCommand(if (cloudPlaying) ACTION_CLOUD_PLAY else ACTION_CLOUD_PAUSE)
                } else {
                    mediaSession?.player?.let { player ->
                        if (player.isPlaying || player.playWhenReady) player.pause() else player.play()
                    }
                }
            }
            ACTION_WIDGET_NEXT, ACTION_WIDGET_PREVIOUS -> {
                val source = getSharedPreferences("tuno_widget", MODE_PRIVATE).getString("source", "none")
                if (source == "cloud") {
                    sendCloudCommand(if (intent.action == ACTION_WIDGET_NEXT) ACTION_CLOUD_NEXT else ACTION_CLOUD_PREVIOUS)
                } else {
                    mediaSession?.player?.let { player ->
                        if (intent.action == ACTION_WIDGET_NEXT) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
                    }
                }
            }
            ACTION_CLOUD_PLAY, ACTION_CLOUD_UPDATE, ACTION_CLOUD_PAUSE -> {
                cloudTitle = intent.getStringExtra(EXTRA_TITLE) ?: cloudTitle
                cloudArtist = intent.getStringExtra(EXTRA_ARTIST) ?: cloudArtist
                cloudArtwork = intent.getStringExtra(EXTRA_ARTWORK) ?: cloudArtwork
                cloudDuration = intent.getLongExtra(EXTRA_DURATION, cloudDuration)
                cloudPosition = intent.getLongExtra(EXTRA_POSITION, cloudPosition)
                cloudPlaying = intent.action == ACTION_CLOUD_PLAY ||
                    (intent.action == ACTION_CLOUD_UPDATE && intent.getBooleanExtra(EXTRA_PLAYING, cloudPlaying))
                updateCloudSession()
                startForeground(CLOUD_NOTIFICATION_ID, buildCloudNotification())
                if (intent.getBooleanExtra(EXTRA_FROM_SYSTEM, false)) {
                    sendCloudCommand(if (intent.action == ACTION_CLOUD_PLAY) ACTION_CLOUD_PLAY else ACTION_CLOUD_PAUSE)
                }
            }
            ACTION_CLOUD_NEXT -> sendCloudCommand(ACTION_CLOUD_NEXT)
            ACTION_CLOUD_PREVIOUS -> sendCloudCommand(ACTION_CLOUD_PREVIOUS)
            ACTION_CLOUD_STOP -> {
                sendCloudCommand(ACTION_CLOUD_STOP)
                cloudPlaying = false
                cloudSession?.setActive(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun updateCloudSession() {
        val session = cloudSession ?: return
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, cloudTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, cloudArtist)
                .putString(MediaMetadata.METADATA_KEY_ART_URI, cloudArtwork)
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, cloudArtwork)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, cloudDuration)
                .build()
        )
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (cloudPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    cloudPosition, if (cloudPlaying) 1f else 0f
                ).build()
        )
        session.setActive(true)
    }

    private fun buildCloudNotification(): Notification {
        val session = cloudSession
        val action = if (cloudPlaying) ACTION_CLOUD_PAUSE else ACTION_CLOUD_PLAY
        val icon = if (cloudPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val actionIntent = PendingIntent.getService(
            this, 71, Intent(this, MusicPlaybackService::class.java).setAction(action)
                .putExtra(EXTRA_FROM_SYSTEM, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 72, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CLOUD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(cloudTitle)
            .setContentText(cloudArtist)
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(cloudPlaying)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(icon, if (cloudPlaying) "Пауза" else "Играть", actionIntent).build())
        if (session != null) {
            builder.setStyle(
                Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0)
            )
        }
        return builder.build()
    }

    private fun createCloudNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CLOUD_CHANNEL_ID, "Воспроизведение Tuno", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Управление музыкой SoundCloud в Tuno" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun sendCloudCommand(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    override fun onGetSession(controllerInfo: androidx.media3.session.MediaSession.ControllerInfo): androidx.media3.session.MediaSession? = mediaSession

    override fun onDestroy() {
        cloudSession?.run { setActive(false); release() }
        cloudSession = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_WIDGET_TOGGLE = "com.ivankolang.tunomusic.WIDGET_TOGGLE"
        const val ACTION_WIDGET_NEXT = "com.ivankolang.tunomusic.WIDGET_NEXT"
        const val ACTION_WIDGET_PREVIOUS = "com.ivankolang.tunomusic.WIDGET_PREVIOUS"
        const val ACTION_CLOUD_PLAY = "com.ivankolang.tunomusic.CLOUD_PLAY"
        const val ACTION_CLOUD_PAUSE = "com.ivankolang.tunomusic.CLOUD_PAUSE"
        const val ACTION_CLOUD_UPDATE = "com.ivankolang.tunomusic.CLOUD_UPDATE"
        const val ACTION_CLOUD_STOP = "com.ivankolang.tunomusic.CLOUD_STOP"
        const val ACTION_CLOUD_NEXT = "com.ivankolang.tunomusic.CLOUD_NEXT"
        const val ACTION_CLOUD_PREVIOUS = "com.ivankolang.tunomusic.CLOUD_PREVIOUS"
        const val EXTRA_TITLE = "cloud_title"
        const val EXTRA_ARTIST = "cloud_artist"
        const val EXTRA_ARTWORK = "cloud_artwork"
        const val EXTRA_DURATION = "cloud_duration"
        const val EXTRA_POSITION = "cloud_position"
        const val EXTRA_PLAYING = "cloud_playing"
        const val EXTRA_FROM_SYSTEM = "cloud_from_system"
        private const val CLOUD_CHANNEL_ID = "tuno_soundcloud_playback"
        private const val CLOUD_NOTIFICATION_ID = 74
    }
}
