package com.music.bitchord.data.debrid

import com.music.bitchord.data.sources.StreamFormat
import java.security.MessageDigest

/**
 * The deterministic-filename cache check: which file names to hash, and how
 * to read the answer back.
 *
 * A debrid cache is keyed by torrent infohash, which this app can never know
 * up front — nobody publishes the infohash of the file holding one track.
 * What it *can* know is the exact file name a seeder used, when that name is
 * built by convention: `{Clean Title} - {Clean Artist} [Tag {Catalogue Id} -
 * {Quality Label}].{ext}`. The MD5 of that name is what the cache is asked
 * about, and a hit whose reported name equals the asked name is the track.
 *
 * The [PROTOCOL_TAG] inside [filename] is a byte-exact protocol constant,
 * not branding: seeders name their files with it, so any other tag hashes
 * differently and never hits. Everything else here is pure string and hash
 * work, so it stays unit-testable on the JVM with no Android or network in
 * the loop.
 *
 * A rung may be filed under more than one spelling — `Dolby Atmos` and
 * `E-AC-3 JOC`, an `.eac3` or an `.m4a` container. [candidates] hashes every
 * spelling in the same batched cache check; a seeder renaming a tier should
 * not make the track look uncached.
 */
object CacheNames {

    /**
     * The seeder convention's tag. Part of the hashed name, so changing it
     * changes every lookup — it must match the files the cache actually holds.
     */
    internal const val PROTOCOL_TAG = "RedLake"

    /** One quality rung a cache may hold a track at. */
    data class Tier(
        /** As it appears in the file name, e.g. `Hi-Res`. */
        val label: String,
        /** File extension, e.g. `flac`. */
        val ext: String,
        /** Bitrate the rung really delivers, null when the container says lossless. */
        val kbps: Int?,
        /** Container/codec for [StreamFormat]. */
        val codec: String,
        /**
         * Whether the rung is bit-exact. Deliberately separate from
         * `kbps == null`: an immersive E-AC-3 or AC-4 file states no bitrate
         * either, and must not be offered as lossless.
         */
        val lossless: Boolean = kbps == null,
        /** Other names the same rung is published under, primary first. */
        val aliases: List<String> = emptyList(),
        /** Other containers the same rung is published in, primary first. */
        val otherExts: List<String> = emptyList(),
    ) {
        /** Every label this rung is published under. */
        val labels: List<String> get() = listOf(label) + aliases

        /** Every extension this rung is published with. */
        val exts: List<String> get() = listOf(ext) + otherExts

        /** True for an immersive Dolby mix, which is lossy and needs a decoder. */
        val isDolbyAtmos: Boolean
            get() = codec == "eac3-joc" || codec == "ec3-joc" || codec == "ac4" || codec == "ac-4"
    }

    /**
     * Highest fidelity first — the order a stream is picked in.
     *
     * Lossless copies lead, then the immersive Dolby mixes, then the lossy
     * stereo rungs. The immersive files state no bitrate, so they are only
     * reachable on `Best`; a capped connection asks for a bounded stereo rung
     * instead, and `Lossless` never sees them at all.
     */
    val TIERS = listOf(
        Tier("Hi-Res", "flac", kbps = null, codec = "flac"),
        Tier("HiFi CD", "flac", kbps = null, codec = "flac"),
        Tier(
            label = "ALAC",
            ext = "m4a",
            kbps = null,
            codec = "alac",
            aliases = listOf("Apple Lossless"),
        ),
        Tier(
            label = "Dolby Atmos",
            ext = "eac3",
            kbps = null,
            codec = "eac3-joc",
            lossless = false,
            // E-AC-3 JOC is what carries Atmos here. Seeders shorten it in
            // several ways, and the label is part of the hash.
            aliases = listOf("E-AC-3 JOC", "EAC3-JOC", "E-AC3 JOC", "EAC3 JOC", "Atmos"),
            otherExts = listOf("m4a"),
        ),
        Tier(
            label = "AC-4",
            ext = "ac4",
            kbps = null,
            codec = "ac4",
            lossless = false,
            aliases = listOf("AC4", "Dolby Atmos AC-4"),
            otherExts = listOf("m4a"),
        ),
        Tier("320kbps", "m4a", kbps = 320, codec = "mp4"),
        Tier("96kbps", "m4a", kbps = 96, codec = "mp4"),
    )

    /** One asked file: its tier, its exact name, and the hash the cache is asked about. */
    data class Candidate(val tier: Tier, val name: String, val md5: String)

    /**
     * Filesystem-hostile characters, stripped from titles and artists before
     * a name is built. Matches the seeder convention exactly — one missed
     * character is a hash that can never hit.
     */
    fun clean(value: String): String =
        value.replace(Regex("[/\\\\?%*:|\"<>]"), "").trim()

    /** The exact file name a seeder uses for [catalogId] at [tier]. */
    fun filename(title: String, artist: String, catalogId: Long, tier: Tier): String =
        filename(title, artist, catalogId, tier.label, tier.ext)

    /** One spelling of a tier's file name. */
    private fun filename(
        title: String,
        artist: String,
        catalogId: Long,
        label: String,
        ext: String,
    ): String = "${clean(title)} - ${clean(artist)} [$PROTOCOL_TAG $catalogId - $label].$ext"

    fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Every tier's candidate for one catalogue track, in every known spelling. */
    fun candidates(title: String, artist: String, catalogId: Long): List<Candidate> =
        TIERS.flatMap { tier ->
            tier.labels.flatMap { label ->
                tier.exts.map { ext ->
                    val name = filename(title, artist, catalogId, label, ext)
                    Candidate(tier, name, md5Hex(name))
                }
            }
        }.distinctBy { it.md5 }

    /**
     * Which tiers came back cached, best first.
     *
     * @param wanted the asked candidates, by hash.
     * @param hits the cache answer: asked hash to the file name it holds.
     *
     * A hit only counts when the held name *equals* the asked name. The cache
     * keys its answer by infohash, so a bare presence only says *a* file with
     * that hash exists; the name is what says it is this track at this tier.
     */
    fun matchedTiers(
        wanted: Map<String, Candidate>,
        hits: Map<String, String>,
    ): List<Tier> =
        wanted
            .filter { (md5, candidate) -> hits[md5] == candidate.name }
            .values
            .map { it.tier }
            .distinct()
            .sortedBy { TIERS.indexOf(it) }

    fun formatOf(tier: Tier): StreamFormat =
        StreamFormat(codec = tier.codec, kbps = tier.kbps, container = tier.ext)

    /** The format of the exact spelling that hit, so its container is right. */
    fun formatOf(candidate: Candidate): StreamFormat =
        formatOf(candidate.tier).copy(
            container = candidate.name.substringAfterLast('.', candidate.tier.ext),
        )

    /**
     * Whether [query]'s words show up in a `title artist` filing at all.
     * A light pre-filter before spending a cache check: catalogue answers
     * are relevance-ordered, and anything past the first rows that shares no
     * words with the question is a different song with the same chorus.
     */
    fun queryOverlap(query: String, title: String, artist: String): Double {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        if (words.isEmpty()) return 0.0
        val haystack = "$title $artist".lowercase()
        val hit = words.count { haystack.contains(it) }
        return hit.toDouble() / words.size
    }
}
