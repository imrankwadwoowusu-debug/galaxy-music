package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopMacDockTest {

    @Test
    fun `an idle queue clears the badge`() {
        assertNull(dockBadgeLabel(0))
        assertNull(dockBadgeLabel(-1))
    }

    @Test
    fun `the badge is the number still being saved`() {
        assertEquals("1", dockBadgeLabel(1))
        assertEquals("12", dockBadgeLabel(12))
    }

    @Test
    fun `a long queue stays readable on the icon`() {
        assertEquals("99+", dockBadgeLabel(100))
    }
}
