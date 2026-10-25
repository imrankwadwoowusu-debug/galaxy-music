package com.music.bitchord.playback.connect

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.playback.cast.CastSink
import com.music.bitchord.playback.cast.RemoteSpeaker
import com.music.bitchord.playback.toSong

class BitChordConnectSpeaker(
    private val client: BitChordConnectClient,
    private val onSpeakerStateChanged: () -> Unit = {},
) : RemoteSpeaker {

    override var sink: CastSink? = null

    override var active: Boolean = false
        private set

    private var lastRequestedSeekMs: Long? = null
    private var lastRequestedSeekAt: Long = 0L

    fun setActive(isActive: Boolean) {
        if (active == isActive) return
        active = isActive
        sink?.onRemoteRouteChanged()
        onSpeakerStateChanged()
    }

    fun onSnapshotReceived(snapshot: ConnectPlaybackSnapshot) {
        if (!active) return
        sink?.onRemotePlaybackChanged()
        sink?.onRemoteVolumeChanged()
        onSpeakerStateChanged()
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

    override fun setVolume(fraction: Float) {
        if (!active) return
        client.sendCommand(ConnectCommand(action = ConnectCommand.ACTION_SET_VOLUME, volume = fraction.coerceIn(0f, 1f)))
    }

    override fun onLocalTransition(item: MediaItem?, reason: Int) {
        if (!active) return
        val song = item?.toSong() ?: return
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
