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

    @Test fun `track ids round-trip metadata without separators`() {
        val id = DebridSource.encodeTrackId(359051850, "Monkeys Spinning Monkeys: Live?", "Kevin/MacLeod")
        assertTrue(":" !in id)
        val (catalogId, title, artist) = DebridSource.parseTrackId(id)!!
        assertEquals(359051850L, catalogId)
        assertEquals("Monkeys Spinning Monkeys: Live?", title)
        assertEquals("Kevin/MacLeod", artist)
    }

    @Test fun `garbage track ids are refused`() {
        assertNull(DebridSource.parseTrackId("359051850"))
        assertNull(DebridSource.parseTrackId("tb!!!not-base64!!!"))
        assertNull(DebridSource.parseTrackId("tbaGk=")) // valid base64, wrong shape
    }

    @Test fun `lossless asks for flac and alac only`() {
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
            assertNull(source.stream("tbabc", StreamRequest.Best))
        }
    }
}
