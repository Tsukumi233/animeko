/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.mediasource.fileservice

import com.sun.net.httpserver.HttpServer
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.runBlocking
import me.him188.ani.utils.ktor.createDefaultHttpClient
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Exercises actual local HTTP transport; the sparse content is generated at requested offsets. */
class WebDavLoopbackTest {
    @Test
    fun `real server authenticates lists and serves a range beyond four GiB`() = runBlocking {
        val methods = Collections.synchronizedList(mutableListOf<String>())
        val size = 5_368_709_120L
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/dav") { exchange ->
            methods += exchange.requestMethod
            if (exchange.requestHeaders.getFirst("Authorization") != "Basic dXNlcjpwYXNz") {
                exchange.sendResponseHeaders(401, -1)
            } else if (exchange.requestMethod == "PROPFIND") {
                val root = """<d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                val file = """<d:response><d:href>/dav/%E4%B8%AD%E6%96%87%2001.mkv</d:href><d:propstat><d:prop><d:getcontentlength>$size</d:getcontentlength><d:getetag>&quot;version-1&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
                val content = """<d:multistatus xmlns:d="DAV:">$root$file</d:multistatus>""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/xml")
                exchange.sendResponseHeaders(207, content.size.toLong())
                exchange.responseBody.write(content)
            } else if (exchange.requestMethod == "GET") {
                val range = exchange.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-').map(String::toLong)
                val content = ByteArray((range[1] - range[0] + 1).toInt()) { (range[0] + it).toByte() }
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.responseHeaders.add("Content-Range", "bytes ${range[0]}-${range[1]}/$size")
                exchange.sendResponseHeaders(206, content.size.toLong())
                exchange.responseBody.write(content)
            } else exchange.sendResponseHeaders(405, -1)
            exchange.close()
        }
        server.start()
        val http = createDefaultHttpClient()
        try {
            WebDavFileServiceAccess("http://127.0.0.1:${server.address.port}/dav/", http).use { access ->
                val credentials = FileServiceCredentials("user", "pass")
                val file = access.list("", credentials).single()
                assertEquals("中文 01.mkv", file.name)
                assertEquals(size, file.size)
                val response = http.get(access.url(file.path)) {
                    access.headers(credentials).forEach { (name, value) -> header(name, value) }
                    header("Range", "bytes=4294967300-4294967303")
                }
                assertEquals(206, response.status.value)
                assertContentEquals(byteArrayOf(4, 5, 6, 7), response.body<ByteArray>())
                assertEquals(listOf("PROPFIND", "GET"), methods.toList())
            }
        } finally {
            http.close()
            server.stop(0)
        }
        Unit
    }
}
