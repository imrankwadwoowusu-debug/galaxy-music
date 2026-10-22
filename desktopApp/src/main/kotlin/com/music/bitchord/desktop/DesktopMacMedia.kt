package com.music.bitchord.desktop

import java.util.concurrent.atomic.AtomicBoolean

/**
 * macOS Now Playing / Control Center / Lock Screen / media keys via MediaPlayer.framework.
 *
 * The macOS counterpart of [DesktopWindowsMedia] and [DesktopMprisController]: the player
 * hands it state, it hands back button presses. The work lives in `libbitchord_nowplaying.dylib`.
 *
 * Absent, everything here is a no-op.
 */
internal object DesktopMacMedia {

    private val started = AtomicBoolean(false)

    private val shutdownHookAdded = AtomicBoolean(false)

    @Volatile
    private var controller: Controller? = null

    /** What a media key does, filled in by the player. */
    internal class Controller(
        val onPlay: () -> Unit,
        val onPause: () -> Unit,
        val onPlayPause: () -> Unit,
        val onNext: () -> Unit,
        val onPrevious: () -> Unit,
        val onStop: () -> Unit,
        /** Moves playback to a position the shell's scrubber was dragged to, in milliseconds. */
        val onSeek: (Long) -> Unit,
    )

    private val available: Boolean by lazy {
        if (!DesktopPlatform.isMac) return@lazy false
        runCatching { DesktopAnalysisRuntime.loadNative(LIBRARY) }
            .onFailure { DesktopTrackLog.log("macOS media controls unavailable: ${it.message}") }
            .isSuccess
    }

    fun start(controller: Controller) {
        this.controller = controller
        if (!available || !started.compareAndSet(false, true)) return
        if (!nativeStart()) {
            started.set(false)
            DesktopTrackLog.log("macOS media controls could not be created")
            return
        }
        if (shutdownHookAdded.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread(::stop, "bitchord-nowplaying-stop"))
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        controller = null
        runCatching { nativeStop() }
    }

    /** Publishes the current track. Called on every change the shell would show. */
    fun publish(state: DesktopPlaybackState) {
        if (!started.get()) return
        val song = state.song
        runCatching {
            nativeUpdate(
                song?.title.orEmpty(),
                song?.artist.orEmpty(),
                song?.albumName.orEmpty(),
                song?.thumbnailUrl.orEmpty(),
                state.isPlaying,
                state.positionMs,
                state.durationMs,
            )
        }
    }

    @JvmStatic
    fun onButton(button: Int) {
        val target = controller ?: return
        when (button) {
            BUTTON_PLAY -> target.onPlay()
            BUTTON_PAUSE -> target.onPause()
            BUTTON_TOGGLE -> target.onPlayPause()
            BUTTON_NEXT -> target.onNext()
            BUTTON_PREVIOUS -> target.onPrevious()
            BUTTON_STOP -> target.onStop()
        }
    }

    @JvmStatic
    fun onSeek(positionMs: Long) {
        controller?.onSeek(positionMs)
    }

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeUpdate(
        title: String,
        artist: String,
        album: String,
        artUrl: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
    )

    private const val LIBRARY = "bitchord_nowplaying"

    private const val BUTTON_PLAY = 0
    private const val BUTTON_PAUSE = 1
    private const val BUTTON_NEXT = 2
    private const val BUTTON_PREVIOUS = 3
    private const val BUTTON_STOP = 4
    private const val BUTTON_TOGGLE = 5
}
