package com.music.bitchord.desktop

import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMacGesturesTest {

    @Test
    fun `a swipe to the right goes back`() {
        assertTrue(DesktopMacGestures.isBackSwipe(deltaX = 1.0, deltaY = 0.0))
    }

    @Test
    fun `a swipe to the left is not back`() {
        assertFalse(DesktopMacGestures.isBackSwipe(deltaX = -1.0, deltaY = 0.0))
    }

    @Test
    fun `a vertical gesture is not navigation`() {
        assertFalse(DesktopMacGestures.isBackSwipe(deltaX = 0.2, deltaY = 1.0))
    }

    @Test
    fun `macOS exports the global block class`() {
        if (!DesktopPlatform.isMac) return
        val isa = DesktopMacGestures.globalBlockClass()
        assertTrue(isa != null && isa != Pointer.NULL)
    }
}
