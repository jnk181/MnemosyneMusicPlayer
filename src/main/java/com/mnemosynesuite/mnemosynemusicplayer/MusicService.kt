package com.mnemosynesuite.mnemosynemusicplayer

import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Binder
import android.os.IBinder
import android.util.Log

class MusicService : Service() {

    private val binder = MusicBinder()
    var mediaPlayer: MediaPlayer? = null
        private set
    var currentPlayingTrack: AudioTrack? = null
        private set

    // Callbacks to update the Activity UI when state changes happen inside the Service
    var onTrackPreparedListener: ((AudioTrack) -> Unit)? = null
    var onTrackCompletedListener: (() -> Unit)? = null

    var onSeekCompletedListener: ((Int) -> Unit)? = null

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        initializeMediaPlayer()
    }

    private fun initializeMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            setOnPreparedListener { mp ->
                mp.start()
                currentPlayingTrack?.let { track ->
                    onTrackPreparedListener?.invoke(track)
                }
            }
            setOnCompletionListener {
                onTrackCompletedListener?.invoke()
            }
            setOnErrorListener { _, what, extra ->
                Log.e("MusicService", "MediaPlayer native error: what=$what extra=$extra")
                true
            }

            // 2. Trigger the callback safely without passing UI components into the Service
            setOnSeekCompleteListener { mp ->
                onSeekCompletedListener?.invoke(mp.currentPosition)
            }
        }
    }

    fun executePlayback(track: AudioTrack) {
        try {
            // Your custom lifecycle discovery fix:
            // Only reset if a track has already been loaded once
            if (currentPlayingTrack != null) {
                mediaPlayer?.reset()
            }

            mediaPlayer?.setDataSource(track.filePath)
            mediaPlayer?.prepareAsync()
            currentPlayingTrack = track

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun togglePlayback(): Boolean {
        val player = mediaPlayer ?: return false
        return if (player.isPlaying) {
            player.pause()
            false // Not playing
        } else {
            player.start()
            true // Is playing
        }
    }

    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying as Boolean

    fun stopPlayback() {
        mediaPlayer?.let { player ->
            try {
                if (currentPlayingTrack != null) {
                    if (player.isPlaying) {
                        player.stop()
                    }
                    player.reset()
                }
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}