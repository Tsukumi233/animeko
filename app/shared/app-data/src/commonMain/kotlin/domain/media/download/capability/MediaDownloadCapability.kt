/*
 * Copyright (C) 2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */
package me.him188.ani.app.domain.media.download.capability

import kotlinx.coroutines.CoroutineScope
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.httpdownloader.DownloadOptions

/** Stable identity and episode context; expiring credentials belong only to prepared access. */
data class MediaDownloadAccessRequest(
    val media: Media,
    val episode: EpisodeMetadata,
    val resourceRef: MediaResourceRef? = (media.download as? ResourceLocation.SourceResource)?.reference,
    val selectedFilePath: String? = episode.episodeId?.let { media.association?.selectedFilePaths?.get(it.toString()) },
)

enum class DownloadTransport { HTTP, BYTE_RANGE, TORRENT }

/** Implementations own source authentication and resolve access on every preparation. */
interface MediaDownloadCapability {
    val transport: DownloadTransport
    fun supports(media: Media): Boolean
    fun priority(media: Media): Int = 0
    suspend fun prepare(request: MediaDownloadAccessRequest, scope: CoroutineScope): PreparedDownloadAccess
}

sealed interface PreparedDownloadAccess {
    data class Http(
        val url: String,
        val options: DownloadOptions = DownloadOptions(),
        /** True when prepare can replace expired credentials for this same immutable resource. */
        val refreshable: Boolean = false,
    ) : PreparedDownloadAccess

    data class ByteRange(
        val size: Long,
        /** Immutable content version. A resource path or file ID is not a content version. */
        val contentIdentity: String?,
        val open: suspend () -> DownloadByteReader,
    ) : PreparedDownloadAccess {
        init { require(size >= 0) }
    }

    data class Torrent(
        val location: ResourceLocation,
        val selectedFilePath: String?,
    ) : PreparedDownloadAccess {
        init { require(location is ResourceLocation.MagnetLink || location is ResourceLocation.HttpTorrentFile) }
    }
}

/** Offset reads must be cancellation-cooperative; close releases only the read session. */
interface DownloadByteReader : AutoCloseable {
    /** Returns bytes read, or -1 at EOF. Never returns more than [length]. */
    suspend fun readAt(offset: Long, buffer: ByteArray, length: Int): Int
}

/** Registration order breaks ties; source protocols and credentials remain inside capabilities. */
class MediaDownloadCapabilities(
    private val capabilities: List<MediaDownloadCapability>,
    private val sourceCapabilities: () -> List<MediaDownloadCapability> = { emptyList() },
) {
    fun find(media: Media, transport: DownloadTransport): MediaDownloadCapability? = (capabilities + sourceCapabilities())
        .filter { it.transport == transport && it.supports(media) }
        .maxByOrNull { it.priority(media) }
}
