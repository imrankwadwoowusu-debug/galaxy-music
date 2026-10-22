package com.music.bitchord.desktop

import androidx.compose.ui.input.key.Key

/**
 * Shortcuts a focused text field is allowed to take first.
 *
 * On macOS, Option+Left moves by word and Command+Left moves to the start of the line.
 * Those are handled on the way back up, so a field that used the key wins and the player
 * only sees what was left over. Space starts and stops playback the same way.
 */
/** One step of Option-Command-Left or Right. Holding the keys repeats it. */
internal const val DESKTOP_SEEK_STEP_MS = 10_000L

/** One step of Command-Up or Command-Down. */
internal const val DESKTOP_VOLUME_STEP = 0.05f

internal enum class DesktopShortcut {
    PlayPause,
    Next,
    Previous,
    SeekForward,
    SeekBackward,
    VolumeUp,
    VolumeDown,
    Back,
}

internal class KeyRepeatTracker {
    private var held: Key? = null

    /** True when this key is already down, which is how a held key repeats. */
    fun downRepeats(key: Key, down: Boolean): Boolean {
        if (!down) {
            if (key == held) held = null
            return false
        }
        val again = key == held
        held = key
        return again
    }
}

internal fun yieldingShortcut(
    key: Key,
    alt: Boolean,
    meta: Boolean,
    ctrl: Boolean,
    shift: Boolean,
    mac: Boolean,
    playerOpen: Boolean,
    repeating: Boolean,
): DesktopShortcut? {
    val shortcut = when {
        shift || ctrl -> null
        mac && meta && alt && key == Key.DirectionRight -> DesktopShortcut.SeekForward
        mac && meta && alt && key == Key.DirectionLeft -> DesktopShortcut.SeekBackward
        mac && meta && key == Key.DirectionRight -> DesktopShortcut.Next
        mac && meta && key == Key.DirectionLeft -> DesktopShortcut.Previous
        mac && meta && key == Key.DirectionUp -> DesktopShortcut.VolumeUp
        mac && meta && key == Key.DirectionDown -> DesktopShortcut.VolumeDown
        !meta && alt && key == Key.DirectionLeft && !playerOpen -> DesktopShortcut.Back
        !meta && !alt && key == Key.Spacebar -> DesktopShortcut.PlayPause
        else -> null
    } ?: return null
    val repeats = shortcut == DesktopShortcut.SeekForward ||
        shortcut == DesktopShortcut.SeekBackward ||
        shortcut == DesktopShortcut.VolumeUp ||
        shortcut == DesktopShortcut.VolumeDown
    if (repeating && !repeats) return null
    return shortcut
}
