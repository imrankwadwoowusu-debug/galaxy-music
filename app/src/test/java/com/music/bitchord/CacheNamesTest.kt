package com.music.bitchord

import com.music.bitchord.data.debrid.CacheNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deterministic-filename cache check, pinned to a fixed example:
 * Monkeys Spinning Monkeys by Kevin MacLeod, catalogue track 359051850.
 */
class CacheNamesTest {

    private val catalogId = 359051850L

    private fun tier(label: String) = CacheNames.TIERS.first { it.label == label }

    @Test fun `primary filenames match the seeder convention exactly`() {
        val names = CacheNames.TIERS.map {
            CacheNames.filename("Monkeys Spinning Monkeys", "Kevin MacLeod", catalogId, it)
        }
        assertEquals(
            listOf(
                "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - Hi-Res].flac",
                "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - HiFi CD].flac",
                "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - ALAC].m4a",
                "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - Dolby Atmos].eac3",
                "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - AC-4].ac4",
                "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - 320kbps].m4a",
                "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - 96kbps].m4a",
            ),
            names,
        )
    }

    @Test fun `every known spelling is hashed in the same batch`() {
        val names = CacheNames.candidates("Monkeys Spinning Monkeys", "Kevin MacLeod", catalogId)
            .map { it.name }
        // The immersive rungs are filed under more than one label and box.
        assertTrue(
            "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - E-AC-3 JOC].m4a" in names,
        )
        assertTrue(
            "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - EAC3-JOC].eac3" in names,
        )
        assertTrue(
            "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - AC4].m4a" in names,
        )
        assertTrue(
            "Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - Apple Lossless].m4a" in names,
        )
        assertEquals(names.size, names.distinct().size)
    }

    @Test fun `hashes are lowercase hex md5`() {
        // Fixed vector first, so a broken digest fails loudly instead of
        // merely disagreeing with itself below.
        assertEquals("900150983cd24fb0d6963f7d28e17f72", CacheNames.md5Hex("abc"))
        val md5 = CacheNames.md5Hex("Monkeys Spinning Monkeys - Kevin MacLeod [RedLake 359051850 - Hi-Res].flac")
        assertTrue(md5.matches(Regex("[0-9a-f]{32}")))
    }

    @Test fun `clean strips exactly the hostile characters`() {
        assertEquals("ACDC Live", CacheNames.clean("AC/DC: Live?"))
        assertEquals("ab", CacheNames.clean("  a|b\"  "))
        assertEquals("", CacheNames.clean("///"))
    }

    @Test fun `only name-equal hits count`() {
        val wanted = CacheNames.candidates("Song", "Singer", 1L).associateBy { it.md5 }
        val byName = wanted.values.associate { it.name to it.md5 }
        val hits = mapOf(
            byName["Song - Singer [RedLake 1 - 320kbps].m4a"]!! to
                "Song - Singer [RedLake 1 - 320kbps].m4a",
            // Present by hash but holding another file: not this track.
            byName["Song - Singer [RedLake 1 - Hi-Res].flac"]!! to
                "Something else entirely.flac",
        )
        assertEquals(listOf(tier("320kbps")), CacheNames.matchedTiers(wanted, hits))
    }

    @Test fun `tier formats describe the real container`() {
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

        val ac4 = CacheNames.formatOf(tier("AC-4"))
        assertEquals("ac4", ac4.codec)
        assertEquals(true, ac4.isDolbyAtmos)
        assertEquals(false, ac4.isLossless)

        assertEquals(320, CacheNames.formatOf(tier("320kbps")).kbps)
        assertEquals(96, CacheNames.formatOf(tier("96kbps")).kbps)
    }

    @Test fun `the exact spelling that hit decides the container`() {
        val candidate = CacheNames.candidates("Song", "Singer", 1L)
            .first { it.name.endsWith("[RedLake 1 - E-AC-3 JOC].m4a") }
        assertEquals("m4a", CacheNames.formatOf(candidate).container)
    }

    @Test fun `query overlap separates same words from noise`() {
        assertTrue(CacheNames.queryOverlap("blinding lights weeknd", "Blinding Lights", "The Weeknd") >= 0.5)
        assertEquals(0.0, CacheNames.queryOverlap("xyzzy qqq", "Blinding Lights", "The Weeknd"), 0.0)
    }
}
