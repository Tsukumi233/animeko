package me.him188.ani.app.domain.media.cache.engine

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
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
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.TestUniversalMediaResolver
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.httpdownloader.DownloadErrorCode
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        @Volatile var prepares = 0
        var expiredPreparations = 0
        var blockExpiredSegment = false
        val expiredSegmentStarted = CompletableDeferred<Unit>()
        val allowExpiredSegment = CountDownLatch(1)
        var replacementIdentity: String? = null
        var blockRecovery = false
        val recoveryStarted = CompletableDeferred<Unit>()
        val recoveryCanceled = CompletableDeferred<Unit>()
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
                if (blockExpiredSegment && auth == "expired" && range != "bytes=0-0") {
                    expiredSegmentStarted.complete(Unit)
                    allowExpiredSegment.await(5, TimeUnit.SECONDS)
                }
                val refreshProbe = exchange.requestURI.path == "/refreshable.mp4" && range == "bytes=0-0"
                if (!refreshProbe && (exchange.requestURI.path != "/fresh.mp4" || auth != "fresh")) {
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
                if (prepares > 1 && blockRecovery) {
                    recoveryStarted.complete(Unit)
                    try { awaitCancellation() } finally { recoveryCanceled.complete(Unit) }
                }
                val expired = prepares <= expiredPreparations
                val path = if (expired) "refreshable.mp4" else "fresh.mp4"
                return PreparedDownloadAccess.Http("http://127.0.0.1:${server.address.port}/$path",
                    DownloadOptions(headers = mapOf("Authorization" to if (expired) "expired" else "fresh"),
                        contentIdentity = if (prepares > 1) replacementIdentity ?: identity else identity), refreshable = true)
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
            allowExpiredSegment.countDown()
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
    fun `new transfer keeps expiry recovery when storage repeats resume during active transfer`() = test {
        expiredPreparations = 1
        blockExpiredSegment = true
        val cache = engine.createCache(media, metadata,
            EpisodeMetadata(metadata.episodeName, metadata.episodeEp, metadata.episodeSort, 11), scope.coroutineContext)
        expiredSegmentStarted.await()
        cache.resume()
        allowExpiredSegment.countDown()
        val complete = downloader.downloadStatesFlow.first { states -> states.any { it.status == DownloadStatus.COMPLETED } }.single()
        assertEquals(2, prepares)
        assertEquals(6L, complete.downloadedBytes)
        assertContentEquals("abcdef".toByteArray(), SystemFileSystem.source(directory.resolve(complete.relativeOutputPath)).buffered().use { it.readByteArray() })
    }

    @Test
    fun `running expired access refreshes once and preserves completed segment bytes`() = test {
        seed()
        expiredPreparations = 1
        val cache = requireNotNull(engine.restore(media, metadata, scope.coroutineContext))
        cache.resume()
        downloader.downloadStatesFlow.first { states -> states.any { it.status == DownloadStatus.COMPLETED } }
        assertEquals(2, prepares)
        assertEquals(1, requests.count { it == "/refreshable.mp4|expired|bytes=3-5" })
        assertTrue(requests.none { it.endsWith("bytes=0-2") })
        assertContentEquals("abcdef".toByteArray(), SystemFileSystem.source(directory.resolve("result.mp4")).buffered().use { it.readByteArray() })
    }

    @Test
    fun `second expired access remains failed without an automatic refresh loop`() = test {
        seed()
        expiredPreparations = Int.MAX_VALUE
        val cache = requireNotNull(engine.restore(media, metadata, scope.coroutineContext))
        cache.resume()
        downloader.downloadStatesFlow.first { states -> prepares == 2 && states.any {
            it.status == DownloadStatus.FAILED && requests.count { request -> request.endsWith("bytes=3-5") } == 2
        } }
        delay(100)
        assertEquals(2, prepares)
        assertEquals(3L, downloader.getState(id)!!.downloadedBytes)
        assertEquals(DownloadErrorCode.HTTP_ACCESS_EXPIRED, downloader.getState(id)!!.error?.code)
    }

    @Test
    fun `automatic expiry refresh fails closed when immutable content changes`() = test {
        seed()
        expiredPreparations = 1
        replacementIdentity = "v2"
        val cache = requireNotNull(engine.restore(media, metadata, scope.coroutineContext))
        cache.resume()
        downloader.downloadStatesFlow.first { states -> states.any { it.error?.technicalMessage?.contains("create it again") == true } }
        assertEquals(2, prepares)
        assertEquals("v1", downloader.getState(id)!!.contentIdentity)
        assertEquals(3L, downloader.getState(id)!!.downloadedBytes)
        assertTrue(requests.none { it.startsWith("/fresh.mp4") })
        assertContentEquals("abc".toByteArray(), SystemFileSystem.source(directory.resolve("segments/0.part")).buffered().use { it.readByteArray() })
    }

    @Test
    fun `pause cancels in flight credential recovery without restarting transport`() = test {
        seed()
        expiredPreparations = 1
        blockRecovery = true
        val cache = requireNotNull(engine.restore(media, metadata, scope.coroutineContext))
        cache.resume()
        recoveryStarted.await()
        cache.pause()
        recoveryCanceled.await()
        assertEquals(2, prepares)
        assertEquals(3L, downloader.getState(id)!!.downloadedBytes)
        assertTrue(requests.none { it.startsWith("/fresh.mp4") })
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
