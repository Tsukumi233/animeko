/*
 * Copyright (C) 2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */
package me.him188.ani.utils.httpdownloader

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.platform.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpRequestRefreshTest {
    @Test
    fun `refresh preserves downloaded segments and replaces credentials after restart`() = runTest {
        fixture(this) { downloader, dir ->
            val old = state()
            downloader.restore(old)
            assertTrue(downloader.refreshRequest(old.downloadId, NEW_URL, mapOf("Authorization" to "new"), "v1"))
            val fresh = downloader.getState(old.downloadId)!!
            assertEquals(old.downloadId, fresh.downloadId)
            assertEquals(old.downloadedBytes, fresh.downloadedBytes)
            assertEquals(old.segments.map { it.isDownloaded }, fresh.segments.map { it.isDownloaded })
            assertEquals(old.segments.map { it.relativeTempFilePath }, fresh.segments.map { it.relativeTempFilePath })
            assertTrue(fresh.segments.all { it.url == NEW_URL })
            assertEquals(mapOf("Authorization" to "new"), fresh.requestHeaders)
            assertEquals(old.relativeOutputPath, fresh.relativeOutputPath)
            SystemFileSystem.createDirectories(dir.resolve("segments"))
            SystemFileSystem.sink(dir.resolve("segments/0.part")).buffered().use { it.write("abc".encodeToByteArray()) }
            assertTrue(downloader.resume(old.downloadId))
            downloader.joinDownload(old.downloadId)
            assertEquals(DownloadStatus.COMPLETED, downloader.getState(old.downloadId)!!.status)
            assertEquals("abcdef", SystemFileSystem.source(dir.resolve("stable.mp4")).buffered().use {
                it.readByteArray().decodeToString()
            })
        }
    }

    @Test
    fun `changed or unknown identity cannot reuse downloaded bytes`() = runTest {
        fixture(this) { downloader, _ ->
            val old = state()
            downloader.restore(old)
            for (identity in listOf(null, "", "v2")) {
                assertFailsWith<IllegalArgumentException> {
                    downloader.refreshRequest(old.downloadId, NEW_URL, emptyMap(), identity)
                }
                assertEquals(old, downloader.getState(old.downloadId)!!.copy(error = null))
                assertTrue(downloader.getState(old.downloadId)!!.error!!.technicalMessage!!.contains("create it again"))
            }
        }
    }

    @Test
    fun `changed length and missing range support reject refresh without mutation`() = runTest {
        for (url in listOf("https://test/changed.mp4", "https://test/no-range.mp4")) {
            fixture(this) { downloader, _ ->
                val old = state()
                downloader.restore(old)
                assertFailsWith<IllegalArgumentException> {
                    downloader.refreshRequest(old.downloadId, url, emptyMap(), "v1")
                }
                assertEquals(old, downloader.getState(old.downloadId)!!.copy(error = null))
            }
        }
    }

    @Test
    fun `completed tasks are immutable`() = runTest {
        fixture(this) { downloader, _ ->
            val old = state().copy(status = DownloadStatus.COMPLETED)
            downloader.restore(old)
            assertFalse(downloader.refreshRequest(old.downloadId, NEW_URL, emptyMap(), "v1"))
            assertEquals(old, downloader.getState(old.downloadId))
        }
    }

    @Test
    fun `ranged segment rejects ignored or malformed range and short bodies`() = runTest {
        fixture(this) { downloader, _ ->
            for (path in listOf("no-range", "wrong-range", "short")) {
                assertFailsWith<IllegalArgumentException> {
                    downloader.fetch(state().segments.first().copy(url = "https://test/$path.mp4"))
                }
            }
        }
    }

    @Test
    fun `hls refresh resolves signed segments and rejects changed sequence`() = runTest {
        fixture(this) { downloader, dir ->
            val old = state().copy(
                url = "https://test/old.m3u8", mediaType = MediaType.M3U8,
                segments = listOf(
                    SegmentInfo(0, "https://test/seg.ts?old", true, byteSize = 3,
                        durationSeconds = 10f, relativeTempFilePath = "segments/0.ts"),
                ), totalSegments = 1, downloadedBytes = 3,
            )
            SystemFileSystem.createDirectories(dir.resolve("segments"))
            SystemFileSystem.sink(dir.resolve("segments/upstream-playlist.m3u8")).buffered().use {
                it.write(playlist(0, "old").encodeToByteArray())
            }
            downloader.restore(old)
            assertFailsWith<IllegalArgumentException> {
                downloader.refreshRequest(old.downloadId, "https://test/changed.m3u8", emptyMap(), "v1")
            }
            assertEquals(old, downloader.getState(old.downloadId)!!.copy(error = null))
            assertTrue(downloader.refreshRequest(old.downloadId, "https://test/new.m3u8", emptyMap(), "v1"))
            val fresh = downloader.getState(old.downloadId)!!
            assertEquals("https://test/seg.ts?new", fresh.segments.single().url)
            assertTrue(fresh.segments.single().isDownloaded)
        }
    }

    private suspend fun fixture(scope: CoroutineScope, block: suspend (TestDownloader, Path) -> Unit) {
        val dir = SystemTemporaryDirectory.resolve("refresh-${Uuid.randomString()}")
        SystemFileSystem.createDirectories(dir)
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/new.m3u8" -> respond(playlist(0, "new"))
                "/changed.m3u8" -> respond(playlist(1, "new"))
                "/no-range.mp4" -> respond("abcdef", HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "6"))
                "/changed.mp4" -> respond("a", HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 0-0/7"))
                "/wrong-range.mp4" -> respond("abc", HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 1-3/6"))
                "/short.mp4" -> respond("a", HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 0-2/6"))
                else -> {
                    assertEquals("new", request.headers["Authorization"])
                    if (request.headers[HttpHeaders.Range] == "bytes=0-0") {
                        respond("a", HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 0-0/6"))
                    } else {
                        assertEquals("bytes=3-5", request.headers[HttpHeaders.Range])
                        respond("def", HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 3-5/6"))
                    }
                }
            }
        })
        val downloader = TestDownloader(client, dir, scope)
        try { block(downloader, dir) } finally {
            downloader.close()
            client.close()
            SystemFileSystem.deleteRecursively(dir)
        }
    }

    private class TestDownloader(client: HttpClient, dir: Path, scope: CoroutineScope) : KtorHttpDownloader(
        client.asScopedHttpClient(), SystemFileSystem, dir, parentScope = scope,
    ) {
        fun restore(state: DownloadState) {
            _downloadStatesFlow.value = persistentMapOf(state.downloadId to DownloadEntry(null, state))
        }
        suspend fun fetch(segment: SegmentInfo) = downloadSingleSegment(segment, DownloadOptions())
    }

    private fun state() = DownloadState(
        DownloadId("stable"), "https://test/old.mp4", "stable.mp4",
        listOf(
            SegmentInfo(0, "https://test/old.mp4", true, 3, relativeTempFilePath = "segments/0.part", rangeStart = 0, rangeEnd = 2),
            SegmentInfo(1, "https://test/old.mp4", false, 3, relativeTempFilePath = "segments/1.part", rangeStart = 3, rangeEnd = 5),
        ), 2, 3, 0, DownloadStatus.PAUSED,
        relativeSegmentCacheDir = "segments", requestHeaders = mapOf("Authorization" to "old"),
        mediaType = MediaType.MP4, contentIdentity = "v1",
    )

    private fun playlist(sequence: Int, token: String) = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:$sequence
        #EXTINF:10,
        seg.ts?$token
        #EXT-X-ENDLIST
    """.trimIndent()

    private companion object { const val NEW_URL = "https://test/new.mp4" }
}
