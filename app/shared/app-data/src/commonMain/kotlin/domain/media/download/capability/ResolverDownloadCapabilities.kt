/*
 * Copyright (C) 2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */
package me.him188.ani.app.domain.media.download.capability

import kotlinx.coroutines.CoroutineScope
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.domain.media.resolver.MediaResolver
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.httpdownloader.DownloadOptions
import org.openani.mediamp.source.UriMediaData

/** Adapts existing HTTP, web extraction, and optional cloud torrent resolution. */
class ResolverHttpDownloadCapability(
    private val resolver: MediaResolver,
    private val pikpakConfig: () -> PikPakConfig = { PikPakConfig.Default },
) : MediaDownloadCapability {
    override val transport = DownloadTransport.HTTP
    override fun supports(media: Media): Boolean = media !is CachedMedia && when (media.download) {
        is ResourceLocation.LocalFile, is ResourceLocation.SourceResource -> false
        is ResourceLocation.HttpTorrentFile, is ResourceLocation.MagnetLink -> pikpakConfig().enabled && resolver.supports(media)
        else -> resolver.supports(media)
    }

    override fun priority(media: Media): Int = if (media.kind == MediaSourceKind.BitTorrent) 10 else 0

    override suspend fun prepare(request: MediaDownloadAccessRequest, scope: CoroutineScope): PreparedDownloadAccess.Http {
        require(supports(request.media))
        val data = resolver.resolve(request.media, request.episode).open(scope)
        require(data is UriMediaData) { "Resolver did not provide HTTP access" }
        var options = DownloadOptions(headers = data.headers)
        if (request.media.kind == MediaSourceKind.BitTorrent) {
            val config = pikpakConfig()
            options = options.copy(
                maxConcurrentSegments = config.downloadConcurrency.coerceIn(
                    PikPakConfig.MIN_DOWNLOAD_CONCURRENCY, PikPakConfig.MAX_DOWNLOAD_CONCURRENCY,
                ),
                headers = options.headers + ("Accept" to "application/octet-stream"),
            )
        }
        val refreshable = when (request.media.download) {
            is ResourceLocation.WebVideo, is ResourceLocation.MagnetLink, is ResourceLocation.HttpTorrentFile -> true
            else -> false
        }
        return PreparedDownloadAccess.Http(data.uri, options, refreshable)
    }
}

object TorrentDownloadCapability : MediaDownloadCapability {
    override val transport = DownloadTransport.TORRENT
    override fun supports(media: Media): Boolean = media !is CachedMedia &&
        (media.download is ResourceLocation.HttpTorrentFile || media.download is ResourceLocation.MagnetLink)

    override suspend fun prepare(request: MediaDownloadAccessRequest, scope: CoroutineScope): PreparedDownloadAccess.Torrent {
        require(supports(request.media))
        return PreparedDownloadAccess.Torrent(request.media.download, request.selectedFilePath)
    }
}
