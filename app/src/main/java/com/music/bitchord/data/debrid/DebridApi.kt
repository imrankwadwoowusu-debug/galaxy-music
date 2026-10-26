package com.music.bitchord.data.debrid

import com.music.bitchord.data.TrackLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.io.File
import java.util.Locale

/**
 * The debrid account's torrent library, the provider's optional global
 * cache, and the download links for both.
 *
 * A debrid cache is keyed by torrent infohash, which this app can never know
 * up front. What it can read is the library the listener has already put in
 * their account — [library] lists those torrents and the files they hold —
 * and, when global search is on, [globalFiles] asks the provider's search
 * service for cached releases outside it. [CacheNames] reads the RedLake
 * names from either source back into title, artist and tier. Nothing outside
 * the debrid provider is consulted to do that.
 *
 * Auth is a user-supplied API key on every call. The library is held for
 * [LIBRARY_TTL_MS] because a search asks for it once per query, and listing
 * thousands of torrents on every keystroke would cost more than the cache
 * check it replaces. Download links are never held: they expire, so one is
 * minted at play time each time it is needed.
 */
object DebridApi {

    private const val TAG = "BitChord"
    private const val BASE = "https://api.torbox.app"
    private const val SEARCH_BASE = "https://search-api.torbox.app"

    private const val PAGE_SIZE = 1000

    /** A hard stop for absurd libraries; twenty pages is 20,000 torrents. */
    private const val MAX_PAGES = 20

    private const val LIBRARY_TTL_MS = 5 * 60 * 1000L

    /**
     * How long a global search may take before the account's own library is
     * allowed to answer on its own.
     *
     * Global search is an extra catalogue, not a prerequisite: it hits a
     * second host and then `checkcached`, and a slow or unreachable search
     * service must never turn a working account search into a spinner.
     */
    private const val GLOBAL_SEARCH_TIMEOUT_MS = 8_000L

    /** TorBox documents a ~100 hash cap per `checkcached` call. */
    private const val CHECKCACHED_BATCH = 100

    /** Bound on how many torrents one global query may probe for file names. */
    private const val MAX_GLOBAL_TORRENTS = 100

    private val codec = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 6_000
                socketTimeoutMillis = 20_000
            }
            expectSuccess = false
        }
    }

    /** One file in the account, addressed the way `requestdl` needs it. */
    data class LibraryFile(
        val torrentId: Long,
        val fileId: Long,
        /** The torrent's own name for the file: `short_name` when it has one. */
        val name: String,
        /**
         * The torrent's infohash, lowercased. Empty when the listing did not
         * carry one. This is what lets a global-cache row and an account row
         * be recognised as the same torrent.
         */
        val hash: String = "",
        /**
         * The file's zero-based position in the torrent's file list, or -1
         * when the listing did not enumerate files. Global rows carry it so
         * an add-on-demand can find the same file once the torrent lands in
         * the account, where `requestdl` wants the account's own file id.
         */
        val fileIndex: Int = -1,
    )

    /** One torrent the global search service knows about. */
    internal data class GlobalTorrent(
        val hash: String,
        val name: String,
        val magnet: String?,
        val cached: Boolean,
        val owned: Boolean,
    )

    /** One file inside a cached torrent, as `checkcached?list_files=true` names it. */
    internal data class CachedFile(
        val name: String,
        val index: Int,
    )

    internal data class CachedTorrent(
        val hash: String,
        val name: String?,
        val files: List<CachedFile>,
    )

    private data class CachedLibrary(
        val apiKey: String,
        val at: Long,
        val files: List<LibraryFile>,
    )

    private val libraryMutex = Mutex()

    @Volatile
    private var libraryCache: CachedLibrary? = null

    /**
     * Every file in the account's torrent library, or null when the provider
     * could not be asked.
     *
     * A failed fetch keeps the previous answer for this key rather than
     * emptying search: the files were there a moment ago, and a transient
     * outage is not evidence that the listener removed them.
     */
    suspend fun library(apiKey: String): List<LibraryFile>? {
        val key = apiKey.trim()
        if (key.isEmpty()) return null
        return libraryMutex.withLock {
            val now = System.currentTimeMillis()
            val cached = libraryCache
            if (cached != null && cached.apiKey == key && now - cached.at < LIBRARY_TTL_MS) {
                cached.files
            } else {
                val fetched = fetchLibrary(key)
                if (fetched != null) {
                    libraryCache = CachedLibrary(key, now, fetched)
                    fetched
                } else {
                    cached?.takeIf { it.apiKey == key }?.files
                }
            }
        }
    }

    /** Forget the held library, e.g. after the key has been changed. */
    fun invalidateLibrary() {
        libraryCache = null
    }

    private suspend fun fetchLibrary(apiKey: String): List<LibraryFile>? = try {
        val files = mutableListOf<LibraryFile>()
        var offset = 0
        var page = 0
        while (page < MAX_PAGES) {
            val text = client.get("$BASE/v1/api/torrents/mylist") {
                bearerAuth(apiKey)
                parameter("bypass_cache", false)
                parameter("limit", PAGE_SIZE)
                parameter("offset", offset)
            }.bodyAsText()
            val root = codec.parseToJsonElement(text).jsonObject
            if (!root.success()) {
                TrackLog.w(TAG, "Debrid library refused: ${text.take(160)}")
                return null
            }
            val parsed = parseLibraryPage(root)
            files += parsed.files
            if (parsed.torrents < PAGE_SIZE) break
            offset += PAGE_SIZE
            page++
        }
        files
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "Debrid library error: ${e.message}")
        null
    }

    /**
     * Read one page of `torrents/mylist`.
     *
     * The response is a list of torrents, each with the files it holds.
     * Only finished torrents contribute files: a torrent still downloading
     * cannot be served by `requestdl`, so offering its rows would be offering
     * tracks that are guaranteed to fail when played. A single-file torrent
     * whose listing omits `files` still contributes its own name, with file id
     * zero — `requestdl` addresses that file without one.
     */
    internal data class ParsedPage(
        /** How many torrents the page listed, whether finished or not. */
        val torrents: Int,
        val files: List<LibraryFile>,
    )

    internal fun parseLibraryPage(root: JsonObject): ParsedPage {
        val data = when (val element = root["data"]) {
            is JsonArray -> element
            // `mylist?id=` answers with one torrent rather than a page of them.
            is JsonObject -> JsonArray(listOf(element))
            else -> return ParsedPage(0, emptyList())
        }
        val files = mutableListOf<LibraryFile>()
        for (element in data) {
            val torrent = element as? JsonObject ?: continue
            val torrentId = torrent.long("id") ?: continue
            // Missing means "the provider did not say"; only an explicit false
            // excludes the torrent. Accepted in both spellings because the
            // response is documented with snake_case keys and modelled with
            // camelCase ones.
            val finished = (torrent.boolean("download_finished")
                ?: torrent.boolean("downloadFinished")) != false
            if (!finished) continue

            val hash = torrent.string("hash")
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            val listed = torrent["files"] as? JsonArray
            if (listed != null && listed.isNotEmpty()) {
                listed.forEachIndexed { index, fileElement ->
                    val file = fileElement as? JsonObject ?: return@forEachIndexed
                    val fileId = file.long("id") ?: 0L
                    val name = file.string("short_name")
                        ?: file.string("shortName")
                        ?: file.string("name")
                        ?: return@forEachIndexed
                    files += LibraryFile(
                        torrentId = torrentId,
                        fileId = fileId,
                        name = name,
                        hash = hash,
                        fileIndex = index,
                    )
                }
            } else {
                val name = torrent.string("name") ?: continue
                files += LibraryFile(
                    torrentId = torrentId,
                    fileId = 0L,
                    name = name,
                    hash = hash,
                    fileIndex = 0,
                )
            }
        }
        return ParsedPage(torrents = data.size, files = files)
    }

    /**
     * RedLake files the provider's global cache holds for [query], with the
     * infohash and file position a later add-on-demand needs.
     *
     * The search service is a second host with its own auth, and it is asked
     * only when the listener has switched global search on. Null means the
     * question could not be answered — a timeout, DNS failure or refused key —
     * which the caller treats as "the account is the only catalogue", never as
     * an error worth showing.
     *
     * `torrentId` and `fileId` are zero on these rows: the torrent is not in
     * the account yet, so there is no account address to carry. Playback
     * resolves one by hash and then files it.
     */
    suspend fun globalFiles(apiKey: String, query: String): List<LibraryFile>? {
        val key = apiKey.trim()
        if (key.isEmpty() || query.isBlank()) return emptyList()
        return try {
            withTimeoutOrNull(GLOBAL_SEARCH_TIMEOUT_MS) { fetchGlobalFiles(key, query) }
                ?: run {
                    TrackLog.w(TAG, "Debrid global search timed out for \"$query\"")
                    null
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Debrid global search error: ${e.message}")
            null
        }
    }

    private suspend fun fetchGlobalFiles(apiKey: String, query: String): List<LibraryFile>? {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val text = client.get("$SEARCH_BASE/torrents/search/$encoded") {
            bearerAuth(apiKey)
            parameter("check_cache", true)
            parameter("check_owned", true)
            parameter("search_user_engines", true)
        }.bodyAsText()
        val torrents = parseGlobalSearch(text) ?: run {
            TrackLog.w(TAG, "Debrid global search refused: ${text.take(160)}")
            return null
        }
        if (torrents.isEmpty()) return emptyList()

        val hashes = torrents.map { it.hash }.distinct().take(MAX_GLOBAL_TORRENTS)
        // Null here means the cache check itself failed. The search service's
        // own `cached` flag is then all there is to go on, and a torrent it
        // says is cached may still be offered. An empty map is a real answer:
        // none of these hashes are cached.
        val cached = try {
            cachedTorrents(apiKey, hashes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Debrid cache check unavailable: ${e.message}")
            null
        }
        val files = mutableListOf<LibraryFile>()
        for (torrent in torrents) {
            val entry = cached?.get(torrent.hash)
            // A successful cache check is authoritative; only when it failed
            // does the search service's own cached flag get a say.
            if (entry == null && (cached != null || !torrent.cached)) continue
            val named = entry?.files.orEmpty().ifEmpty {
                listOf(CachedFile(torrent.name, 0))
            }
            for (file in named) {
                if (file.name.isBlank()) continue
                files += LibraryFile(
                    torrentId = 0L,
                    fileId = 0L,
                    name = file.name,
                    hash = torrent.hash,
                    fileIndex = file.index,
                )
            }
        }
        return files
    }

    /**
     * Parse the search service's answer.
     *
     * Null is "the service did not answer usefully"; an empty list is a
     * successful search that found nothing. The two are kept apart because a
     * refused key should not silently read as a small cache.
     */
    internal fun parseGlobalSearch(text: String): List<GlobalTorrent>? = try {
        val root = codec.parseToJsonElement(text).jsonObject
        if (!root.success()) {
            null
        } else {
            val data = root["data"]
            val torrents = when (data) {
                is JsonObject -> data["torrents"] as? JsonArray
                is JsonArray -> data
                else -> null
            }
            torrents?.mapNotNull { element ->
                val torrent = element as? JsonObject ?: return@mapNotNull null
                val hash = torrent.string("hash")?.lowercase(Locale.ROOT) ?: return@mapNotNull null
                GlobalTorrent(
                    hash = hash,
                    name = torrent.string("raw_title")
                        ?: torrent.string("title")
                        ?: torrent.string("name")
                        ?: hash,
                    magnet = torrent.string("magnet"),
                    cached = torrent.boolean("cached") == true,
                    owned = torrent.boolean("owned") == true,
                )
            } ?: emptyList()
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Which of [hashes] the provider holds in its cache, with the files each
     * torrent contains when `list_files` answers.
     *
     * Batched because the endpoint documents a ~100 hash cap, and sent as a
     * POST body so a large batch is not a URL-length gamble. Null is a failed
     * call; an empty map is a successful call that found nothing.
     */
    internal suspend fun cachedTorrents(apiKey: String, hashes: List<String>): Map<String, CachedTorrent>? {
        val key = apiKey.trim()
        val wanted = hashes.map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (key.isEmpty() || wanted.isEmpty()) return emptyMap()
        return try {
            val found = LinkedHashMap<String, CachedTorrent>()
            for (batch in wanted.chunked(CHECKCACHED_BATCH)) {
                val body = JsonObject(
                    mapOf("hashes" to JsonArray(batch.map(::JsonPrimitive))),
                ).toString()
                val text = client.post("$BASE/v1/api/torrents/checkcached") {
                    bearerAuth(key)
                    parameter("format", "object")
                    parameter("list_files", true)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.bodyAsText()
                val parsed = parseCachedTorrents(text) ?: run {
                    TrackLog.w(TAG, "Debrid cache check refused: ${text.take(160)}")
                    return null
                }
                found.putAll(parsed)
            }
            found
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Debrid cache check error: ${e.message}")
            null
        }
    }

    internal fun parseCachedTorrents(text: String): Map<String, CachedTorrent>? = try {
        val root = codec.parseToJsonElement(text).jsonObject
        if (!root.success()) {
            null
        } else {
            val data = root["data"] ?: return emptyMap()
            val entries: List<Pair<String?, JsonObject>> = when (data) {
                is JsonArray -> data.mapNotNull { it as? JsonObject }.map { null to it }
                is JsonObject -> {
                    // `format=object` normally gives hash -> torrent. A single
                    // torrent object is also accepted, since one hash is a
                    // perfectly ordinary batch.
                    if (data.string("hash") != null && data["files"] != null) {
                        listOf(null to data)
                    } else {
                        data.entries.mapNotNull { (name, value) ->
                            (value as? JsonObject)?.let { name to it }
                        }
                    }
                }
                else -> emptyList()
            }
            val out = LinkedHashMap<String, CachedTorrent>()
            for ((key, entry) in entries) {
                val hash = entry.string("hash")?.lowercase(Locale.ROOT)
                    ?: key?.lowercase(Locale.ROOT)
                    ?: continue
                out[hash] = CachedTorrent(
                    hash = hash,
                    name = entry.string("name"),
                    files = parseCachedFiles(entry["files"]),
                )
            }
            out
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Read the several shapes `files` takes across TorBox endpoints: an array
     * of objects, an array of names, a map keyed by file position, or a
     * wrapper object with `files` inside it. Only names and positions are
     * kept — a global row cannot use an account file id until the torrent is
     * actually added.
     */
    private fun parseCachedFiles(element: JsonElement?): List<CachedFile> = when (element) {
        is JsonArray -> element.mapIndexedNotNull { index, item ->
            cachedFile(item, index)
        }
        is JsonObject -> {
            val nested = element["files"]
            if (nested != null) {
                parseCachedFiles(nested)
            } else if (element.string("name") != null || element.string("short_name") != null) {
                cachedFile(element, 0)?.let(::listOf).orEmpty()
            } else {
                element.entries.mapIndexedNotNull { position, (name, value) ->
                    val index = name.toIntOrNull() ?: position
                    cachedFile(value, index)
                }
            }
        }
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
            ?.let { listOf(CachedFile(it, 0)) }
            .orEmpty()
        else -> emptyList()
    }

    private fun cachedFile(element: JsonElement, index: Int): CachedFile? {
        if (element is JsonPrimitive) {
            return element.contentOrNull?.takeIf { it.isNotBlank() }
                ?.let { CachedFile(it, index) }
        }
        val file = element as? JsonObject ?: return null
        val name = file.string("short_name")
            ?: file.string("shortName")
            ?: file.string("name")
            ?: file.string("path")
            ?: return null
        return CachedFile(name, index)
    }

    /**
     * Add a cached torrent to the account, and return the torrent id the
     * provider assigned.
     *
     * `add_only_if_cached` is deliberate: this is the path a global search
     * row takes when it is played, and the row was offered precisely because
     * the provider said the file is already cached. Asking the provider to
     * fetch a torrent it does not hold would turn a play into a download.
     *
     * Null covers both "not cached" and "could not ask". The caller still
     * looks the hash up in the account afterwards, because a duplicate add is
     * also a null here and the torrent may already be there.
     */
    suspend fun addCachedTorrent(
        apiKey: String,
        hash: String,
        magnet: String? = null,
        name: String? = null,
    ): Long? = try {
        val key = apiKey.trim()
        val link = magnet?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
            ?: "magnet:?xt=urn:btih:$hash"
        val text = client.post("$BASE/v1/api/torrents/createtorrent") {
            bearerAuth(key)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("magnet", link)
                        append("seed", "0")
                        append("allow_zip", "false")
                        append("add_only_if_cached", "true")
                        name?.takeIf { it.isNotBlank() }?.let { append("name", it) }
                    },
                ),
            )
        }.bodyAsText()
        parseAddedTorrentId(text) ?: run {
            TrackLog.w(TAG, "Debrid could not add cached torrent ${hash.take(12)}: ${text.take(160)}")
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "Debrid add-torrent error: ${e.message}")
        null
    }

    /**
     * Submit torrent metadata for a file that this device is actively seeding.
     * TorBox receives only the small .torrent file here; it pulls audio pieces
     * from the seeder until its account copy is complete.
     */
    suspend fun addSeededTorrent(apiKey: String, torrentFile: File, name: String): Long? = try {
        if (!torrentFile.isFile || torrentFile.length() !in 1..4_194_304) return null
        val text = client.post("$BASE/v1/api/torrents/createtorrent") {
            bearerAuth(apiKey.trim())
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("name", name)
                        append("seed", "1")
                        append("allow_zip", "false")
                        append(
                            "file",
                            torrentFile.readBytes(),
                            Headers.build {
                                append(HttpHeaders.ContentType, "application/x-bittorrent")
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "filename=\"${torrentFile.name}\"",
                                )
                            },
                        )
                    },
                ),
            )
        }.bodyAsText()
        parseAddedTorrentId(text).also { id ->
            if (id == null) TrackLog.w(TAG, "Debrid rejected the seeded torrent: ${text.take(160)}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "Debrid seeded-torrent add error: ${e.message}")
        null
    }

    /** Keep the completed upload in the account cache after the seeder stops. */
    suspend fun airlockTorrent(apiKey: String, torrentId: Long, name: String): Boolean = try {
        val text = client.put("$BASE/v1/api/torrents/edittorrent") {
            bearerAuth(apiKey.trim())
            contentType(ContentType.Application.Json)
            setBody(
                JsonObject(
                    mapOf(
                        "torrent_id" to JsonPrimitive(torrentId),
                        "name" to JsonPrimitive(name),
                        "airlocked" to JsonPrimitive(true),
                    ),
                ).toString(),
            )
        }.bodyAsText()
        codec.parseToJsonElement(text).jsonObject.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "Debrid airlock error: ${e.message}")
        false
    }

    internal fun parseAddedTorrentId(text: String): Long? = try {
        val root = codec.parseToJsonElement(text).jsonObject
        if (!root.success()) {
            null
        } else {
            when (val data = root["data"]) {
                is JsonObject -> data.long("torrent_id")
                    ?: data.long("torrentId")
                    ?: data.long("id")
                is JsonPrimitive -> data.longOrNull
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The files of one account torrent, by id.
     *
     * Used after an add-on-demand: `checkcached` knows the file's position,
     * but `requestdl` wants the account's own file id, which only a listing
     * of the torrent has. Null is a failed or refused lookup.
     */
    suspend fun torrentFiles(apiKey: String, torrentId: Long): List<LibraryFile>? = try {
        val text = client.get("$BASE/v1/api/torrents/mylist") {
            bearerAuth(apiKey.trim())
            parameter("id", torrentId)
            parameter("bypass_cache", true)
            parameter("limit", PAGE_SIZE)
        }.bodyAsText()
        val root = codec.parseToJsonElement(text).jsonObject
        if (!root.success()) {
            TrackLog.w(TAG, "Debrid torrent lookup refused: ${text.take(160)}")
            null
        } else {
            val files = parseLibraryPage(root).files
            files.filter { it.torrentId == torrentId }.ifEmpty { files }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "Debrid torrent lookup error: ${e.message}")
        null
    }

    /**
     * Ask the provider to fetch [link] into the account as a web download.
     *
     * This is the only route a file that is not a torrent has: TorBox offers
     * no upload endpoint, so the bytes must already be at a URL the provider
     * can reach. Returns the web download id, or null when the provider
     * refused or could not be asked.
     */
    suspend fun createWebDownload(
        apiKey: String,
        link: String,
        name: String? = null,
    ): Long? = try {
        val response = client.submitForm(
            url = "$BASE/v1/api/webdl/createwebdownload",
            formParameters = parameters {
                append("link", link)
                name?.takeIf { it.isNotBlank() }?.let { append("name", it) }
                append("add_only_if_cached", "false")
            },
        ) {
            bearerAuth(apiKey.trim())
        }
        val text = response.bodyAsText()
        parseWebDownloadId(text) ?: run {
            TrackLog.w(TAG, "Debrid web download refused: ${text.take(160)}")
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "Debrid web download error: ${e.message}")
        null
    }

    /**
     * Ask for a playable URL for an earlier web download. A null result means
     * it is not ready yet, no longer available, or the provider refused it;
     * callers can fall back to the addon's live stream in all three cases.
     */
    suspend fun webDownloadUrl(apiKey: String, webId: Long): String? = try {
        val text = client.get("$BASE/v1/api/webdl/requestdl") {
            parameter("token", apiKey.trim())
            parameter("web_id", webId)
            parameter("file_id", 0)
            parameter("zip_link", false)
            parameter("redirect", false)
        }.bodyAsText()
        parseDownloadUrl(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "Debrid web-download link error: ${e.message}")
        null
    }

    internal fun parseWebDownloadId(text: String): Long? = try {
        val root = codec.parseToJsonElement(text).jsonObject
        if (!root.success()) {
            null
        } else {
            val data = when (val raw = root["data"]) {
                is JsonArray -> raw.firstOrNull() as? JsonObject
                is JsonObject -> raw
                else -> null
            }
            data?.long("webdownload_id")
                ?: data?.long("web_download_id")
                ?: data?.long("webdl_id")
                ?: data?.long("web_id")
                ?: data?.long("id")
        }
    } catch (e: Exception) {
        null
    }

    /**
     * An openable URL for one file in [torrentId].
     *
     * [fileId] is zero for a single-file torrent, which `requestdl` addresses
     * by [torrentId] alone.
     */
    suspend fun downloadUrl(apiKey: String, torrentId: Long, fileId: Long): String? = try {
        val text = client.get("$BASE/v1/api/torrents/requestdl") {
            parameter("token", apiKey.trim())
            parameter("torrent_id", torrentId)
            if (fileId > 0) parameter("file_id", fileId)
            parameter("zip_link", false)
        }.bodyAsText()
        parseDownloadUrl(text) ?: run {
            TrackLog.w(TAG, "Debrid download-link refused: ${text.take(160)}")
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "Debrid download-link error: ${e.message}")
        null
    }

    internal fun parseDownloadUrl(text: String): String? = try {
        val root = codec.parseToJsonElement(text).jsonObject
        if (!root.success()) {
            null
        } else {
            val data = root["data"]
            (data as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("http") }
                ?: (data as? JsonObject)?.string("url")?.takeIf { it.startsWith("http") }
        }
    } catch (e: Exception) {
        null
    }

    /** Whether [apiKey] is accepted, for the editor's Test button. */
    suspend fun validateKey(apiKey: String): Boolean = try {
        val text = client.get("$BASE/v1/api/user/me") {
            bearerAuth(apiKey.trim())
        }.bodyAsText()
        parseSuccess(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    internal fun parseSuccess(text: String): Boolean = try {
        codec.parseToJsonElement(text).jsonObject.success()
    } catch (e: Exception) {
        false
    }

    /** Display name for a valid key, for the editor's Test button. Null when the key is rejected. */
    suspend fun accountName(apiKey: String): String? = try {
        val text = client.get("$BASE/v1/api/user/me") {
            bearerAuth(apiKey.trim())
        }.bodyAsText()
        parseAccountName(text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    internal fun parseAccountName(text: String): String? = try {
        val root = codec.parseToJsonElement(text).jsonObject
        if (!root.success()) {
            null
        } else {
            val data = root["data"] as? JsonObject
            data?.string("email")
                ?: data?.string("username")
                ?: data?.string("plan")
                ?: data?.long("plan")?.toString()
        }
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.success(): Boolean {
        val primitive = this["success"]?.jsonPrimitive ?: return false
        return primitive.booleanOrNull ?: (primitive.contentOrNull == "true")
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(name: String): Long? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
    }

    private fun JsonObject.boolean(name: String): Boolean? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return primitive.booleanOrNull ?: primitive.contentOrNull?.toBooleanStrictOrNull()
    }
}
