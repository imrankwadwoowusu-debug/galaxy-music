package com.music.bitchord

import com.music.bitchord.data.debrid.CacheNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the RedLake file-name convention back into title, artist and tier.
 * The id inside the bracket is opaque throughout: these names could carry any
 * id, or none, and must parse the same.
 */
class CacheNamesTest {

    private fun tier(label: String) = CacheNames.TIERS.first { it.label == label }

    @Test fun `the protocol tag is the one the seeders use`() {
        assertEquals("RedLake", CacheNames.PROTOCOL_TAG)
    }

    @Test fun `a primary name reads back into title artist tier and container`() {
        val parsed = CacheNames.parse(
            "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - Hi-Res].flac",
        )!!
        assertEquals("Monkeys Spinning Monkeys", parsed.title)
        assertEquals("Kevin MacLeod", parsed.artist)
        assertEquals(tier("Hi-Res"), parsed.tier)
        assertEquals("flac", parsed.ext)
    }

    @Test fun `the id inside the bracket is never interpreted`() {
        val names = listOf(
            "Song - Artist [RedLake 359051850 - Hi-Res].flac",
            "Song - Artist [RedLake opaque text - Hi-Res].flac",
            "Song - Artist [RedLake - Hi-Res].flac",
        )
        names.forEach { name ->
            val parsed = CacheNames.parse(name)!!
            assertEquals("Song", parsed.title)
            assertEquals("Artist", parsed.artist)
            assertEquals(tier("Hi-Res"), parsed.tier)
        }
    }

    @Test fun `a different protocol tag is not a RedLake file`() {
        assertNull(CacheNames.parse("Song - Artist [NullBeat 359051850 - Hi-Res].flac"))
        assertNull(CacheNames.parse("Song - Artist [redlake 359051850 - Hi-Res].flac"))
        assertNull(CacheNames.parse("Song - Artist [RedLake2 359051850 - Hi-Res].flac"))
    }

    @Test fun `a torrent folder prefix and an album track number are filing, not the title`() {
        val parsed = CacheNames.parse(
            "RedLake Album/01. Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 1 - HiFi CD].flac",
        )!!
        assertEquals("Monkeys Spinning Monkeys", parsed.title)
        assertEquals("Kevin MacLeod", parsed.artist)
        assertEquals(tier("HiFi CD"), parsed.tier)
    }

    @Test fun `every known spelling and container reads back to one tier`() {
        val cases = listOf(
            "Song - Artist [RedLake 1 - HiFi CD].flac" to tier("HiFi CD"),
            "Song - Artist [RedLake 1 - Apple Lossless].m4a" to tier("ALAC"),
            "Song - Artist [RedLake 1 - Dolby Atmos].eac3" to tier("Dolby Atmos"),
            "Song - Artist [RedLake 1 - E-AC-3 JOC].m4a" to tier("Dolby Atmos"),
            "Song - Artist [RedLake 1 - EAC3-JOC].eac3" to tier("Dolby Atmos"),
            "Song - Artist [RedLake 1 - Atmos].m4a" to tier("Dolby Atmos"),
            "Song - Artist [RedLake 1 - AC-4].ac4" to tier("AC-4"),
            "Song - Artist [RedLake 1 - AC4].m4a" to tier("AC-4"),
            "Song - Artist [RedLake 1 - 320kbps].m4a" to tier("320kbps"),
            "Song - Artist [RedLake 1 - 96kbps].m4a" to tier("96kbps"),
        )
        cases.forEach { (name, expected) ->
            assertEquals(name, expected, CacheNames.parse(name)!!.tier)
        }
    }

    @Test fun `the container decides when the label is renamed`() {
        assertEquals(tier("HiFi CD"), CacheNames.tierFor("Unlabelled", "flac"))
        assertEquals(tier("Dolby Atmos"), CacheNames.tierFor("Unlabelled", "eac3"))
        assertEquals(tier("AC-4"), CacheNames.tierFor("Unlabelled", "ac4"))
        assertEquals(tier("ALAC"), CacheNames.tierFor("Unlabelled lossless", "m4a"))
        assertEquals(tier("Dolby Atmos"), CacheNames.tierFor("Unlabelled Atmos", "m4a"))
        assertNull(CacheNames.tierFor("Unlabelled", "m4a"))
        assertNull(CacheNames.tierFor("Unlabelled", "ogg"))
    }

    @Test fun `labels are matched with case and punctuation ignored`() {
        assertEquals(tier("Hi-Res"), CacheNames.tierFor("HI RES", "flac"))
        assertEquals(tier("AC-4"), CacheNames.tierFor("ac 4", "ac4"))
    }

    @Test fun `malformed names are refused`() {
        assertNull(CacheNames.parse("Song.flac"))
        assertNull(CacheNames.parse("Song - Artist.flac"))
        assertNull(CacheNames.parse("Song - Artist [RedLake 1 - Hi-Res].aac"))
        assertNull(CacheNames.parse("Song - Artist [RedLake 1].flac"))
        assertNull(CacheNames.parse("Song - Artist [RedLake 1 - Hi-Res]"))
        assertNull(CacheNames.parse("Song - Artist [RedLake 1 - Hi-Res].flac.bak"))
        assertNull(CacheNames.parse(" - Artist [RedLake 1 - Hi-Res].flac"))
        assertNull(CacheNames.parse("Song -  [RedLake 1 - Hi-Res].flac"))
    }

    @Test fun `formats describe the real container`() {
        val hiRes = CacheNames.formatOf(tier("Hi-Res"))
        assertEquals("flac", hiRes.codec)
        assertEquals(null, hiRes.kbps)
        assertEquals("flac", hiRes.container)

        val alac = CacheNames.formatOf(tier("ALAC"))
        assertEquals("alac", alac.codec)
        assertEquals(true, alac.isLossless)
        assertEquals("m4a", alac.container)

        val atmos = CacheNames.formatOf(tier("Dolby Atmos"))
        assertEquals("eac3-joc", atmos.codec)
        assertEquals(true, atmos.isDolbyAtmos)
        assertEquals(false, atmos.isLossless)
        assertEquals("eac3", atmos.container)

        val atmosInM4a = CacheNames.formatOf(tier("Dolby Atmos"), "m4a")
        assertEquals("eac3-joc", atmosInM4a.codec)
        assertEquals("m4a", atmosInM4a.container)

        val ac4 = CacheNames.formatOf(tier("AC-4"))
        assertEquals("ac4", ac4.codec)
        assertEquals(true, ac4.isDolbyAtmos)
        assertEquals(false, ac4.isLossless)

        assertEquals(320, CacheNames.formatOf(tier("320kbps")).kbps)
        assertEquals(96, CacheNames.formatOf(tier("96kbps")).kbps)
    }

    @Test fun `filing key groups the same recording across punctuation and case`() {
        assertEquals(
            CacheNames.filingKey("Monkeys Spinning Monkeys!", "Kevin MacLeod"),
            CacheNames.filingKey("monkeys spinning monkeys", "kevin macleod"),
        )
        assertFalse(
            CacheNames.filingKey("Song", "Artist") == CacheNames.filingKey("Song", "Other"),
        )
    }

    @Test fun `query overlap separates same words from noise`() {
        assertTrue(CacheNames.queryOverlap("blinding lights weeknd", "Blinding Lights", "The Weeknd") >= 0.5)
        assertEquals(0.0, CacheNames.queryOverlap("xyzzy qqq", "Blinding Lights", "The Weeknd"), 0.0)
    }
}
