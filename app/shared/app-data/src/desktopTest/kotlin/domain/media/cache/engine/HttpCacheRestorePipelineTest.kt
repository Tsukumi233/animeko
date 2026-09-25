package me.him188.ani.app.domain.media.cache.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.download.capability.DownloadTransport
import me.him188.ani.app.domain.media.download.capability.MediaDownloadAccessRequest
import me.him188.ani.app.domain.media.download.capability.MediaDownloadCapability
import me.him188.ani.app.domain.media.download.capability.PreparedDownloadAccess
import me.him188.ani.app.domain.media.resolver.TestUniversalMediaResolver
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.httpdownloader.DownloadId
import me.him188.ani.utils.httpdownloader.DownloadOptions
import me.him188.ani.utils.httpdownloader.DownloadState
import me.him188.ani.utils.httpdownloader.DownloadStatus
import me.him188.ani.utils.httpdownloader.MediaType
import me.him188.ani.utils.httpdownloader.SegmentInfo
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.ktor.createDefaultHttpClient
import me.him188.ani.utils.ktor.asScopedHttpClient
import me.him188.ani.utils.platform.Uuid
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Room -> persistent downloader -> cache engine -> actual loopback HTTP -> assembled local file. */
class HttpCacheRestorePipelineTest {
    private val metadata = MediaCacheMetadata(subjectId = "1", episodeId = "11", subjectNames = listOf("Title"),
        episodeSort = EpisodeSort(1), episodeEp = EpisodeSort(1), episodeName = "Episode")

    private class Fixture {
        val directory = SystemTemporaryDirectory.resolve("http-restore-${Uuid.randomString()}")
        val database = createTestAniDatabase()
        val dao = database.httpCacheDownloadStateDao()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val client = createDefaultHttpClient()
        val downloader = KtorPersistentHttpDownloader(dao, client.asScopedHttpClient(), SystemFileSystem, directory, scope = scope)
        var prepares = 0
        var offline = false
        var identity = "v1"
        val id = DownloadId("release")
        val media = TestMediaList.first().copy(mediaId = id.value,
            download = ResourceLocation.SourceResource(MediaResourceRef("drive", "file")))

        init {
            SystemFileSystem.createDirectories(directory.resolve("segments"))
            server.createContext("/") { exchange ->
                val range = exchange.requestHeaders.getFirst("Range")
                val auth = exchange.requestHeaders.getFirst("Authorization")
                requests += "${exchange.requestURI.path}|$auth|$range"
                if (exchange.requestURI.path != "/fresh.mp4" || auth != "fresh") {
                    exchange.sendResponseHeaders(403, -1)
                } else {
                    val bounds = range?.removePrefix("bytes=")?.split('-')?.map(String::toInt) ?: listOf(0, 5)
                    val bytes = "abcdef".toByteArray().copyOfRange(bounds[0], bounds[1] + 1)
                    exchange.responseHeaders.add("Content-Type", "video/mp4")
                    exchange.responseHeaders.add("Content-Range", "bytes ${bounds[0]}-${bounds[1]}/6")
                    exchange.sendResponseHeaders(206, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
                exchange.close()
            }
            server.start()
        }

        val capability = object : MediaDownloadCapability {
            override val transport = DownloadTransport.HTTP
            override fun supports(media: Media) = true
            override fun priority(media: Media) = 100
            override suspend fun prepare(request: MediaDownloadAccessRequest, scope: CoroutineScope): PreparedDownloadAccess.Http {
                prepares++
                check(!offline) { "Source offline" }
                return PreparedDownloadAccess.Http("http://127.0.0.1:${server.address.port}/fresh.mp4",
                    DownloadOptions(headers = mapOf("Authorization" to "fresh"), contentIdentity = identity), refreshable = true)
            }
        }
        val engine = HttpMediaCacheEngine(downloader, directory, TestUniversalMediaResolver, "cache", dao,
            sourceCapabilities = { listOf(capability) })

        suspend fun seed(status: DownloadStatus = DownloadStatus.DOWNLOADING) {
            val stale = "http://127.0.0.1:${server.address.port}/expired.mp4"
            dao.upsert(DownloadState(id, stale, "result.mp4", listOf(
                SegmentInfo(0, stale, true, 3, relativeTempFilePath = "segments/0.part", rangeStart = 0, rangeEnd = 2),
                SegmentInfo(1, stale, false, 3, relativeTempFilePath = "segments/1.part", rangeStart = 3, rangeEnd = 5),
            ), 2, 3, 0, status, relativeSegmentCacheDir = "segments", mediaType = MediaType.MP4,
                requestHeaders = mapOf("Authorization" to "expired"), contentIdentity = "v1"))
            SystemFileSystem.sink(directory.resolve("segments/0.part")).buffered().use { it.writeString("abc") }
        }

        suspend fun close() {
            downloader.close()
            job.cancelAndJoin()
            client.close()
            server.stop(0)
            database.close()
            SystemFileSystem.deleteRecursively(directory)
        }
    }

    private fun test(block: suspend Fixture.() -> Unit) = runBlocking {
        val fixture = Fixture()
        try { withTimeout(20_000) { fixture.block() } } finally { fixture.close() }
    }

    @Test
    fun `DAO-only restore refreshes capability before first HTTP request and preserves downloaded bytes`() = test {
        seed()
        val cache = requireNotNull(engine.restore(media, metadata, scope.coroutineContext))
        assertEquals(DownloadStatus.PAUSED, downloader.getState(id)!!.status)
        assertEquals(0, prepares)
        assertTrue(requests.isEmpty())
        cache.resume()
        val finished = downloader.downloadStatesFlow.first { states -> states.any { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED } }.single()
        assertEquals(DownloadStatus.COMPLETED, finished.status, finished.error.toString())
        assertEquals(1, prepares)
        assertEquals(listOf("/fresh.mp4|fresh|bytes=0-0", "/fresh.mp4|fresh|bytes=3-5"), requests.toList())
        assertContentEquals("abcdef".toByteArray(), SystemFileSystem.source(directory.resolve("result.mp4")).buffered().use { it.readByteArray() })
        assertTrue(cache.getCachedMedia().download is ResourceLocation.LocalFile)
    }

    @Test
    fun `completed DAO-only copy stays playable without available source or requests`() = test {
        seed(DownloadStatus.COMPLETED)
        SystemFileSystem.sink(directory.resolve("result.mp4")).buffered().use { it.writeString("abcdef") }
        offline = true
        val cache = requireNotNull(engine.restore(media, metadata, scope.coroutineContext))
        cache.resume()
        assertTrue(cache.canPlay.first())
        assertTrue(cache.getCachedMedia().download is ResourceLocation.LocalFile)
        assertEquals(0, prepares)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `refresh failure preserves task and partial bytes for retry and never probes expired URL`() = test {
        seed(DownloadStatus.FAILED)
        val cache = requireNotNull(engine.restore(media, metadata, scope.coroutineContext))
        offline = true
        assertFailsWith<IllegalStateException> { cache.resume() }
        assertTrue(requests.isEmpty())
        assertEquals(DownloadStatus.FAILED, downloader.getState(id)!!.status)
        offline = false
        cache.resume()
        downloader.downloadStatesFlow.first { states -> states.any { it.status == DownloadStatus.COMPLETED } }
        assertEquals(2, prepares)
        assertTrue(requests.all { it.startsWith("/fresh.mp4|fresh|") })
    }

    @Test
    fun `changed immutable version refuses partial reuse before HTTP for any refreshable capability`() = test {
        seed()
        val plainMedia = media.copy(download = ResourceLocation.HttpStreamingFile("https://example.com/stable"))
        val cache = requireNotNull(engine.restore(plainMedia, metadata, scope.coroutineContext))
        identity = "v2"
        assertFailsWith<IllegalArgumentException> { cache.resume() }
        assertEquals(1, prepares)
        assertTrue(requests.isEmpty())
        assertEquals("v1", downloader.getState(id)!!.contentIdentity)
        assertTrue(downloader.getState(id)!!.error!!.technicalMessage!!.contains("create it again"))
        assertFalse(downloader.restoreState(downloader.getState(id)!!.copy(status = DownloadStatus.COMPLETED)))
        assertEquals(DownloadStatus.PAUSED, downloader.getState(id)!!.status)
    }
}
