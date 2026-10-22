package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMacFullScreenTest {

    @Test
    fun `a plain click on the green button enters full screen`() {
        assertEquals(MacGreenButton.FullScreen, macGreenButton(optionDown = false))
    }

    @Test
    fun `option-click on the green button zooms`() {
        assertEquals(MacGreenButton.Zoom, macGreenButton(optionDown = true))
    }

    @Test
    fun `the window menu names full screen for the state the window is in`() {
        assertEquals("Enter Full Screen", fullScreenMenuTitle(inFullScreen = false, "Enter Full Screen", "Exit Full Screen"))
        assertEquals("Quitter le plein écran", fullScreenMenuTitle(inFullScreen = true, "Passer en plein écran", "Quitter le plein écran"))
    }

    @Test
    fun `f11 stays with the system on mac and fills the screen elsewhere`() {
        assertFalse(desktopHandlesF11(mac = true))
        assertTrue(desktopHandlesF11(mac = false))
    }

    @Test
    fun `the title bar follows the system double-click setting`() {
        assertEquals(MacTitleBarAction.Zoom, macTitleBarAction("Maximize"))
        assertEquals(MacTitleBarAction.Zoom, macTitleBarAction(null))
        assertEquals(MacTitleBarAction.Minimize, macTitleBarAction("Minimize"))
        assertEquals(MacTitleBarAction.Ignore, macTitleBarAction("None"))
    }
}
