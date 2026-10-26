package com.music.bitchord.playback.connect

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.playback.cast.CastSink
import com.music.bitchord.playback.cast.RemoteSpeaker
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.toSong

class BitChordConnectSpeaker(
    private val client: BitChordConnectClient,
    private val localPlayer: () -> Player?,
    private val onSpeakerStateChanged: () -> Unit = {},
) : RemoteSpeaker {

    override var sink: CastSink? = null

    override var active: Boolean = false
        private set

    private var lastRequestedSeekMs: Long? = null
    private var lastRequestedSeekAt: Long = 0L

    private var syncingFromRemote = false

    fun setActive(isActive: Boolean) {
        if (active == isActive) return
        active = isActive
        if (!isActive) {
            syncingFromRemote = false
        }
        sink?.onRemoteRouteChanged()
        onSpeakerStateChanged()
    }

    fun onSnapshotReceived(snapshot: ConnectPlaybackSnapshot) {
        if (!active) return
        val remoteSong = snapshot.song
        if (remoteSong != null) {
            syncRemoteSnapshot(snapshot)
        }
        sink?.onRemotePlaybackChanged()
        sink?.onRemoteVolumeChanged()
        onSpeakerStateChanged()
    }

    private fun syncRemoteSnapshot(snapshot: ConnectPlaybackSnapshot) {
        val remoteSong = snapshot.song ?: return
        val player = localPlayer() ?: return

        val currentMediaId = player.currentMediaItem?.mediaId
        val queueSize = snapshot.queue.size
        if (currentMediaId == remoteSong.videoId && (queueSize <= 1 || player.mediaItemCount == queueSize)) {
            return
        }

        Log.i("BitChordConnect", "syncRemoteSnapshot: remoteSong=${remoteSong.title} (${remoteSong.videoId}) currentMediaId=$currentMediaId count=${player.mediaItemCount} queueSize=$queueSize")

        val existingIndex = (0 until player.mediaItemCount).firstOrNull {
            player.getMediaItemAt(it).mediaId == remoteSong.videoId
        }

        syncingFromRemote = true
        try {
            if (existingIndex != null && player.mediaItemCount == queueSize) {
                player.seekTo(existingIndex, snapshot.positionMs)
                player.pause()
            } else {
                val queueSongs = if (snapshot.queue.isNotEmpty()) snapshot.queue else listOf(remoteSong)
                val targetIndex = snapshot.queueIndex.coerceIn(0, (queueSongs.size - 1).coerceAtLeast(0))
                val items = queueSongs.map { it.toMediaItem() }
                player.setMediaItems(items, targetIndex, snapshot.positionMs)
                player.pause()
            }
        } catch (e: Exception) {
            Log.w("BitChordConnect", "Failed to sync remote snapshot to local player: ${e.message}")
        } finally {
            syncingFromRemote = false
        }
    }

    override val playWhenReady: Boolean
        get() = if (active) client.remoteState.value?.playWhenReady ?: false else false

    override val isPlaying: Boolean
        get() = if (active) client.remoteState.value?.isPlaying ?: false else false

    override val playbackState: Int
        get() = if (active) client.remoteState.value?.playbackState ?: Player.STATE_IDLE else Player.STATE_IDLE

    override val positionMs: Long
        get() {
            if (!active) return 0L
            val seek = lastRequestedSeekMs
            if (seek != null && SystemClock.elapsedRealtime() - lastRequestedSeekAt < 1200) {
                return seek
            }
            val snapshot = client.remoteState.value ?: return 0L
            if (!snapshot.isPlaying) return snapshot.positionMs
            val elapsed = System.currentTimeMillis() - snapshot.timestamp
            val pos = snapshot.positionMs + elapsed
            return if (snapshot.durationMs > 0) pos.coerceAtMost(snapshot.durationMs) else pos
        }

    override val durationMs: Long
        get() = if (active) client.remoteState.value?.durationMs?.takeIf { it > 0 } ?: C.TIME_UNSET else C.TIME_UNSET

    override val deviceVolume: Int
        get() = if (active) ((client.remoteState.value?.volume ?: 1f) * 100).toInt().coerceIn(0, 100) else 100

    override fun play() {
        if (!active) return
        client.sendCommand(ConnectCommand(action = ConnectCommand.ACTION_RESUME))
    }

    override fun pause() {
        if (!active) return
        client.sendCommand(ConnectCommand(action = ConnectCommand.ACTION_PAUSE))
    }

    override fun seekTo(positionMs: Long) {
        if (!active) return
        lastRequestedSeekMs = positionMs
        lastRequestedSeekAt = SystemClock.elapsedRealtime()
        client.sendCommand(ConnectCommand(action = ConnectCommand.ACTION_SEEK, positionMs = positionMs))
    }

    override fun seekToNext() {
        if (!active) return
        client.sendCommand(ConnectCommand(action = ConnectCommand.ACTION_SKIP_NEXT))
    }

    override fun seekToPrevious() {
        if (!active) return
        client.sendCommand(ConnectCommand(action = ConnectCommand.ACTION_SKIP_PREVIOUS))
    }

    override fun setVolume(fraction: Float) {
        if (!active) return
        client.sendCommand(ConnectCommand(action = ConnectCommand.ACTION_SET_VOLUME, volume = fraction.coerceIn(0f, 1f)))
    }

    override fun onLocalTransition(item: MediaItem?, reason: Int) {
        if (!active || syncingFromRemote) return
        val song = item?.toSong() ?: return
        val currentRemoteSong = client.remoteState.value?.song
        if (song.videoId == currentRemoteSong?.videoId) {
            // Already playing on TV!
            return
        }
        client.sendCommand(
            ConnectCommand(
                action = ConnectCommand.ACTION_PLAY_SONGS,
                song = song,
                startIndex = 0,
            ),
        )
    }

    override fun onLocalQueueChanged() {
        // Queue sync handled via direct song play requests
    }

    override fun onLocalRepeatChanged(repeatMode: Int) {
        if (!active) return
        client.sendCommand(ConnectCommand(action = ConnectCommand.ACTION_SET_REPEAT, repeatMode = repeatMode))
    }

    override fun onLocalStartedPlaying(player: Player) {
        if (active) {
            // Keep phone's local player paused while TV is the active remote speaker
            player.pause()
        }
    }
}
