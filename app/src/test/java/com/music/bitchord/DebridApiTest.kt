package com.music.bitchord

import com.music.bitchord.data.debrid.DebridApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TorBox response shapes the debrid library is built from. */
class DebridApiTest {

    private fun page(json: String) =
        DebridApi.parseLibraryPage(Json.parseToJsonElement(json).jsonObject)

    @Test fun `mylist reads files and skips torrents that are still downloading`() {
        val parsed = page(
            """
            {
              "success": true,
              "data": [
                {
                  "id": 11,
                  "name": "RedLake Album",
                  "download_finished": true,
                  "files": [
                    {
                      "id": 101,
                      "name": "Album/01. Song - Artist [RedLake 9 - Hi-Res].flac",
                      "short_name": "01. Song - Artist [RedLake 9 - Hi-Res].flac",
                      "size": 1000
                    },
                    {
                      "id": 102,
                      "name": "Album/cover.jpg",
                      "short_name": "cover.jpg",
                      "size": 10
                    }
                  ]
                },
                {
                  "id": 12,
                  "name": "Single - Artist [RedLake 10 - 320kbps].m4a",
                  "download_finished": true
                },
                {
                  "id": 13,
                  "name": "Still downloading",
                  "download_finished": false,
                  "files": [
                    {
                      "id": 103,
                      "name": "Song - Artist [RedLake 11 - Hi-Res].flac"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        // The unfinished torrent is counted for pagination but contributes
        // no playable files.
        assertEquals(3, parsed.torrents)
        assertEquals(3, parsed.files.size)
        assertEquals(
            DebridApi.LibraryFile(
                11, 101, "01. Song - Artist [RedLake 9 - Hi-Res].flac", fileIndex = 0,
            ),
            parsed.files[0],
        )
        assertEquals(
            DebridApi.LibraryFile(11, 102, "cover.jpg", fileIndex = 1),
            parsed.files[1],
        )
        // A single-file torrent whose listing omits files is addressed by
        // its own name, with no file id.
        assertEquals(
            DebridApi.LibraryFile(
                12, 0, "Single - Artist [RedLake 10 - 320kbps].m4a", fileIndex = 0,
            ),
            parsed.files[2],
        )
    }

    @Test fun `mylist carries the torrent hash and file position`() {
        val parsed = page(
            """
            {
              "success": true,
              "data": [
                {
                  "id": 21,
                  "hash": "AABBCC",
                  "download_finished": true,
                  "files": [
                    { "id": 7, "name": "Song - Artist [RedLake 1 - Hi-Res].flac" },
                    { "id": 8, "name": "cover.jpg" }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals("aabbcc", parsed.files[0].hash)
        assertEquals(0, parsed.files[0].fileIndex)
        assertEquals(1, parsed.files[1].fileIndex)
    }

    @Test fun `a single-torrent mylist answer parses as one torrent`() {
        val parsed = page(
            """
            {"success":true,"data":{"id":31,"hash":"DDEE","download_finished":true,
             "files":[{"id":9,"short_name":"Song - Artist [RedLake 2 - 320kbps].m4a"}]}}
            """.trimIndent(),
        )
        assertEquals(1, parsed.torrents)
        assertEquals(31L, parsed.files.single().torrentId)
        assertEquals(9L, parsed.files.single().fileId)
    }

    @Test fun `global search reads cached and owned torrents`() {
        val torrents = DebridApi.parseGlobalSearch(
            """
            {
              "success": true,
              "data": {
                "metadata": null,
                "torrents": [
                  {
                    "hash": "AABB",
                    "raw_title": "Album/01. Song - Artist [RedLake 9 - Hi-Res].flac",
                    "magnet": "magnet:?xt=urn:btih:aabb",
                    "cached": true,
                    "owned": false
                  },
                  {
                    "hash": "CCDD",
                    "title": "Not cached",
                    "cached": false,
                    "owned": true
                  }
                ]
              }
            }
            """.trimIndent(),
        )!!
        assertEquals(2, torrents.size)
        assertEquals("aabb", torrents[0].hash)
        assertEquals("magnet:?xt=urn:btih:aabb", torrents[0].magnet)
        assertTrue(torrents[0].cached)
        assertFalse(torrents[0].owned)
        assertFalse(torrents[1].cached)
        assertTrue(torrents[1].owned)
    }

    @Test fun `global search refuses a failed answer rather than reading it as empty`() {
        assertNull(DebridApi.parseGlobalSearch("""{"success":false,"detail":"Invalid token"}"""))
        assertEquals(emptyList<DebridApi.GlobalTorrent>(), DebridApi.parseGlobalSearch("""{"success":true,"data":{"torrents":[]}}"""))
    }

    @Test fun `checkcached reads object and array files with their positions`() {
        val objectPage = DebridApi.parseCachedTorrents(
            """
            {
              "success": true,
              "data": {
                "aabb": {
                  "hash": "AABB",
                  "name": "Album",
                  "files": [
                    { "name": "Album/01. Song - Artist [RedLake 9 - Hi-Res].flac", "size": 100 },
                    { "short_name": "cover.jpg", "size": 3 }
                  ]
                }
              }
            }
            """.trimIndent(),
        )!!
        val entry = objectPage["aabb"]!!
        assertEquals("Album", entry.name)
        assertEquals(2, entry.files.size)
        assertEquals(0, entry.files[0].index)
        assertEquals(1, entry.files[1].index)
        assertEquals("cover.jpg", entry.files[1].name)

        val arrayPage = DebridApi.parseCachedTorrents(
            """
            {
              "success": true,
              "data": [
                {
                  "hash": "CCDD",
                  "files": {
                    "0": { "name": "Song - Artist [RedLake 2 - 320kbps].m4a" },
                    "1": { "name": "Song 2 - Artist [RedLake 2 - 320kbps].m4a" }
                  }
                }
              ]
            }
            """.trimIndent(),
        )!!
        assertEquals(listOf(0, 1), arrayPage["ccdd"]!!.files.map { it.index })
    }

    @Test fun `add and web-download answers yield their ids`() {
        assertEquals(
            77L,
            DebridApi.parseAddedTorrentId(
                """{"success":true,"data":{"torrent_id":77,"hash":"aabb"}}""",
            ),
        )
        assertNull(DebridApi.parseAddedTorrentId("""{"success":false,"detail":"Not cached"}"""))
        assertEquals(
            88L,
            DebridApi.parseWebDownloadId("""{"success":true,"data":{"webdownload_id":88}}"""),
        )
        assertEquals(
            89L,
            DebridApi.parseWebDownloadId("""{"success":true,"data":[{"web_id":89}]}"""),
        )
        assertNull(DebridApi.parseWebDownloadId("""{"success":false,"detail":"Unsupported hoster"}"""))
    }

    @Test fun `a page without data is empty rather than a crash`() {
        val parsed = page("""{"success": false, "detail": "Invalid API key"}""")
        assertEquals(0, parsed.torrents)
        assertTrue(parsed.files.isEmpty())
    }

    @Test fun `download links are read only from a successful answer`() {
        assertEquals(
            "https://cdn.torbox.app/file.flac",
            DebridApi.parseDownloadUrl(
                """{"success": true, "data": "https://cdn.torbox.app/file.flac"}""",
            ),
        )
        assertEquals(
            "https://cdn.torbox.app/file.flac",
            DebridApi.parseDownloadUrl(
                """{"success": true, "data": {"url": "https://cdn.torbox.app/file.flac"}}""",
            ),
        )
        assertNull(DebridApi.parseDownloadUrl("""{"success": false, "detail": "No access"}"""))
        assertNull(DebridApi.parseDownloadUrl("""{"success": true, "data": "not a link"}"""))
        assertNull(DebridApi.parseDownloadUrl("not json"))
    }

    @Test fun `key checks read success and account details`() {
        assertTrue(DebridApi.parseSuccess("""{"success": true, "data": {}}"""))
        assertFalse(DebridApi.parseSuccess("""{"success": false, "detail": "Invalid API key"}"""))
        assertFalse(DebridApi.parseSuccess("not json"))

        assertEquals(
            "listener@example.com",
            DebridApi.parseAccountName(
                """{"success": true, "data": {"email": "listener@example.com"}}""",
            ),
        )
        assertEquals(
            "listener",
            DebridApi.parseAccountName(
                """{"success": true, "data": {"username": "listener"}}""",
            ),
        )
        assertNull(DebridApi.parseAccountName("""{"success": false}"""))
        assertNull(DebridApi.parseAccountName("not json"))
    }
}
