/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.mediasource.fileservice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebDavFileServiceAccessTest {
    private val credentials = FileServiceCredentials("user", "password")

    @Test
    fun `Depth one reads namespaced properties unicode escaped names and large lengths`() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("PROPFIND", request.method.value)
            assertEquals("1", request.headers["Depth"])
            assertTrue(request.headers["Authorization"]?.startsWith("Basic ") == true)
            respond(
                multistatus(
                    response("/dav/", "<d:resourcetype><d:collection/></d:resourcetype>"),
                    response("/dav/%E4%B8%AD%E6%96%87%20%26%20video.mkv", "<d:getcontentlength>5368709120</d:getcontentlength><d:getetag>&quot;revision&quot;</d:getetag>"),
                ),
                HttpStatusCode.MultiStatus,
            )
        })
        try {
            WebDavFileServiceAccess("https://example.test/dav/", http).use { access ->
                val files = access.list("", credentials)
                assertEquals(1, files.size)
                assertEquals("中文 & video.mkv", files.single().name)
                assertEquals(5_368_709_120L, files.single().size)
                assertEquals("etag:\"revision\"", files.single().contentIdentity)
                assertEquals("中文 & video.mkv", Url(access.url(files.single().path)).segments.last())
            }
        } finally { http.close() }
    }

    @Test
    fun `malicious cross origin href traversal and encoded slash are rejected`() = runTest {
        for (href in listOf("https://evil.test/dav/file.mkv", "/other/file.mkv", "/dav/%2e%2e/file.mkv", "/dav/a%2fb.mkv")) {
            val http = HttpClient(MockEngine { respond(multistatus(response(href)), HttpStatusCode.MultiStatus) })
            try {
                WebDavFileServiceAccess("https://example.test/dav/", http).use { access ->
                    assertFailsWith<IllegalArgumentException> { access.list("", credentials) }
                }
            } finally { http.close() }
        }
    }

    @Test
    fun `auth and redirect failures are not empty directories and never followed`() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Found)) {
            var requests = 0
            val http = HttpClient(MockEngine {
                requests++
                respond("", status, headersOf("Location", "https://evil.test/files"))
            })
            try {
                WebDavFileServiceAccess("https://example.test/dav/", http).use { access ->
                    assertFailsWith<FileServiceAccessException> { access.list("", credentials) }
                    assertEquals(1, requests)
                }
            } finally { http.close() }
        }
    }

    @Test
    fun `request cancellation is propagated`() = runTest {
        val http = HttpClient(MockEngine { throw CancellationException("cancelled") })
        try {
            WebDavFileServiceAccess("https://example.test/dav/", http).use { access ->
                assertFailsWith<CancellationException> { access.list("", credentials) }
            }
        } finally { http.close() }
    }

    @Test
    fun `connection URL cannot contain credentials and input paths cannot escape root`() {
        val http = HttpClient(MockEngine { error("No network expected") })
        try {
            assertFailsWith<IllegalArgumentException> { WebDavFileServiceAccess("https://user:secret@example.test/dav/", http) }
            WebDavFileServiceAccess("https://example.test/dav/", http).use { access ->
                for (path in listOf("../file", "a/../../file", "/absolute", "a\\b")) {
                    assertFailsWith<IllegalArgumentException> { access.url(path) }
                }
            }
            assertEquals("FileServiceCredentials(redacted)", credentials.toString())
        } finally { http.close() }
    }

    private fun response(href: String, properties: String = "") =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop>$properties</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    private fun multistatus(vararg entries: String) = """<d:multistatus xmlns:d="DAV:">${entries.joinToString("")}</d:multistatus>"""
}
