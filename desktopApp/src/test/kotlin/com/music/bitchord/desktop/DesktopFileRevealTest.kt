package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DesktopFileRevealTest {

    @Test
    fun `the label names the file manager of the platform`() {
        assertEquals("d_show_in_finder", revealLabelKey(mac = true, windows = false))
        assertEquals("Show in Finder", revealLabelFallback(mac = true, windows = false))
        assertEquals("d_show_in_explorer", revealLabelKey(mac = false, windows = true))
        assertEquals("d_show_in_files", revealLabelKey(mac = false, windows = false))
    }

    @Test
    fun `a playing track reveals the copy that was saved`() {
        val file = Files.createTempFile("bitchord-reveal", ".flac")
        try {
            Files.write(file, byteArrayOf(1))
            val playing = song("abc")
            val saved = playing.copy(localPath = file.toString())
            assertSame(saved, songToReveal(playing, listOf(saved)))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a track with no file on disk is not revealed`() {
        val playing = song("abc")
        val missing = playing.copy(localPath = "/tmp/bitchord-does-not-exist.flac")
        assertNull(songToReveal(playing, listOf(missing)))
    }

    @Test
    fun `the file on the row itself is enough`() {
        val file = Files.createTempFile("bitchord-reveal", ".mp3")
        try {
            Files.write(file, byteArrayOf(1))
            val local = song("").copy(localPath = file.toString())
            assertSame(local, songToReveal(local, emptyList()))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun song(id: String) = Song(videoId = id, title = "Track", artist = "Artist", thumbnailUrl = null)
}
