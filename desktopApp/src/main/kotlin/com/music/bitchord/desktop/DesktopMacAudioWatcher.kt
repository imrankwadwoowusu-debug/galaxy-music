package com.music.bitchord.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * CoreAudio's push for output changes, the macOS counterpart of Windows'
 * endpoint notifications and [DesktopLinuxAudioWatcher].
 *
 * Java Sound does not follow a new default device on its own: headphones plugged
 * in after the line opened keep playing on the old output until the line is
 * reopened. The listener only bumps [DesktopAudioDevices.systemChanged].
 */
internal object DesktopMacAudioWatcher {

    /** 'dev#' — the list of audio devices. */
    private const val DEVICES = 0x64657623

    /** 'dOut' — which device is the default output. */
    private const val DEFAULT_OUTPUT = 0x644F7574

    /** 'glob' */
    private const val GLOBAL = 0x676c6f62

    /** kAudioObjectSystemObject */
    private const val SYSTEM = 1

    private var started = false

    private interface CoreAudio : Library {
        fun AudioObjectAddPropertyListener(
            objectId: Int,
            address: Pointer,
            listener: Listener,
            clientData: Pointer?,
        ): Int
    }

    private interface Listener : Callback {
        fun invoke(objectId: Int, addressCount: Int, addresses: Pointer?, clientData: Pointer?): Int
    }

    private val listener = object : Listener {
        override fun invoke(objectId: Int, addressCount: Int, addresses: Pointer?, clientData: Pointer?): Int {
            DesktopAudioDevices.systemChanged()
            return 0
        }
    }

    private val coreAudio: CoreAudio? by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { Native.load("CoreAudio", CoreAudio::class.java) }.getOrNull()
    }

    fun ensureStarted() {
        if (started || !DesktopPlatform.isMac) return
        val audio = coreAudio ?: run {
            DesktopTrackLog.log("CoreAudio is unavailable; output changes will not be followed")
            return
        }
        val devices = listen(audio, DEVICES)
        val defaultOutput = listen(audio, DEFAULT_OUTPUT)
        if (!devices && !defaultOutput) {
            DesktopTrackLog.log("CoreAudio output listener was not installed")
            return
        }
        started = true
    }

    private fun listen(audio: CoreAudio, property: Int): Boolean {
        val address = Memory(12)
        address.setInt(0, property)
        address.setInt(4, GLOBAL)
        address.setInt(8, 0)
        val status = runCatching {
            audio.AudioObjectAddPropertyListener(SYSTEM, address, listener, null)
        }.getOrElse {
            DesktopTrackLog.log("CoreAudio listener failed: ${it.message}")
            return false
        }
        // The address has to outlive the call. CoreAudio copies it, but keep the buffer reachable.
        addresses += address
        return status == 0
    }

    private val addresses = mutableListOf<Memory>()
}
