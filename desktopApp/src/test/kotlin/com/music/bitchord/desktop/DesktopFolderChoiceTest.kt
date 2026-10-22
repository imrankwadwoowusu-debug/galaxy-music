package com.music.bitchord.desktop

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopFolderChoiceTest {

    @Test
    fun `a cancelled mac panel is not a folder`() {
        assertNull(DesktopLocalMusic.chosenDirectory(null, null))
        assertNull(DesktopLocalMusic.chosenDirectory("/Users/a/", null))
        assertNull(DesktopLocalMusic.chosenDirectory(null, "Music"))
    }

    @Test
    fun `the mac panel joins the parent and the chosen folder`() {
        assertEquals(
            Paths.get("/Users/a/Music"),
            DesktopLocalMusic.chosenDirectory("/Users/a/", "Music"),
        )
    }
}
