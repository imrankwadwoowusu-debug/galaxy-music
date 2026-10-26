package com.music.bitchord.data.sources

import java.util.Base64
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.debrid.CacheNames
import com.music.bitchord.data.debrid.DebridApi
import com.music.bitchord.data.debrid.DebridProviders
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings

private const val TAG = "BitChord"

/**
 * Bit-exact audio from the listener's own debrid library.
 *
 * ## How to use (listeners)
 *
 * 1. Put the RedLake music you want into your debrid account's torrent
 *    library. Nothing is searched on a public index; the account is the
 *    library.
 * 2. Open Settings → Sources → the Debrid row, paste the provider's API key,
 *    hit Test (it validates the key live), then Save — saving also switches
 *    the source on.
 * 3. Play anything. The source is asked in rank order alongside the rest; it
 *    answers with the best copy the account holds at the requested tier and
 *    playback silently falls through to the next source when it holds none.
 *
 * ## How lookup works
 *
 * A debrid cache is keyed by torrent infohash, which this app can never know
 * up front. What it can read is the account's own library: `torrents/mylist`
 * names every file, and [CacheNames] reads
 * `{Clean Title} - {Clean Artist} [RedLake {Opaque Id} - {Quality}].{ext}`
 * back into title, artist, tier and container. Search is a local filter over
 * that list.
 *
 * The id inside the brackets is deliberately opaque. It is never computed,
 * resolved or checked against a catalogue, so no music service is involved
 * and no seeding scheme has to be reproduced: any RedLake file in the account
 * is found by the title and artist in its own name.
 *
 * ## How streaming works
 *
 * A row's track id carries the torrent id, file id and file name. Playback
 * decodes it, checks the file's tier against the request, and asks the
 * provider for exactly that file. The download link is minted at play time
 * because those links expire; nothing is held in memory between search and
 * playback, so a queue restored after a restart resolves the same way.
 */
class DebridSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.DEBRID

    /**
     * RedLake file names carry no runtime, so the resolver must not apply its
     * strict length check to these rows — see [MusicSource.statesDuration].
     */
    override val statesDuration: Boolean get() = false
    override val displayName: String
        get() = config.label.ifBlank {
            val provider = DebridProviders.labelFor(providerId)
            if (providerId.isBlank() || provider == "Debrid") SourceKind.DEBRID.label
            else "${SourceKind.DEBRID.label} · $provider"
        }

    private val providerId: String
        get() = config.provider.ifBlank { DebridProviders.DEFAULT }

    private fun apiKey(): String = AppSettings.debridApiKey.value.trim()

    override suspend fun health(): SourceHealth {
        if (!DebridProviders.isKnown(providerId)) {
            return SourceHealth.Rejected("Unknown debrid provider ($providerId)")
        }
        if (apiKey().isEmpty()) {
            return SourceHealth.Rejected("API key required")
        }
        return runCatching { DebridApi.validateKey(apiKey()) }
            .fold(
                onSuccess = { valid ->
                    if (valid) SourceHealth.Ok(DebridProviders.labelFor(providerId))
                    else SourceHealth.Rejected("The API key was rejected")
                },
                onFailure = { SourceHealth.Unreachable(it.message ?: "Unreachable") },
            )
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val key = apiKey()
        if (key.isEmpty() || !DebridProviders.isKnown(providerId)) return emptyList()
        if (limit <= 0 || query.isBlank()) return emptyList()
        TrackLog.d(TAG, "▶ Debrid search \"$query\" limit=$limit")
        val library = DebridApi.library(key) ?: run {
            TrackLog.w(TAG, "  ✗ Debrid library unavailable")
            return emptyList()
        }
        val allowed = tierFor(request, atmosWanted())
        if (allowed.isEmpty()) return emptyList()

        val matches = library.mapNotNull { file ->
            val parsed = CacheNames.parse(file.name) ?: return@mapNotNull null
            if (parsed.tier !in allowed) return@mapNotNull null
            val overlap = CacheNames.queryOverlap(query, parsed.title, parsed.artist)
            if (overlap < MIN_QUERY_OVERLAP) return@mapNotNull null
            Held(file, parsed, overlap)
        }
        if (matches.isEmpty()) {
            TrackLog.d(TAG, "  · Debrid library holds no match for \"$query\"")
            return emptyList()
        }

        // One row per recording, at the best tier this request can take. The
        // same track is routinely filed in several torrents, and offering each
        // copy would spend the result limit on one song.
        val songs = matches
            .groupBy { CacheNames.filingKey(it.parsed.title, it.parsed.artist) }
            .values
            .mapNotNull { copies -> copies.minByOrNull { allowed.indexOf(it.parsed.tier) } }
            .sortedWith(
                compareByDescending<Held> { it.overlap }
                    .thenBy { allowed.indexOf(it.parsed.tier) },
            )
            .take(limit)
            .map(::songOf)

        TrackLog.d(
            TAG,
            "  ✓ Debrid ${songs.size} track(s) from ${library.size} file(s) for \"$query\"",
        )
        return songs
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val key = apiKey()
        if (key.isEmpty() || !DebridProviders.isKnown(providerId)) return null
        val (torrentId, fileId, fileName) = parseTrackId(trackId) ?: return null
        val parsed = CacheNames.parse(fileName) ?: return null
        val allowed = tierFor(request, atmosWanted())
        if (parsed.tier !in allowed) {
            TrackLog.d(
                TAG,
                "  ✗ Debrid: '${parsed.title}' is ${parsed.tier.label}, not an answer to $request",
            )
            return null
        }
        TrackLog.d(TAG, "▶ Debrid stream torrent=$torrentId file=$fileId ${parsed.tier.label}")
        val url = DebridApi.downloadUrl(key, torrentId, fileId) ?: return null
        TrackLog.d(TAG, "  ✓ Debrid '${parsed.title}' at ${parsed.tier.label}")
        return SourceStream(url = url, format = CacheNames.formatOf(parsed.tier, parsed.ext))
    }

    /**
     * Which tiers answer [request], best first. Lossless asks for bit-exact
     * copies only — FLAC and ALAC; a capped connection excludes the unbounded
     * lossless and immersive rungs; anything else takes the best the library
     * holds.
     *
     * [atmosAllowed] folds the device's decoder probe and the listener's
     * setting into one answer: an immersive mix the phone cannot decode, or
     * that the listener switched off, is not offered even when it is the only
     * copy. The source then falls through to the next rung, or misses and lets
     * another source play the track.
     */
    internal fun tierFor(
        request: StreamRequest?,
        atmosAllowed: Boolean = true,
    ): List<CacheNames.Tier> {
        val playable = CacheNames.TIERS.filter { atmosAllowed || !it.isDolbyAtmos }
        return when (request) {
            is StreamRequest.Lossless -> playable.filter { it.lossless }
            is StreamRequest.Capped -> playable.filter {
                !it.lossless && it.kbps != null && it.kbps <= request.maxKbps
            }
            else -> playable
        }
    }

    /** The same two gates [ModuleSource.unplayable] and the addon path use. */
    private fun atmosWanted(): Boolean =
        DeviceCodecs.playsDolbyAtmos && AppSettings.dolbyAtmos.value

    private fun songOf(held: Held): Song {
        val parsed = held.parsed
        return Song(
            videoId = SourceRegistry.trackKey(
                config.id,
                encodeTrackId(held.file.torrentId, held.file.fileId, held.file.name),
            ),
            title = parsed.title,
            artist = parsed.artist,
            thumbnailUrl = null,
            sourceQuality = when {
                parsed.tier.isDolbyAtmos -> ModuleSource.DOLBY
                parsed.tier.lossless -> ModuleSource.LOSSLESS
                else -> ModuleSource.HIGH
            },
            sourceHiRes = parsed.tier.label == "Hi-Res",
        )
    }

    /** One parsed library file, with how well its name answers the question. */
    private data class Held(
        val file: DebridApi.LibraryFile,
        val parsed: CacheNames.ParsedFile,
        val overlap: Double,
    )

    internal companion object {
        /** Minimum query-word overlap before a library row is worth offering. */
        const val MIN_QUERY_OVERLAP = 0.5

        /**
         * Everything [stream] needs, in the id itself: torrent id, file id and
         * the file's own name, newline-separated and base64url-encoded (no `:`
         * so it can never collide with [SourceRegistry]'s `::` track-key
         * separator).
         */
        internal fun encodeTrackId(torrentId: Long, fileId: Long, fileName: String): String {
            val payload = "$torrentId\n$fileId\n$fileName".toByteArray(Charsets.UTF_8)
            return "rl" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload)
        }

        internal fun parseTrackId(rawId: String): Triple<Long, Long, String>? {
            if (!rawId.startsWith("rl")) return null
            val parts = runCatching {
                String(Base64.getUrlDecoder().decode(rawId.removePrefix("rl")), Charsets.UTF_8)
            }.getOrNull()?.split("\n", limit = 3) ?: return null
            if (parts.size != 3) return null
            val torrentId = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val fileId = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            if (parts[2].isBlank()) return null
            return Triple(torrentId, fileId, parts[2])
        }
    }
}
