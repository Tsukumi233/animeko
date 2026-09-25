/*
 * Copyright (C) 2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */
package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.download.capability.DownloadByteReader
import me.him188.ani.app.domain.media.download.capability.DownloadTransport
import me.him188.ani.app.domain.media.download.capability.MediaDownloadAccessRequest
import me.him188.ani.app.domain.media.download.capability.MediaDownloadCapabilities
import me.him188.ani.app.domain.media.download.capability.MediaDownloadCapability
import me.him188.ani.app.domain.media.download.capability.PreparedDownloadAccess
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.platform.Uuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByteRangeMediaCacheEngineTest {
    private val media = TestMediaList.first()
    private val metadata = MediaCacheMetadata(subjectId = "1", episodeId = "2", subjectNames = listOf("Subject"),
        episodeSort = EpisodeSort(1), episodeName = "One")
    private val episode = EpisodeMetadata("One", EpisodeSort(1), EpisodeSort(1), 2)

    @Test
    fun `interrupted transfer resumes committed chunks and complete copy works without source`() = runTest {
        val dir = temp()
        try {
            val source = Source().apply { failAfter = CHUNK }
            fun engine(capabilities: List<MediaDownloadCapability>) = ByteRangeMediaCacheEngine(dir,
                MediaDownloadCapabilities(capabilities), "test-cache", StandardTestDispatcher(testScheduler))
            val first = engine(listOf(source)).createCache(media, metadata, episode, backgroundScope.coroutineContext)
            runCurrent()
            assertEquals(MediaCacheState.FAILED, first.state.first())
            assertEquals(CHUNK.toLong(), first.fileStats.first().downloadedBytes.inBytes)
            assertEquals(1, source.closed)
            first.close()
            source.failAfter = null
            source.offsets.clear()
            val restored = engine(listOf(source)).restore(media, metadata, backgroundScope.coroutineContext)!!
            assertFalse(restored.canPlay.first())
            restored.resume()
            runCurrent()
            assertEquals(CHUNK.toLong(), source.offsets.first())
            assertEquals(MediaCacheState.COMPLETED, restored.state.first())
            val local = restored.getCachedMedia()
            assertContentEquals(source.data, SystemFileSystem.source(Path((local.download as ResourceLocation.LocalFile).filePath)).buffered().use { it.readByteArray() })
            val offline = engine(emptyList()).restore(media, metadata, backgroundScope.coroutineContext)!!
            assertTrue(offline.canPlay.first())
            assertEquals(local.download, offline.getCachedMedia().download)
            offline.closeAndDeleteFiles()
            assertTrue(offline.isDeleted.value)
        } finally { SystemFileSystem.deleteRecursively(dir) }
    }

    @Test
    fun `changed or missing immutable version rejects existing chunks`() = runTest {
        for (newVersion in listOf(null, "v2")) {
            val dir = temp()
            try {
                val source = Source().apply { failAfter = CHUNK }
                val engine = ByteRangeMediaCacheEngine(dir, MediaDownloadCapabilities(listOf(source)), "test-cache",
                    StandardTestDispatcher(testScheduler))
                val cache = engine.createCache(media, metadata, episode, backgroundScope.coroutineContext)
                runCurrent()
                val reads = source.offsets.size
                source.failAfter = null
                source.version = newVersion
                cache.resume()
                runCurrent()
                assertEquals(MediaCacheState.FAILED, cache.state.first())
                assertEquals(reads, source.offsets.size)
                assertEquals(CHUNK.toLong(), cache.fileStats.first().downloadedBytes.inBytes)
            } finally { SystemFileSystem.deleteRecursively(dir) }
        }
    }

    @Test
    fun `pause cancels suspended read and closes remote handle without publishing partial file`() = runTest {
        val dir = temp()
        try {
            val source = Source().apply { suspendReads = true }
            val engine = ByteRangeMediaCacheEngine(dir, MediaDownloadCapabilities(listOf(source)), "test-cache",
                StandardTestDispatcher(testScheduler))
            val cache = engine.createCache(media, metadata, episode, backgroundScope.coroutineContext)
            runCurrent()
            cache.pause()
            assertEquals(1, source.closed)
            assertEquals(MediaCacheState.PAUSED, cache.state.first())
            assertFalse(cache.canPlay.first())
            assertEquals(0L, cache.fileStats.first().downloadedBytes.inBytes)
        } finally { SystemFileSystem.deleteRecursively(dir) }
    }

    private class Source : MediaDownloadCapability {
        override val transport = DownloadTransport.BYTE_RANGE
        val data = ByteArray(CHUNK + 11) { (it % 251).toByte() }
        var version: String? = "v1"
        var failAfter: Int? = null
        var suspendReads = false
        var closed = 0
        val offsets = mutableListOf<Long>()
        override fun supports(media: Media) = true
        override suspend fun prepare(request: MediaDownloadAccessRequest, scope: CoroutineScope) =
            PreparedDownloadAccess.ByteRange(data.size.toLong(), version) {
                object : DownloadByteReader {
                    override suspend fun readAt(offset: Long, buffer: ByteArray, length: Int): Int {
                        offsets += offset
                        if (suspendReads) awaitCancellation()
                        if (failAfter?.let { offset >= it } == true) error("Connection lost")
                        val size = minOf(length, data.size - offset.toInt())
                        if (size == 0) return -1
                        data.copyInto(buffer, 0, offset.toInt(), offset.toInt() + size)
                        return size
                    }
                    override fun close() { closed++ }
                }
            }
    }

    private fun temp() = SystemTemporaryDirectory.resolve("byte-download-${Uuid.randomString()}").also {
        SystemFileSystem.createDirectories(it)
    }
    private companion object { const val CHUNK = 1024 * 1024 }
}
