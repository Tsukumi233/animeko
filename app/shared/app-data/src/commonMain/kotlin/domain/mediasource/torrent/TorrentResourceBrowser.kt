/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.mediasource.torrent

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.domain.media.DroppedFileMedia
import me.him188.ani.app.domain.media.cache.engine.EnsureTorrentEngineIsAccessible
import me.him188.ani.app.domain.media.cache.engine.TorrentEngineAccess
import me.him188.ani.app.domain.media.cache.engine.withServiceRequest
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.TorrentMediaSourceReferences

/** A file row is identified by its full path; the release reference remains the source factory input. */
data class TorrentResourceFile(
    val pathInTorrent: String,
    val name: String,
    val size: Long,
    val isVideo: Boolean,
)

data class TorrentResourceListing(
    val releaseReference: MediaResourceRef,
    val originalMedia: Media,
    val files: List<TorrentResourceFile>,
)

/**
 * Enumerates torrent metadata without requesting payload file handles. The downloader and any active
 * torrent task remain owned by the engine; only an unused metadata session may be closed.
 */
class TorrentResourceBrowser(
    private val engine: () -> TorrentEngine?,
    private val engineAccess: TorrentEngineAccess,
    private val timeoutMillis: Long = 60_000,
) {
    constructor(engine: TorrentEngine, engineAccess: TorrentEngineAccess, timeoutMillis: Long = 60_000) :
        this({ engine }, engineAccess, timeoutMillis)

    init {
        require(timeoutMillis > 0)
    }

    @OptIn(EnsureTorrentEngineIsAccessible::class)
    suspend fun browse(reference: MediaResourceRef): TorrentResourceListing {
        val media = TorrentMediaSourceReferences.decode(reference)
        val engine = engine() ?: throw UnsupportedOperationException("Torrent browsing is not supported on this platform")
        check(engine.isSupported) { "Torrent browsing is not supported on this platform" }
        return engineAccess.withServiceRequest(Any()) {
            withTimeout(timeoutMillis) {
                val downloader = engine.getDownloader()
                val metadata = downloader.fetchTorrent(media.download.uri)
                val session = downloader.startDownload(metadata)
                var failure: Throwable? = null
                try {
                    val paths = hashSetOf<String>()
                    val files = session.getFiles().map { file ->
                        currentCoroutineContext().ensureActive()
                        val path = file.pathInTorrent
                        require(path.isNotBlank() && file.length >= 0) { "Torrent file metadata is incomplete" }
                        require(paths.add(path)) { "Torrent contains duplicate full file paths" }
                        TorrentResourceFile(
                            path, file.fileName, file.length,
                            file.fileName.substringAfterLast('.', "").lowercase() in DroppedFileMedia.VIDEO_EXTENSIONS,
                        )
                    }
                    TorrentResourceListing(reference, media, files)
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    try {
                        withContext(NonCancellable) { session.closeIfNotInUse() }
                    } catch (closeError: Throwable) {
                        failure?.addSuppressed(closeError) ?: throw closeError
                    }
                }
            }
        }
    }
}
