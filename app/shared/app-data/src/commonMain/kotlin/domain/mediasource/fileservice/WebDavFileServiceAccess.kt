/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.mediasource.fileservice

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.util.encodeBase64
import me.him188.ani.utils.xml.Element
import me.him188.ani.utils.xml.Xml

/** RFC 4918 Depth 0/1 reads only. Cross-origin redirects and out-of-root response hrefs are rejected. */
class WebDavFileServiceAccess(endpoint: String, httpClient: HttpClient) : FileServiceAccess {
    private val root = Url(endpoint).also {
        require(it.protocol.name in setOf("http", "https") && it.host.isNotEmpty())
        require(it.user.isNullOrEmpty() && it.password.isNullOrEmpty() && it.parameters.isEmpty() && it.fragment.isEmpty())
    }
    private val rootSegments = pathSegments(root.encodedPath)
    private val http = httpClient.config { followRedirects = false }

    fun url(path: String): String {
        checkedRelativePath(path)
        val segments = rootSegments + if (path.isEmpty()) emptyList() else path.split('/')
        return URLBuilder(root).apply { encodedPath = "/" + segments.joinToString("/") { it.encodeURLPathPart() } }.buildString()
    }

    fun headers(credentials: FileServiceCredentials): Map<String, String> = mapOf(
        "Authorization" to "Basic ${"${credentials.username}:${credentials.password}".encodeBase64()}",
        "Accept" to "*/*",
    )

    override suspend fun list(path: String, credentials: FileServiceCredentials): List<FileServiceEntry> =
        properties(path, credentials, 1).filter { it.path != path }

    override suspend fun stat(path: String, credentials: FileServiceCredentials): FileServiceEntry =
        properties(path, credentials, 0).singleOrNull { it.path == path }
            ?: throw FileServiceAccessException("Requested WebDAV resource was not returned")

    private suspend fun properties(path: String, credentials: FileServiceCredentials, depth: Int): List<FileServiceEntry> {
        val target = url(path).let { if (depth == 1) "$it/" else it }
        val response = http.request(target) {
            method = HttpMethod("PROPFIND")
            headers(credentials).forEach { (key, value) -> header(key, value) }
            header("Depth", depth.toString())
            header("Content-Type", "application/xml; charset=utf-8")
            setBody("""<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/><d:getcontenttype/></d:prop></d:propfind>""")
        }
        if (response.status.value != 207) throw FileServiceAccessException("WebDAV returned HTTP ${response.status.value}")
        val document = Xml.parse(response.bodyAsText())
        return document.elementsNamed("response").mapNotNull { item ->
            val href = item.elementsNamed("href").firstOrNull()?.text() ?: return@mapNotNull null
            val relative = relativeHref(href, target)
            // Depth 1 cannot return descendants below the immediate children or adjacent directories.
            require(relative == path || relative.substringBeforeLast('/', "") == path) { "Unexpected WebDAV depth response" }
            val properties = item.elementsNamed("propstat").filter {
                it.elementsNamed("status").firstOrNull()?.text()?.split(' ')?.getOrNull(1) == "200"
            }.flatMap { it.elementsNamed("prop") }
            if (properties.isEmpty()) throw FileServiceAccessException("WebDAV resource properties are unavailable")
            fun text(name: String) = properties.firstNotNullOfOrNull { it.elementsNamed(name).firstOrNull()?.text() }
            val etag = text("getetag")?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
            FileServiceEntry(
                path = relative,
                name = relative.substringAfterLast('/').ifEmpty { rootSegments.lastOrNull().orEmpty() },
                directory = properties.any { it.elementsNamed("collection").isNotEmpty() },
                size = text("getcontentlength")?.toLongOrNull()?.takeIf { it >= 0 },
                modifiedTimeMillis = text("getlastmodified")?.let { parseFileServiceHttpDate(it) },
                contentIdentity = etag?.let { "etag:$it" },
                mimeType = text("getcontenttype"),
            )
        }.distinctBy { it.path }
    }

    private fun relativeHref(href: String, requestUrl: String): String {
        val candidate = when {
            href.startsWith("http://") || href.startsWith("https://") -> Url(href)
            href.startsWith('/') -> Url(URLBuilder(root).apply { encodedPath = href }.buildString())
            else -> Url(requestUrl.substringBeforeLast('/') + "/" + href)
        }
        require(candidate.protocol == root.protocol && candidate.host == root.host && candidate.port == root.port) { "Foreign WebDAV resource" }
        require(candidate.user.isNullOrEmpty() && candidate.password.isNullOrEmpty() && candidate.parameters.isEmpty() && candidate.fragment.isEmpty())
        val segments = pathSegments(candidate.encodedPath)
        require(segments.take(rootSegments.size) == rootSegments) { "WebDAV resource is outside the configured root" }
        return checkedRelativePath(segments.drop(rootSegments.size).joinToString("/"))
    }

    override fun close() = http.close()
}

private fun pathSegments(path: String): List<String> = path.trim('/').takeIf { it.isNotEmpty() }?.split('/')?.map {
    it.decodeURLPart().also { decoded -> require(decoded != "." && decoded != ".." && '/' !in decoded && '\\' !in decoded && '\u0000' !in decoded) }
}.orEmpty()

private fun Element.elementsNamed(name: String): List<Element> = select("*").filter { it.tagName().substringAfter(':').equals(name, true) }

internal expect fun parseFileServiceHttpDate(value: String): Long?

class FileServiceAccessException(message: String) : Exception(message)
