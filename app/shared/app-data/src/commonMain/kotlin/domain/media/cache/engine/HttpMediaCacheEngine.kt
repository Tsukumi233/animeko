/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import me.him188.ani.app.data.persistent.database.dao.HttpCacheDownloadStateDao
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.domain.media.cache.DownloaderStatus
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.media.download.capability.DownloadTransport
import me.him188.ani.app.domain.media.download.capability.MediaDownloadCapability
import me.him188.ani.app.domain.media.download.capability.MediaDownloadAccessRequest
import me.him188.ani.app.domain.media.download.capability.MediaDownloadCapabilities
import me.him188.ani.app.domain.media.download.capability.PreparedDownloadAccess
import me.him188.ani.app.domain.media.download.capability.ResolverHttpDownloadCapability
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.app.tools.Progress
import me.him188.ani.app.tools.toProgress
import me.him188.ani.app.torrent.api.files.averageRate
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.MediaCacheProperties
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.httpdownloader.DownloadId
import me.him188.ani.utils.httpdownloader.DownloadProgress
import me.him188.ani.utils.httpdownloader.DownloadState
import me.him188.ani.utils.httpdownloader.DownloadStatus
import me.him188.ani.utils.httpdownloader.HttpDownloader
import me.him188.ani.utils.httpdownloader.MediaType
import me.him188.ani.utils.io.DigestAlgorithm
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.actualSize
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.deleteRecursively
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.readAndDigest
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.coroutines.CoroutineContext

class HttpMediaCacheEngine(
    private val downloader: HttpDownloader,
    private val saveDir: Path,
    private val mediaResolver: MediaResolver,
    private val mediaSourceId: String,
    private val dao: HttpCacheDownloadStateDao,
    pikpakConfig: () -> PikPakConfig = { PikPakConfig.Default },
    sourceCapabilities: () -> List<MediaDownloadCapability> = { emptyList() },
    private val capabilities: MediaDownloadCapabilities = MediaDownloadCapabilities(
        listOf(ResolverHttpDownloadCapability(mediaResolver, pikpakConfig)), sourceCapabilities,
    ),
) : MediaCacheEngine {
    override val engineKey: MediaCacheEngineKey = MediaCacheEngineKey.WebM3u

    override val stats: Flow<MediaStats> = run {
        val downloadSpeedFlow =
            downloader.downloadStatesFlow
                .map { list ->
                    list.sumOf { it.downloadedBytes }
                }
                .averageRate()

        combine(downloader.downloadStatesFlow, downloadSpeedFlow) { list, speed ->
            MediaStats(
                uploaded = FileSize.Zero,
                downloaded = list.sumOf { it.downloadedBytes }.bytes,
                uploadSpeed = FileSize.Zero,
                downloadSpeed = speed.bytes,
            )
        }
    }

    override fun supports(media: Media): Boolean = capabilities.find(media, DownloadTransport.HTTP) != null

    override fun downloadPriority(media: Media): Int = capabilities.find(media, DownloadTransport.HTTP)?.priority(media) ?: 0

    @Composable
    override fun ComposeContent(): Unit = mediaResolver.ComposeContent()

    override suspend fun restore(
        origin: Media,
        metadata: MediaCacheMetadata,
        parentContext: CoroutineContext,
    ): MediaCache? {

        logger.info { "Restoring cache '${origin.mediaId}'" }
        val downloadId = restoredHttpDownloadId(origin, metadata)

        // 注意, getState 一般不会返回 null, 除非 downloader 的 persistent datastore 出问题了 (例如文件损坏).
        if (downloader.getState(downloadId) != null) {
            // Task already exists
            logger.info { "Restored download $downloadId" }
            return HttpMediaCache(origin, downloadId, metadata, parentContext)
        }

        val persistentState = dao.getById(downloadId) ?: kotlin.run {
            logger.error { "Failed to find download state $downloadId from persistent storage while recreating cache." }
            return null
        }

        logger.info { "Restoring persisted HTTP task $downloadId" }
        downloader.restoreState(persistentState)
        check(downloader.getState(downloadId) != null) { "Failed to restore HTTP download $downloadId" }
        return HttpMediaCache(origin, downloadId, metadata, parentContext)
    }

    override suspend fun createCache(
        origin: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        parentContext: CoroutineContext,
    ): MediaCache {
        if (!supports(origin)) throw UnsupportedOperationException("Media is not supported by this engine $this: ${origin.download}")

        val access = prepareAccess(origin, metadata, episodeMetadata, parentContext)
        val downloadId = httpDownloadId(origin, metadata)
        downloader.downloadWithId(downloadId, access.url, access.options)
            ?: error("Failed to create HTTP download $downloadId")
        return HttpMediaCache(origin, downloadId, metadata, parentContext)
    }

    private suspend fun prepareAccess(origin: Media, metadata: MediaCacheMetadata, episode: EpisodeMetadata, parentContext: CoroutineContext): PreparedDownloadAccess.Http {
        val capability = capabilities.find(origin, DownloadTransport.HTTP)
            ?: error("No HTTP download capability for ${origin.mediaId}")
        return capability.prepare(MediaDownloadAccessRequest(origin, episode, selectedFilePath = origin.association?.selectedFilePaths?.get(metadata.episodeId)), CoroutineScope(parentContext))
            as? PreparedDownloadAccess.Http ?: error("HTTP capability returned incompatible access")
    }

    private suspend fun resumeDownload(
        origin: Media, metadata: MediaCacheMetadata, id: DownloadId, parentContext: CoroutineContext,
    ) {
        val state = downloader.getState(id) ?: return
        if (state.status !in listOf(DownloadStatus.PAUSED, DownloadStatus.FAILED)) return
        val access = prepareAccess(origin, metadata, EpisodeMetadata(metadata.episodeName, metadata.episodeEp, metadata.episodeSort, metadata.episodeId.toIntOrNull()), parentContext)
        if (access.refreshable) {
            check(downloader.refreshRequest(id, access.url, access.options.headers, access.options.contentIdentity)) {
                "Download request could not be refreshed"
            }
        }
        downloader.resume(id)
    }

    /**
     * 新建任务的标识, 由 mediaId, subjectId 与 episodeId 共同决定: 合集资源经 PikPak 下载时各集有独立的任务与文件.
     */
    private fun httpDownloadId(media: Media, metadata: MediaCacheMetadata): DownloadId {
        val identity = listOf(media.mediaId, metadata.subjectId, metadata.episodeId)
            .joinToString("") { "${it.length}:$it" }
        val digest = Buffer().apply { writeString(identity) }.readAndDigest(DigestAlgorithm.SHA256).toHexString()
        return DownloadId("http-v2-$digest")
    }

    /**
     * 恢复记录时的任务标识: 优先 [httpDownloadId]; downloader 与 [dao] 中都没有时回退到 [toSafeDownloadId], 以匹配旧记录.
     */
    private suspend fun restoredHttpDownloadId(media: Media, metadata: MediaCacheMetadata): DownloadId {
        val current = httpDownloadId(media, metadata)
        if (downloader.getState(current) != null || dao.getById(current) != null) return current
        return media.toSafeDownloadId()
    }

    override suspend fun deleteUnusedCaches(all: List<MediaCache>) {
        if (!(SystemFileSystem.exists(saveDir))) return


        val allowedAbsolute = buildSet {
            for (mediaCache in all.filterIsInstance<HttpMediaCache>()) {
                downloader.getState(mediaCache.downloadId)?.let { state ->
                    add(Path(saveDir, state.relativeOutputPath).inSystem.absolutePath)
                    add(Path(saveDir, state.relativeSegmentCacheDir).inSystem.absolutePath)
                }
            }
        }
        withContext(Dispatchers.IO_) {
            val saves = SystemFileSystem.list(saveDir)
            for (save in saves) {
                val myPath = save.inSystem.absolutePath
                if (allowedAbsolute.none {
                        myPath.startsWith(it)
                    }) {
                    logger.warn { "本地 WEB 缓存文件未找到匹配的 MediaCache, 已释放 ${save.inSystem.actualSize().bytes}: ${save.inSystem.absolutePath}" }
                    SystemFileSystem.deleteRecursively(save)
                }
            }
        }

    }

    inner class HttpMediaCache(
        override val origin: Media,
        internal val downloadId: DownloadId,
        override val metadata: MediaCacheMetadata,
        private val parentContext: CoroutineContext,
    ) : MediaCache {
        override val state: Flow<MediaCacheState> =
            downloader.getProgressFlow(downloadId).map { it.status.toMediaCacheState() }

        override val canPlay: Flow<Boolean>
            get() = downloader.getProgressFlow(downloadId).map {
                it.status == DownloadStatus.COMPLETED
            }

        override val fileStats: Flow<MediaCache.FileStats> = downloader.getProgressFlow(downloadId).map {
            val totalSize = it.totalBytes
            val downloadedBytes = it.downloadedBytes
            MediaCache.FileStats(
                totalSize = totalSize.bytes,
                downloadedBytes = downloadedBytes.bytes,
                downloadProgress = it.toHttpCacheProgress(),
            )
        }
        override val downloaderStatus: Flow<DownloaderStatus?> = downloader.getProgressFlow(downloadId).map {
            DownloaderStatus.Http(
                status = it.status,
                error = it.error,
                downloadedSegments = it.downloadedSegments,
                totalSegments = it.totalSegments,
                lastSegmentFailure = it.lastSegmentFailure,
            )
        }

        override val sessionStats: Flow<MediaCache.SessionStats> = run {
            val downloadSpeedFlow = fileStats.map { it.downloadedBytes.inBytes }.averageRate()

            combine(downloadSpeedFlow, fileStats) { speed, stats ->
                MediaCache.SessionStats(
                    totalSize = stats.totalSize,
                    downloadedBytes = stats.downloadedBytes,
                    downloadSpeed = speed.bytes,
                    uploadedBytes = FileSize.Zero,
                    uploadSpeed = FileSize.Zero,
                    downloadProgress = stats.downloadProgress,
                )
            }
        }
        override val isDeleted: MutableStateFlow<Boolean> = MutableStateFlow(false)
        private val closeMutex = Mutex()

        override suspend fun getCachedMedia(): CachedMedia {
            val state = downloader.getState(downloadId)
                ?: throw IllegalStateException("Download state not found for $downloadId")

            return when (state.status) {
                DownloadStatus.INITIALIZING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.MERGING,
                DownloadStatus.PAUSED,
                    -> {
                    error("Download not completed, cannot get cached media for $downloadId")
                }

                DownloadStatus.COMPLETED -> {
                    val actualFileSize = withContext(Dispatchers.IO_) {
                        try {
                            Path(saveDir, state.relativeOutputPath).inSystem.actualSize().bytes
                        } catch (_: Exception) {
                            FileSize.Unspecified
                        }
                    }
                    CachedMedia(
                        origin,
                        cacheMediaSourceId = mediaSourceId,
                        download = ResourceLocation.LocalFile(
                            Path(saveDir, state.relativeOutputPath).inSystem.absolutePath,
                            state.toFileType(),
                            originalUri = state.url,
                        ),
                        properties = origin.properties.copy(
                            size = if (actualFileSize.isUnspecified) {
                                origin.properties.size
                            } else {
                                actualFileSize
                            },
                        ),
                        cacheProperties = MediaCacheProperties(
                            totalSegments = state.totalSegments,
                            httpDownloaderStatus = state.status.toString(),
                        ),
                    )
                }

                DownloadStatus.FAILED,
                DownloadStatus.CANCELED,
                    -> {
                    error("Download failed or canceled")
                }
            }
        }

        override suspend fun pause() {
            downloader.pause(downloadId)
        }

        override suspend fun close() {
            if (isDeleted.value) return
            closeMutex.withLock {
                if (isDeleted.value) return
                downloader.cancel(downloadId)
            }
        }

        override suspend fun resume() {
            resumeDownload(origin, metadata, downloadId, parentContext)
        }

        override suspend fun closeAndDeleteFiles() {
            if (isDeleted.value) return
            closeMutex.withLock {
                if (isDeleted.value) return
                val removed = downloader.remove(downloadId)
                if (!removed) {
                    dao.getById(downloadId)?.let { state ->
                        deleteDownloadFiles(state)
                    }
                }
                isDeleted.value = true
            }
        }
    }

    private suspend fun deleteDownloadFiles(state: DownloadState) {
        withContext(Dispatchers.IO_) {
            val outputPath = Path(saveDir, state.relativeOutputPath).inSystem
            if (outputPath.exists()) {
                outputPath.delete()
            }

            val cacheDir = Path(saveDir, state.relativeSegmentCacheDir).inSystem
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
        }
        dao.deleteById(state.downloadId)
    }

    /**
     * 仅由 mediaId 派生的旧标识, 只用于 [restoredHttpDownloadId] 的回退匹配.
     */
    private fun Media.toSafeDownloadId(): DownloadId {
        return DownloadId(mediaId.replace(PATH_AFFECTING_CHARS_REGEX, "-"))
    }

    companion object {
        private val logger = logger<HttpMediaCacheEngine>()
        private val PATH_AFFECTING_CHARS_REGEX = Regex("[\\\\/:*?\"<>|]")

        @Deprecated("Use HttpMediaCacheEngine.MEDIA_CACHE_DIR instead")
        const val LEGACY_MEDIA_CACHE_DIR = "web-m3u-cache"
        const val MEDIA_CACHE_DIR = "web-m3u"
    }
}

internal fun DownloadStatus.toMediaCacheState(): MediaCacheState {
    return when (this) {
        DownloadStatus.INITIALIZING,
        DownloadStatus.DOWNLOADING,
        DownloadStatus.MERGING,
            -> MediaCacheState.IN_PROGRESS

        DownloadStatus.PAUSED,
            -> MediaCacheState.PAUSED

        DownloadStatus.FAILED,
        DownloadStatus.CANCELED,
            -> MediaCacheState.FAILED

        DownloadStatus.COMPLETED,
            -> MediaCacheState.COMPLETED
    }
}

internal fun DownloadProgress.toHttpCacheProgress(): Progress {
    if (status == DownloadStatus.COMPLETED) {
        return 1f.toProgress()
    }

    return when (mediaType) {
        MediaType.M3U8 -> {
            if (totalSegments <= 0) {
                Progress.Unspecified
            } else {
                (downloadedSegments.toFloat() / totalSegments.toFloat()).toProgress()
            }
        }

        MediaType.MP4,
        MediaType.MKV,
            -> {
            if (totalBytes <= 0L) {
                Progress.Unspecified
            } else {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).toProgress()
            }
        }
    }
}

private fun DownloadState.toFileType(): ResourceLocation.LocalFile.FileType? {
    return when {
        relativeOutputPath.endsWith(".ts", ignoreCase = true) -> ResourceLocation.LocalFile.FileType.MPTS
        else -> ResourceLocation.LocalFile.FileType.CONTAINED
    }
}
