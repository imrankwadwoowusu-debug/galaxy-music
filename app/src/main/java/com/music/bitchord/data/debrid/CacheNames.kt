package com.music.bitchord.data.debrid

import com.music.bitchord.data.sources.StreamFormat
import java.util.Locale

/**
 * The RedLake file-name convention, read back into title, artist, tier and
 * container.
 *
 * This is the only thing the debrid source knows about a catalogue: the
 * account's files are named
 * `{Clean Title} - {Clean Artist} [RedLake {Opaque Id} - {Quality}].{ext}`.
 * The id inside the brackets is deliberately *opaque*. Nothing here computes
 * or verifies it, so the library may carry ids from any seeding scheme — or
 * from no catalogue at all — without this source consulting that catalogue.
 * Search is a local filter over the account's own files, not a hash lookup.
 *
 * A rung may be filed under more than one spelling — `Dolby Atmos` and
 * `E-AC-3 JOC`, an `.eac3` or an `.m4a` container. [tierFor] accepts every
 * known spelling, so a seeder renaming a tier does not make the track look
 * absent.
 */
object CacheNames {

    /**
     * The seeder convention's tag. Part of the file name, so a name carrying
     * a different tag is a different protocol and is not parsed.
     */
    internal const val PROTOCOL_TAG = "RedLake"

    /** One quality rung a library may hold a track at. */
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
            // several ways, and the label is part of the name.
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

    /**
     * One parsed library file.
     *
     * [ext] is the container the file actually carries, which can differ from
     * [Tier.ext] for a rung published in more than one box (an Atmos `.m4a`
     * next to its `.eac3`). [StreamFormat] uses it so the player is told the
     * truth about the stream it is being handed.
     */
    data class ParsedFile(
        val title: String,
        val artist: String,
        val tier: Tier,
        val ext: String,
    )

    /**
     * Read one file name, or null when it is not a RedLake file at this
     * source's known tiers.
     *
     * Only the base name is inspected, so a torrent's folder prefix is
     * irrelevant. The opaque id is skipped rather than interpreted:
     * `[RedLake 359051850 - Hi-Res]`, `[RedLake anything - Hi-Res]` and
     * `[RedLake - Hi-Res]` all read the same.
     */
    fun parse(fileName: String): ParsedFile? {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val open = base.lastIndexOf("[$PROTOCOL_TAG")
        if (open <= 0) return null
        val close = base.indexOf(']', open)
        if (close < 0) return null

        val suffix = base.substring(close + 1)
        if (!suffix.startsWith('.')) return null
        val ext = suffix.substring(1).lowercase(Locale.ROOT)
        if (ext.isEmpty() || ext.any { !it.isLetterOrDigit() }) return null

        val bracket = base.substring(open + 1, close)
        val qualitySeparator = bracket.lastIndexOf(" - ")
        if (qualitySeparator < 0) return null
        val label = bracket.substring(qualitySeparator + 2).trim()
        val tier = tierFor(label, ext) ?: return null

        val head = base.substring(0, open).trimEnd()
        val artistSeparator = head.lastIndexOf(" - ")
        if (artistSeparator <= 0) return null
        val title = TRACK_NUMBER.replace(head.substring(0, artistSeparator), "").trim()
        val artist = head.substring(artistSeparator + 2).trim()
        if (title.isEmpty() || artist.isEmpty()) return null

        return ParsedFile(title = title, artist = artist, tier = tier, ext = ext)
    }

    /**
     * The tier a `{label}` in a `.{ext}` container names, or null when this
     * source has no rung for it.
     *
     * The label is matched with punctuation and case ignored, so `Hi-Res`,
     * `HI RES` and `hi-res` are one rung. A container that can only be one
     * codec still identifies the rung when the label is missing or renamed:
     * `.flac` is lossless, `.eac3` is Atmos, `.ac4` is AC-4, and an `.m4a`
     * that calls itself ALAC/Apple Lossless or Atmos is read as such.
     */
    fun tierFor(label: String, ext: String): Tier? {
        val suffix = ext.lowercase(Locale.ROOT).removePrefix(".")
        if (suffix.isEmpty()) return null
        val wanted = normalized(label)
        if (wanted.isNotEmpty()) {
            TIERS.firstOrNull { tier ->
                suffix in tier.exts && tier.labels.any { normalized(it) == wanted }
            }?.let { return it }
        }
        return when (suffix) {
            "flac" -> TIERS.first { it.label == "HiFi CD" }
            "eac3", "ec3" -> TIERS.first { it.label == "Dolby Atmos" }
            "ac4" -> TIERS.first { it.label == "AC-4" }
            "m4a", "mp4" -> when {
                wanted.contains("alac") || wanted.contains("lossless") ->
                    TIERS.first { it.label == "ALAC" }
                wanted.contains("atmos") -> TIERS.first { it.label == "Dolby Atmos" }
                else -> null
            }
            else -> null
        }
    }

    /** The format of [tier] in its primary container. */
    fun formatOf(tier: Tier): StreamFormat = formatOf(tier, tier.ext)

    /** The format of [tier] in the exact container [ext] the library holds. */
    fun formatOf(tier: Tier, ext: String): StreamFormat =
        StreamFormat(codec = tier.codec, kbps = tier.kbps, container = ext)

    /**
     * The identity two library files share when they are the same recording,
     * ignoring case and punctuation.
     *
     * The same track is routinely filed in several torrents — one per quality
     * tier, or a duplicate added later — and each carries the same title and
     * artist. Grouping on this key lets search offer one row per recording
     * rather than one row per file the account happens to hold.
     */
    internal fun filingKey(title: String, artist: String): String =
        normalized(title) + '\u0000' + normalized(artist)

    /**
     * Whether [query]'s words show up in a `title artist` filing at all.
     *
     * A light pre-filter before a library row is offered: the debrid account
     * can hold a lot of music, and a row that shares no words with the
     * question is a different song with the same chorus.
     */
    fun queryOverlap(query: String, title: String, artist: String): Double {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        if (words.isEmpty()) return 0.0
        val haystack = "$title $artist".lowercase(Locale.ROOT)
        val hit = words.count { haystack.contains(it) }
        return hit.toDouble() / words.size
    }

    /** Case and punctuation are not part of a tier's identity. */
    private fun normalized(value: String): String =
        value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** An album's `01.`, `1 -` or `01)` prefix is filing, not part of the title. */
    private val TRACK_NUMBER = Regex("""^\s*\d{1,3}\s*[.)\-]\s+""")
}
