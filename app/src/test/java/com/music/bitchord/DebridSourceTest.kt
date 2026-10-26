package com.music.bitchord

import com.music.bitchord.data.debrid.CacheNames
import com.music.bitchord.data.sources.DebridSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.StreamRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridSourceTest {

    private val source = DebridSource(SourceConfig(kind = SourceKind.DEBRID))

    private fun tier(label: String) = CacheNames.TIERS.first { it.label == label }

    @Test fun `track ids round-trip the address without separators`() {
        val fileName = "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - Hi-Res].flac"
        val id = DebridSource.encodeTrackId(42, 7, fileName)
        assertTrue(":" !in id)
        val (torrentId, fileId, name) = DebridSource.parseTrackId(id)!!
        assertEquals(42L, torrentId)
        assertEquals(7L, fileId)
        assertEquals(fileName, name)
    }

    @Test fun `single-file torrents round-trip a zero file id`() {
        val id = DebridSource.encodeTrackId(42, 0, "Song - Artist [RedLake 1 - 320kbps].m4a")
        val (_, fileId, _) = DebridSource.parseTrackId(id)!!
        assertEquals(0L, fileId)
    }

    @Test fun `garbage track ids are refused`() {
        assertNull(DebridSource.parseTrackId("359051850"))
        assertNull(DebridSource.parseTrackId("rl!!!not-base64!!!"))
        assertNull(DebridSource.parseTrackId("rl" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("42\n7".toByteArray())))
        assertNull(DebridSource.parseTrackId("rl" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("-42\n7\nSong - Artist [RedLake 1 - Hi-Res].flac".toByteArray())))
        assertNull(DebridSource.parseTrackId("rl" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("42\n-1\nSong - Artist [RedLake 1 - Hi-Res].flac".toByteArray())))
        assertNull(DebridSource.parseTrackId("rl" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("42\n7\n".toByteArray())))
        // The old catalogue-id shape is not a valid address any more.
        assertNull(DebridSource.parseTrackId("tbaGk="))
    }

    @Test fun `lossless asks for flac and alac only`() {
        assertFalse(
            "a RedLake name states no runtime, so the resolver must not length-check it",
            source.statesDuration,
        )
        assertEquals(
            listOf(tier("Hi-Res"), tier("HiFi CD"), tier("ALAC")),
            source.tierFor(StreamRequest.Lossless),
        )
        assertTrue(source.tierFor(StreamRequest.Lossless).all { it.lossless })
    }

    @Test fun `capped asks at or under the ceiling`() {
        assertEquals(
            listOf(tier("320kbps"), tier("96kbps")),
            source.tierFor(StreamRequest.Capped(320)),
        )
        assertEquals(
            listOf(tier("96kbps")),
            source.tierFor(StreamRequest.Capped(100)),
        )
        assertEquals(emptyList<CacheNames.Tier>(), source.tierFor(StreamRequest.Capped(10)))
    }

    @Test fun `best and unscoped take every tier in order`() {
        assertEquals(CacheNames.TIERS, source.tierFor(StreamRequest.Best))
        assertEquals(CacheNames.TIERS, source.tierFor(null))
    }

    @Test fun `atmos is refused when the device or listener does not want it`() {
        val withoutAtmos = source.tierFor(StreamRequest.Best, atmosAllowed = false)
        assertFalse(withoutAtmos.any { it.isDolbyAtmos })
        assertTrue(tier("Dolby Atmos") !in withoutAtmos)
        assertTrue(tier("AC-4") !in withoutAtmos)
        // The stereo rungs are untouched, so a cached 320 still plays.
        assertTrue(tier("320kbps") in withoutAtmos)
    }

    @Test fun `atmos never answers a lossless request`() {
        val lossless = source.tierFor(StreamRequest.Lossless, atmosAllowed = true)
        assertFalse(lossless.any { it.isDolbyAtmos })
        assertFalse(lossless.any { it.label == "Dolby Atmos" || it.label == "AC-4" })
    }

    @Test fun `missing key means missing source`() {
        // No key configured in tests: health refuses, search is empty,
        // stream misses — all without touching the network.
        kotlinx.coroutines.runBlocking {
            assertTrue(source.health() is com.music.bitchord.data.sources.SourceHealth.Rejected)
            assertTrue(source.search("anything", 5, false, null).isEmpty())
            assertNull(source.stream("rlabc", StreamRequest.Best))
        }
    }
}
