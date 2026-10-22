package com.music.bitchord.desktop

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopShortcutsTest {

    @Test
    fun `space starts and stops playback`() {
        assertEquals(DesktopShortcut.PlayPause, press(Key.Spacebar))
    }

    @Test
    fun `holding space does not toggle again`() {
        assertNull(press(Key.Spacebar, repeating = true))
    }

    @Test
    fun `option left goes back unless the player is open`() {
        assertEquals(DesktopShortcut.Back, press(Key.DirectionLeft, alt = true))
        assertNull(press(Key.DirectionLeft, alt = true, playerOpen = true))
    }

    @Test
    fun `command arrows follow the music shortcuts on mac`() {
        assertEquals(DesktopShortcut.Next, press(Key.DirectionRight, meta = true, mac = true))
        assertEquals(DesktopShortcut.Previous, press(Key.DirectionLeft, meta = true, mac = true))
        assertEquals(DesktopShortcut.VolumeUp, press(Key.DirectionUp, meta = true, mac = true))
        assertEquals(DesktopShortcut.VolumeDown, press(Key.DirectionDown, meta = true, mac = true))
        assertNull(press(Key.DirectionRight, meta = true, mac = false))
    }

    @Test
    fun `option command arrows seek and may repeat`() {
        assertEquals(
            DesktopShortcut.SeekForward,
            press(Key.DirectionRight, alt = true, meta = true, mac = true, repeating = true),
        )
        assertEquals(
            DesktopShortcut.SeekBackward,
            press(Key.DirectionLeft, alt = true, meta = true, mac = true),
        )
    }

    @Test
    fun `a shifted shortcut is left for the text field`() {
        assertNull(press(Key.DirectionLeft, meta = true, shift = true, mac = true))
        assertNull(press(Key.Spacebar, shift = true))
    }

    @Test
    fun `the first press is not a repeat`() {
        val tracker = KeyRepeatTracker()
        assertEquals(false, tracker.downRepeats(Key.Spacebar, down = true))
        assertEquals(true, tracker.downRepeats(Key.Spacebar, down = true))
        tracker.downRepeats(Key.Spacebar, down = false)
        assertEquals(false, tracker.downRepeats(Key.Spacebar, down = true))
    }

    private fun press(
        key: Key,
        alt: Boolean = false,
        meta: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        mac: Boolean = true,
        playerOpen: Boolean = false,
        repeating: Boolean = false,
    ) = yieldingShortcut(key, alt, meta, ctrl, shift, mac, playerOpen, repeating)
}
