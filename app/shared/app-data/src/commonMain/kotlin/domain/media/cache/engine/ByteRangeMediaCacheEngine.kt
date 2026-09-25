/*
 * Copyright (C) 2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */
package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.download.capability.DownloadTransport
import me.him188.ani.app.domain.media.download.capability.MediaDownloadAccessRequest
import me.him188.ani.app.domain.media.download.capability.MediaDownloadCapabilities
import me.him188.ani.app.domain.media.download.capability.PreparedDownloadAccess
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.tools.toProgress
import me.him188.ani.app.torrent.api.files.averageRate
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.DigestAlgorithm
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.copyTo
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.readAndDigest
import me.him188.ani.utils.io.resolve
import kotlin.coroutines.CoroutineContext

/** Downloads readable byte resources into Ani-owned chunks, then publishes one complete local file. */
class ByteRangeMediaCacheEngine(
    private val saveDir: Path,
    private val capabilities: MediaDownloadCapabilities,
    private val mediaSourceId: String,
    private val ioContext: CoroutineContext = Dispatchers.IO_,
) : MediaCacheEngine {
    override val engineKey = MediaCacheEngineKey("byte-range")
    private val tasks = MutableStateFlow<Map<String, ByteRangeCache>>(emptyMap())
    private val taskLock = Mutex()
    override val stats: Flow<MediaStats> = tasks.flatMapLatest { current ->
        if (current.isEmpty()) flowOf(MediaStats.Zero) else combine(current.values.map { it.checkpoint }) { checkpoints ->
            MediaStats(FileSize.Zero, checkpoints.sumOf { it.downloadedBytes }.bytes, FileSize.Zero, FileSize.Zero)
        }
    }

    override fun supports(media: Media): Boolean = media !is CachedMedia &&
        capabilities.find(media, DownloadTransport.BYTE_RANGE) != null
    override fun downloadPriority(media: Media): Int = capabilities.find(media, DownloadTransport.BYTE_RANGE)?.priority(media) ?: 0

    override suspend fun createCache(
        origin: Media, metadata: MediaCacheMetadata, episodeMetadata: EpisodeMetadata, parentContext: CoroutineContext,
    ): MediaCache = taskLock.withLock {
        val id = identity(origin, metadata)
        tasks.value[id]?.takeUnless { it.isDeleted.value }?.let { return it }
        require(supports(origin))
        val task = load(id, origin, metadata, episodeMetadata, parentContext)
        tasks.value = tasks.value + (id to task)
        task.resume()
        task
    }

    override suspend fun restore(origin: Media, metadata: MediaCacheMetadata, parentContext: CoroutineContext): MediaCache? =
        taskLock.withLock {
            val id = identity(origin, metadata)
            tasks.value[id]?.takeUnless { it.isDeleted.value }?.let { return it }
            if (!SystemFileSystem.exists(saveDir.resolve(id).resolve("checkpoint.json"))) return null
            val task = load(id, origin, metadata, EpisodeMetadata(metadata.episodeName, metadata.episodeEp, metadata.episodeSort, metadata.episodeId.toIntOrNull()), parentContext)
            tasks.value = tasks.value + (id to task)
            // Restoration is local-only. The user can resume an incomplete copy when its source is available.
            task
        }

    private suspend fun load(
        id: String, origin: Media, metadata: MediaCacheMetadata, episode: EpisodeMetadata, context: CoroutineContext,
    ): ByteRangeCache = withContext(ioContext) {
        val directory = saveDir.resolve(id)
        SystemFileSystem.createDirectories(directory)
        val path = directory.resolve("checkpoint.json")
        val checkpoint = if (SystemFileSystem.exists(path)) {
            Json.decodeFromString<ByteRangeCheckpoint>(SystemFileSystem.source(path).buffered().use { it.readByteArray().decodeToString() })
        } else ByteRangeCheckpoint().also { fresh ->
            SystemFileSystem.sink(path).buffered().use { it.write(Json.encodeToString(fresh).encodeToByteArray()) }
        }
        require(checkpoint.chunkCount >= 0 && checkpoint.size >= -1 && checkpoint.downloadedBytes >= 0)
        if (checkpoint.completed) {
            check(SystemFileSystem.metadataOrNull(directory.resolve("video.media"))?.size == checkpoint.size) { "Completed download file is missing or changed" }
        } else {
            repeat(checkpoint.chunkCount) { index ->
                val expected = minOf(CHUNK_SIZE.toLong(), checkpoint.size - index.toLong() * CHUNK_SIZE)
                check(SystemFileSystem.metadataOrNull(directory.resolve("$index.part"))?.size == expected) { "Cached chunk is missing or changed" }
            }
        }
        ByteRangeCache(origin, metadata, episode, directory, checkpoint, CoroutineScope(context))
    }

    override suspend fun deleteUnusedCaches(all: List<MediaCache>) = withContext(ioContext) {
        if (!SystemFileSystem.exists(saveDir)) return@withContext
        val allowed = all.filterIsInstance<ByteRangeCache>().map { it.directory }.toSet()
        SystemFileSystem.list(saveDir).filter { it !in allowed }.forEach { SystemFileSystem.deleteRecursively(it) }
    }

    private inner class ByteRangeCache(
        override val origin: Media,
        override val metadata: MediaCacheMetadata,
        val episode: EpisodeMetadata,
        val directory: Path,
        initial: ByteRangeCheckpoint,
        val scope: CoroutineScope,
    ) : MediaCache {
        val checkpoint = MutableStateFlow(initial)
        private val status = MutableStateFlow(if (initial.completed) MediaCacheState.COMPLETED else MediaCacheState.PAUSED)
        override val state: Flow<MediaCacheState> = status
        override val canPlay: Flow<Boolean> = status.map { it == MediaCacheState.COMPLETED }
        override val isDeleted = MutableStateFlow(false)
        override val fileStats = checkpoint.map {
            MediaCache.FileStats(it.size.bytes, it.downloadedBytes.bytes,
                (if (it.completed) 1f else if (it.size > 0) it.downloadedBytes.toFloat() / it.size else 0f).toProgress())
        }
        override val sessionStats = combine(fileStats, checkpoint.map { it.downloadedBytes }.averageRate()) { stats, speed ->
            MediaCache.SessionStats(stats.totalSize, stats.downloadedBytes, speed.bytes, FileSize.Zero, FileSize.Zero, stats.downloadProgress)
        }
        private val operations = Mutex()
        private var job: Job? = null

        override suspend fun getCachedMedia(): CachedMedia {
            check(checkpoint.value.completed && !isDeleted.value) { "Download is not complete" }
            return CachedMedia(origin, mediaSourceId, ResourceLocation.LocalFile(directory.resolve("video.media").inSystem.absolutePath),
                properties = origin.properties.copy(size = checkpoint.value.size.bytes))
        }

        override suspend fun resume() = operations.withLock {
            if (isDeleted.value || checkpoint.value.completed || job?.isActive == true) return@withLock
            job?.join()
            status.value = MediaCacheState.IN_PROGRESS
            job = scope.launch(ioContext) {
                try { transfer() } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    status.value = MediaCacheState.FAILED
                }
            }
        }

        override suspend fun pause() = operations.withLock {
            job?.cancelAndJoin()
            job = null
            if (!checkpoint.value.completed) status.value = MediaCacheState.PAUSED
        }
        override suspend fun close() = pause()
        override suspend fun closeAndDeleteFiles() = operations.withLock {
            if (isDeleted.value) return@withLock
            job?.cancelAndJoin()
            withContext(ioContext) { SystemFileSystem.deleteRecursively(directory) }
            isDeleted.value = true
        }

        private suspend fun save(value: ByteRangeCheckpoint) {
            val tmp = directory.resolve("checkpoint.tmp")
            SystemFileSystem.sink(tmp).buffered().use { it.write(Json.encodeToString(value).encodeToByteArray()) }
            SystemFileSystem.atomicMove(tmp, directory.resolve("checkpoint.json"))
            checkpoint.value = value
        }

        private suspend fun transfer() {
            val capability = capabilities.find(origin, DownloadTransport.BYTE_RANGE) ?: error("Source is unavailable")
            val access = capability.prepare(MediaDownloadAccessRequest(origin, episode,
                selectedFilePath = origin.association?.selectedFilePaths?.get(metadata.episodeId)), scope)
                as? PreparedDownloadAccess.ByteRange ?: error("Source did not provide byte access")
            val old = checkpoint.value
            require(old.chunkCount == 0 || (access.size == old.size && !access.contentIdentity.isNullOrBlank() && access.contentIdentity == old.contentIdentity)) {
                "Content changed or its immutable version is unavailable"
            }
            save(old.copy(size = access.size, contentIdentity = access.contentIdentity))
            access.open().use { reader ->
                val buffer = ByteArray(64 * 1024)
                while (checkpoint.value.downloadedBytes < access.size) {
                    currentCoroutineContext().ensureActive()
                    val offset = checkpoint.value.downloadedBytes
                    val count = minOf(CHUNK_SIZE.toLong(), access.size - offset)
                    val index = checkpoint.value.chunkCount
                    SystemFileSystem.sink(directory.resolve("$index.part")).buffered().use { sink ->
                        var written = 0L
                        while (written < count) {
                            currentCoroutineContext().ensureActive()
                            val requested = minOf(buffer.size.toLong(), count - written).toInt()
                            val read = reader.readAt(offset + written, buffer, requested)
                            require(read in 1..requested) { "Unexpected end of byte resource" }
                            sink.write(buffer, 0, read)
                            written += read
                        }
                    }
                    save(checkpoint.value.copy(chunkCount = index + 1, downloadedBytes = offset + count))
                }
            }
            val output = directory.resolve("video.tmp")
            SystemFileSystem.sink(output).buffered().use { sink ->
                repeat(checkpoint.value.chunkCount) { index ->
                    currentCoroutineContext().ensureActive()
                    SystemFileSystem.source(directory.resolve("$index.part")).buffered().use { it.copyTo(sink) }
                }
            }
            SystemFileSystem.atomicMove(output, directory.resolve("video.media"))
            save(checkpoint.value.copy(completed = true))
            status.value = MediaCacheState.COMPLETED
            repeat(checkpoint.value.chunkCount) { runCatching { SystemFileSystem.delete(directory.resolve("$it.part")) } }
        }
    }

    @Serializable
    private data class ByteRangeCheckpoint(
        val size: Long = -1,
        val contentIdentity: String? = null,
        val chunkCount: Int = 0,
        val downloadedBytes: Long = 0,
        val completed: Boolean = false,
    )

    private fun identity(media: Media, metadata: MediaCacheMetadata): String {
        val key = listOf(media.mediaId, metadata.subjectId, metadata.episodeId).joinToString("") { "${it.length}:$it" }
        return Buffer().apply { writeString(key) }.readAndDigest(DigestAlgorithm.SHA256).toHexString()
    }

    private companion object { const val CHUNK_SIZE = 1024 * 1024 }
}
