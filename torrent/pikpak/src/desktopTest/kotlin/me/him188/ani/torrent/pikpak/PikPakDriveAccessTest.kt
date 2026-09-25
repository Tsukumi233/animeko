/*
 * Copyright (C) 2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.DownloadLink
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.InMemorySessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.him188.ani.torrent.offline.OfflineDownloadRejectedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class PikPakDriveAccessTest {
    @Test
    fun `lists direct folder pages without mutation and preserves stable metadata`() = runTest {
        val requestedTokens = mutableListOf<String?>()
        fixture { path, params ->
            assertEquals("/drive/v1/files", path)
            assertEquals("folder-id", params["parent_id"])
            assertEquals("2", params["limit"])
            requestedTokens += params["page_token"]
            if (params["page_token"] == null) {
                """{"next_page_token":"page-two","files":[
                    {"id":"dir","parent_id":"folder-id","kind":"drive#folder","name":"Folder"},
                    {"id":"file","parent_id":"folder-id","kind":"drive#file","name":"中文 01.mkv","size":"5368709120","hash":"HASH","modified_time":"2026-09-26T00:00:00Z"}
                ]}"""
            } else """{"files":[]}"""
        }.use { f ->
            val scope = f.provider.accountScope()
            val first = f.drive.list(scope, "folder-id", pageSize = 2)
            assertEquals("page-two", first.nextPageToken)
            assertTrue(first.entries[0].isDirectory)
            assertEquals(5_368_709_120L, first.entries[1].size)
            assertEquals("hash:HASH", first.entries[1].contentIdentity)
            assertEquals(Instant.parse("2026-09-26T00:00:00Z"), first.entries[1].modifiedAt)
            assertNull(f.drive.list(scope, "folder-id", first.nextPageToken, 2).nextPageToken)
            assertEquals(listOf(null, "page-two"), requestedTokens)
        }
    }

    @Test
    fun `SDK search is case insensitive direct folder listing across pages`() = runTest {
        var count = 0
        fixture { _, params ->
            assertEquals("selected-folder", params["parent_id"])
            count++
            if (params["page_token"] == null) {
                """{"next_page_token":"next","files":[{"id":"a","name":"EPISODE 01.mkv"},{"id":"folder","kind":"drive#folder","name":"other"}]}"""
            } else {
                """{"files":[{"id":"b","name":"episode 02.mkv"},{"id":"c","name":"unrelated.mkv"}]}"""
            }
        }.use { f ->
            val results = f.drive.search(f.provider.accountScope(), "episode", "selected-folder")
            assertEquals(listOf("a", "b"), results.map { it.fileId })
            assertEquals(2, count)
        }
    }

    @Test
    fun `resolve refreshes links but preserves content identity and download headers`() = runTest {
        var count = 0
        fixture { path, _ ->
            assertEquals("/drive/v1/files/file-id", path)
            count++
            """{"id":"file-id","kind":"drive#file","name":"01.mkv","size":"1234","hash":"content-hash","links":{"application/octet-stream":{"url":"https://cdn.example/video?request=$count","expire":"2030-01-01T00:00:00Z"}}}"""
        }.use { f ->
            val scope = f.provider.accountScope()
            val first = f.drive.resolve(scope, "file-id")
            val second = f.drive.resolve(scope, "file-id")
            assertTrue(first.url != second.url)
            assertEquals(first.contentIdentity, second.contentIdentity)
            assertEquals("application/octet-stream", second.headers["Accept"])
            assertEquals(Instant.parse("2030-01-01T00:00:00Z"), second.expiresAt)
            assertEquals(1234L, second.fileSize)
        }
    }

    @Test
    fun `account change rejects old reference before any drive request`() = runTest {
        var reads = 0
        fixture { _, _ -> reads++; """{"files":[]}""" }.use { f ->
            val original = f.provider.accountScope()
            f.credentials.value = PikPakCredentials("second@example.com", "password")
            assertFailsWith<PikPakAccountChangedException> { f.drive.list(original) }
            assertEquals(0, reads)
        }
    }

    @Test
    fun `concurrent borrowers share one authenticated client`() = runTest {
        fixture { _, _ -> """{"files":[]}""" }.use { f ->
            val clients = List(8) { async { f.provider.withClient { scope, client -> scope to client } } }.awaitAll()
            assertTrue(clients.all { it.first == "account-1" })
            clients.forEach { assertSame(clients.first().second, it.second) }
        }
    }

    @Test
    fun `account change during a read does not publish old account results`() = runTest {
        lateinit var credentials: MutableStateFlow<PikPakCredentials?>
        fixture { _, _ ->
            credentials.value = PikPakCredentials("second@example.com", "password")
            """{"files":[{"id":"private-file","name":"01.mkv"}]}"""
        }.use { f ->
            credentials = f.credentials
            assertFailsWith<PikPakAccountChangedException> { f.drive.list(f.provider.accountScope()) }
        }
    }

    @Test
    fun `removed file is rejected and cancellation propagates`() = runTest {
        fixture { _, _ -> """{"id":"file-id","trashed":true}""" }.use { f ->
            assertFailsWith<OfflineDownloadRejectedException> {
                f.drive.resolve(f.provider.accountScope(), "file-id")
            }
        }
        fixture { _, _ -> throw CancellationException("cancelled") }.use { f ->
            assertFailsWith<CancellationException> { f.drive.list(f.provider.accountScope()) }
        }
    }

    @Test
    fun `file ID and size alone do not assert unchanged content`() {
        val result = FileDetail(
            id = "file", size = "1234", webContentLink = "https://cdn.example/video",
            links = FileDetail.Links(octetStream = DownloadLink(expire = "2030-01-01T00:00:00Z")),
        ).toFileAccess()
        assertNull(result.contentIdentity)
        assertNull(result.expiresAt)
    }

    private fun fixture(driveResponse: (String, Map<String, String?>) -> String): Fixture {
        val credentials = MutableStateFlow<PikPakCredentials?>(PikPakCredentials("first@example.com", "password"))
        var signinCount = 0
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/v1/shield/captcha/init") -> """{"captcha_token":"CAP"}"""
                path.endsWith("/v1/auth/signin") -> {
                    signinCount++
                    """{"access_token":"AT","refresh_token":"RT","sub":"account-$signinCount","expires_in":3600}"""
                }
                else -> {
                    assertEquals(HttpMethod.Get, request.method, "Drive browsing must only read existing files")
                    driveResponse(path, request.url.parameters.names().associateWith { request.url.parameters[it] })
                }
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val provider = PikPakAccountProvider(http, credentials, InMemorySessionStore())
        return Fixture(http, credentials, provider, PikPakDriveAccess(provider))
    }

    private class Fixture(
        val http: HttpClient,
        val credentials: MutableStateFlow<PikPakCredentials?>,
        val provider: PikPakAccountProvider,
        val drive: PikPakDriveAccess,
    ) : AutoCloseable {
        override fun close() = http.close()
    }
}
