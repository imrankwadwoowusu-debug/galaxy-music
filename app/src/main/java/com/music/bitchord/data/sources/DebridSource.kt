package com.music.bitchord.data.sources

import java.util.Base64
import java.util.Locale
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.debrid.CacheNames
import com.music.bitchord.data.debrid.DebridApi
import com.music.bitchord.data.debrid.DebridProviders
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings

private const val TAG = "BitChord"

/**
 * Bit-exact audio from the listener's debrid account and, when switched on,
 * the provider's global cache.
 *
 * ## How to use (listeners)
 *
 * 1. Open Settings → Sources → the Debrid row, paste the provider's API key,
 *    hit Test (it validates the key live), then Save — saving also switches
 *    the source on.
 * 2. Put RedLake music in the account's torrent library, or leave
 *    "Search global cache" on and pick a cached release search finds. A
 *    global row is added to the account only when it is played.
 * 3. Play anything. The source is asked in rank order alongside the rest; it
 *    answers with the best copy the request can take and playback falls
 *    through to the next source when neither catalogue holds one.
 *
 * ## How lookup works
 *
 * A debrid cache is keyed by torrent infohash, which this app can never know
 * up front. The account is read through `torrents/mylist`, which names every
 * file. The optional global catalogue comes from the provider's search
 * service, and `checkcached?list_files=true` names the files a cached torrent
 * contains. [CacheNames] reads
 * `{Clean Title} - {Clean Artist} [RedLake {Opaque Id} - {Quality}].{ext}`
 * back into title, artist, tier and container, and search filters those names
 * locally.
 *
 * The id inside the brackets is deliberately opaque. It is never computed,
 * resolved or checked against a catalogue, so no music service is involved
 * and no seeding scheme has to be reproduced: any RedLake file is found by
 * the title and artist in its own name.
 *
 * ## How streaming works
 *
 * An account row's track id carries the torrent id, file id and file name. A
 * global row's carries the infohash, the file position and the file name;
 * playing it adds the torrent with `add_only_if_cached` and then looks up the
 * account's own file id. Both are minted at play time because download links
 * expire and a global row has no account address until it is added. Nothing
 * is held in memory between search and playback, so a queue restored after a
 * restart resolves the same way.
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
        val allowed = tierFor(request, atmosWanted())
        if (allowed.isEmpty()) return emptyList()

        val library = DebridApi.library(key)
        val accountFiles = library.orEmpty()
        if (library == null) {
            // The global cache can still answer when the account listing
            // itself is briefly unavailable, so this is a warning rather than
            // the end of search.
            TrackLog.w(TAG, "  · Debrid library unavailable; trying the global cache")
        }

        val matches = mutableListOf<Held>()
        accountFiles.mapNotNullTo(matches) { file ->
            heldFor(file, query, allowed, owned = true)
        }

        if (AppSettings.debridGlobalSearch.value) {
            val global = DebridApi.globalFiles(key, query)
            if (global == null) {
                TrackLog.d(TAG, "  · Debrid global cache unavailable")
            } else {
                global.mapNotNullTo(matches) { file ->
                    heldFor(file, query, allowed, owned = false)
                }
            }
        } else {
            TrackLog.d(TAG, "  · Debrid global cache is switched off")
        }

        if (matches.isEmpty()) {
            TrackLog.d(TAG, "  · Debrid holds no match for \"$query\"")
            return emptyList()
        }

        // One row per recording, at the best tier this request can take. The
        // same track is routinely filed in several torrents, and offering each
        // copy would spend the result limit on one song. An owned copy wins a
        // tie against a global-cache copy at the same tier: it is already in
        // the account, so playing it needs no add and cannot disappear.
        val songs = matches
            .groupBy { CacheNames.filingKey(it.parsed.title, it.parsed.artist) }
            .values
            .mapNotNull { copies ->
                copies.minWithOrNull(
                    compareBy<Held> { allowed.indexOf(it.parsed.tier) }
                        .thenBy { if (it.owned) 0 else 1 },
                )
            }
            .sortedWith(
                compareByDescending<Held> { it.overlap }
                    .thenBy { allowed.indexOf(it.parsed.tier) }
                    .thenBy { if (it.owned) 0 else 1 },
            )
            .take(limit)
            .map(::songOf)

        TrackLog.d(
            TAG,
            "  ✓ Debrid ${songs.size} track(s) from ${accountFiles.size} account file(s)" +
                " and ${matches.count { !it.owned }} global row(s) for \"$query\"",
        )
        return songs
    }

    /** One parsed file that answers [query] at a tier this request allows. */
    private fun heldFor(
        file: DebridApi.LibraryFile,
        query: String,
        allowed: List<CacheNames.Tier>,
        owned: Boolean,
    ): Held? {
        val parsed = CacheNames.parse(file.name) ?: return null
        if (parsed.tier !in allowed) return null
        val overlap = CacheNames.queryOverlap(query, parsed.title, parsed.artist)
        if (overlap < MIN_QUERY_OVERLAP) return null
        return Held(file, parsed, overlap, owned)
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val key = apiKey()
        if (key.isEmpty() || !DebridProviders.isKnown(providerId)) return null
        val allowed = tierFor(request, atmosWanted())
        if (allowed.isEmpty()) return null
        return when {
            trackId.startsWith("rg") -> streamGlobal(key, trackId, allowed)
            else -> streamOwned(key, trackId, allowed)
        }
    }

    private suspend fun streamOwned(
        key: String,
        trackId: String,
        allowed: List<CacheNames.Tier>,
    ): SourceStream? {
        val (torrentId, fileId, fileName) = parseTrackId(trackId) ?: return null
        val parsed = CacheNames.parse(fileName) ?: return null
        if (parsed.tier !in allowed) {
            TrackLog.d(
                TAG,
                "  ✗ Debrid: '${parsed.title}' is ${parsed.tier.label}, not an answer to this request",
            )
            return null
        }
        TrackLog.d(TAG, "▶ Debrid stream torrent=$torrentId file=$fileId ${parsed.tier.label}")
        val url = DebridApi.downloadUrl(key, torrentId, fileId) ?: return null
        TrackLog.d(TAG, "  ✓ Debrid '${parsed.title}' at ${parsed.tier.label}")
        return SourceStream(url = url, format = CacheNames.formatOf(parsed.tier, parsed.ext))
    }

    /**
     * Play a row that was found in the provider's global cache but is not in
     * the account yet.
     *
     * The add is on demand, not at search time: a search that added every
     * result it offered would fill the account with torrents the listener
     * never chose. The row carries the infohash and the file's position;
     * `add_only_if_cached` makes the add a lookup rather than a download, and
     * the account listing afterwards turns the position into the file id
     * `requestdl` needs.
     *
     * Every step here falls back to the account: a duplicate add, a cache
     * check that raced another device, or an add that succeeded but whose
     * response was lost all still resolve if the torrent is already there.
     */
    private suspend fun streamGlobal(
        key: String,
        trackId: String,
        allowed: List<CacheNames.Tier>,
    ): SourceStream? {
        val ref = parseGlobalTrackId(trackId) ?: return null
        val parsed = CacheNames.parse(ref.fileName) ?: return null
        if (parsed.tier !in allowed) {
            TrackLog.d(
                TAG,
                "  ✗ Debrid global: '${parsed.title}' is ${parsed.tier.label}, not an answer to this request",
            )
            return null
        }
        TrackLog.d(
            TAG,
            "▶ Debrid global stream hash=${ref.hash.take(12)} file=${ref.fileIndex} ${parsed.tier.label}",
        )

        var file = pickGlobalFile(DebridApi.library(key).orEmpty(), ref)
        if (file == null) {
            val added = DebridApi.addCachedTorrent(
                key,
                ref.hash,
                name = ref.fileName.substringAfterLast('/').substringAfterLast('\\'),
            )
            DebridApi.invalidateLibrary()
            file = added?.let { torrentId ->
                DebridApi.torrentFiles(key, torrentId)?.let { pickGlobalFile(it, ref) }
            }
            if (file == null) {
                file = pickGlobalFile(DebridApi.library(key).orEmpty(), ref)
            }
        }
        val resolved = file ?: run {
            TrackLog.w(TAG, "  ✗ Debrid global: ${ref.hash.take(12)} is no longer cached")
            return null
        }
        val url = DebridApi.downloadUrl(key, resolved.torrentId, resolved.fileId) ?: return null
        TrackLog.d(TAG, "  ✓ Debrid global '${parsed.title}' at ${parsed.tier.label}")
        return SourceStream(url = url, format = CacheNames.formatOf(parsed.tier, parsed.ext))
    }

    /**
     * Find the file a global row named inside an account listing.
     *
     * Name first: it is what the row was parsed from and survives a torrent
     * whose file ordering another client changed. Position second, for the
     * single-file torrent whose listing only repeats the torrent name. Last,
     * any file at the same tier, which covers a name that was normalised on
     * the way through the provider.
     */
    private fun pickGlobalFile(
        files: List<DebridApi.LibraryFile>,
        ref: GlobalTrackRef,
    ): DebridApi.LibraryFile? {
        val hashed = files.filter { it.hash.equals(ref.hash, ignoreCase = true) }
        val candidates = hashed.ifEmpty {
            // A one-torrent listing need not repeat the hash.
            if (files.size == 1 || files.map { it.torrentId }.distinct().size == 1) files else emptyList()
        }
        if (candidates.isEmpty()) return null
        val tier = CacheNames.parse(ref.fileName)?.tier
        return candidates.firstOrNull { it.name == ref.fileName }
            ?: candidates.firstOrNull { it.fileIndex == ref.fileIndex && it.fileIndex >= 0 }
            ?: candidates.firstOrNull { file ->
                tier != null && CacheNames.parse(file.name)?.tier == tier
            }
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
        val trackId = if (held.owned) {
            encodeTrackId(held.file.torrentId, held.file.fileId, held.file.name)
        } else {
            encodeGlobalTrackId(held.file.hash, held.file.fileIndex, held.file.name)
        }
        return Song(
            videoId = SourceRegistry.trackKey(config.id, trackId),
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
        /** True for a torrent already in the account; false for a global row. */
        val owned: Boolean,
    )

    /** What a global-cache track id carries: infohash, file position, file name. */
    internal data class GlobalTrackRef(
        val hash: String,
        val fileIndex: Int,
        val fileName: String,
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

        /**
         * A global-cache row's address: the infohash, the file's position in
         * the torrent and its name. The prefix is distinct from `rl` so a
         * restored queue from before global search still resolves its owned
         * rows, and an owned row is never mistaken for one that needs adding.
         */
        internal fun encodeGlobalTrackId(hash: String, fileIndex: Int, fileName: String): String {
            val payload = "${hash.lowercase(Locale.ROOT)}\n${fileIndex.coerceAtLeast(0)}\n$fileName"
                .toByteArray(Charsets.UTF_8)
            return "rg" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload)
        }

        internal fun parseGlobalTrackId(rawId: String): GlobalTrackRef? {
            if (!rawId.startsWith("rg")) return null
            val parts = runCatching {
                String(Base64.getUrlDecoder().decode(rawId.removePrefix("rg")), Charsets.UTF_8)
            }.getOrNull()?.split("\n", limit = 3) ?: return null
            if (parts.size != 3) return null
            val hash = parts[0].lowercase(Locale.ROOT).takeIf { it.isNotBlank() } ?: return null
            val index = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            if (parts[2].isBlank()) return null
            return GlobalTrackRef(hash = hash, fileIndex = index, fileName = parts[2])
        }
    }
}
