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
            DebridApi.LibraryFile(11, 101, "01. Song - Artist [RedLake 9 - Hi-Res].flac"),
            parsed.files[0],
        )
        assertEquals(
            DebridApi.LibraryFile(11, 102, "cover.jpg"),
            parsed.files[1],
        )
        // A single-file torrent whose listing omits files is addressed by
        // its own name, with no file id.
        assertEquals(
            DebridApi.LibraryFile(12, 0, "Single - Artist [RedLake 10 - 320kbps].m4a"),
            parsed.files[2],
        )
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
