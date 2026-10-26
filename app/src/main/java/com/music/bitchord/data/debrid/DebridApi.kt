package com.music.bitchord.data.debrid

import com.music.bitchord.data.TrackLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The debrid account's own torrent library, and the download links for files
 * inside it.
 *
 * A debrid cache is keyed by torrent infohash, which this app can never know
 * up front. What it can read is the library the listener has already put in
 * their account — [library] lists those torrents and the files they hold, and
 * [CacheNames] reads the RedLake names back into title, artist and tier.
 * Nothing outside the debrid provider is consulted to do that.
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

    private const val PAGE_SIZE = 1000

    /** A hard stop for absurd libraries; twenty pages is 20,000 torrents. */
    private const val MAX_PAGES = 20

    private const val LIBRARY_TTL_MS = 5 * 60 * 1000L

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
        val data = root["data"] as? JsonArray ?: return ParsedPage(0, emptyList())
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

            val listed = torrent["files"] as? JsonArray
            if (listed != null && listed.isNotEmpty()) {
                for (fileElement in listed) {
                    val file = fileElement as? JsonObject ?: continue
                    val fileId = file.long("id") ?: 0L
                    val name = file.string("short_name")
                        ?: file.string("shortName")
                        ?: file.string("name")
                        ?: continue
                    files += LibraryFile(torrentId = torrentId, fileId = fileId, name = name)
                }
            } else {
                val name = torrent.string("name") ?: continue
                files += LibraryFile(torrentId = torrentId, fileId = 0L, name = name)
            }
        }
        return ParsedPage(torrents = data.size, files = files)
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
