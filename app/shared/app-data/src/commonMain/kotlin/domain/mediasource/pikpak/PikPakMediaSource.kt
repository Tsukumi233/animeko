/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.mediasource.pikpak

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.media.DroppedFileMedia
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.domain.media.download.capability.DownloadTransport
import me.him188.ani.app.domain.media.download.capability.MediaDownloadAccessRequest
import me.him188.ani.app.domain.media.download.capability.MediaDownloadCapability
import me.him188.ani.app.domain.media.download.capability.PreparedDownloadAccess
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.HttpStreamingMediaDataProvider
import me.him188.ani.app.domain.media.resolver.MediaSourcePlaybackCapability
import me.him188.ani.app.domain.mediasource.codec.DefaultMediaSourceCodec
import me.him188.ani.app.domain.mediasource.codec.DontForgetToRegisterCodec
import me.him188.ani.app.domain.mediasource.codec.MediaSourceArguments
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.paging.emptySizedSource
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceBrowser
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.source.MediaSourceFactory
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.source.MediaSourcePage
import me.him188.ani.datasources.api.source.MediaSourceResourceFactory
import me.him188.ani.datasources.api.source.MediaSourceSearchScope
import me.him188.ani.datasources.api.source.MediaSourceTier
import me.him188.ani.datasources.api.source.deserializeArgumentsOrNull
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.topic.titles.RawTitleParser
import me.him188.ani.datasources.api.topic.titles.parse
import me.him188.ani.torrent.pikpak.PikPakAccountProvider
import me.him188.ani.torrent.pikpak.PikPakDriveAccess
import me.him188.ani.torrent.pikpak.PikPakDriveEntry
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.httpdownloader.DownloadOptions

@OptIn(DontForgetToRegisterCodec::class)
@Serializable
data class PikPakMediaSourceArguments(
    override val name: String = "PikPak",
    override val tier: MediaSourceTier = MediaSourceTier.Fallback,
) : MediaSourceArguments

object PikPakMediaSourceCodec : DefaultMediaSourceCodec<PikPakMediaSourceArguments>(
    PikPakMediaSource.FactoryId, PikPakMediaSourceArguments::class, 1, PikPakMediaSourceArguments.serializer(),
)

/** Existing drive files are browsed on demand; confirmed associations supply fetch candidates centrally. */
class PikPakMediaSource(
    override val mediaSourceId: String,
    private val accounts: PikPakAccountProvider,
    private val drive: PikPakDriveAccess,
    arguments: PikPakMediaSourceArguments = PikPakMediaSourceArguments(),
    private val downloadConcurrency: () -> Int = { PikPakConfig.Default.downloadConcurrency },
) : MediaSource, MediaSourceBrowser, MediaSourceResourceFactory, MediaSourcePlaybackCapability, MediaDownloadCapability {
    companion object {
        val FactoryId = FactoryId("pikpak-drive")
    }

    override val kind = MediaSourceKind.CloudDrive
    override val info = MediaSourceInfo(arguments.name, "PikPak 网盘中的已有文件", tier = arguments.tier)
    override val searchScope = MediaSourceSearchScope.CURRENT_CONTAINER
    override val transport = DownloadTransport.HTTP

    override fun supports(media: Media): Boolean = media.mediaSourceId == mediaSourceId &&
        (media.download as? ResourceLocation.SourceResource)?.reference?.sourceId == mediaSourceId

    override suspend fun prepare(request: MediaDownloadAccessRequest, scope: CoroutineScope): PreparedDownloadAccess {
        require(supports(request.media)) { "Resource does not belong to this PikPak source" }
        val reference = requireNotNull(request.resourceRef)
        require(reference == (request.media.download as ResourceLocation.SourceResource).reference)
        val locator = reference.decodePikPakLocator(mediaSourceId)
        require(!locator.isDirectory)
        val access = drive.resolve(locator.accountScope, locator.fileId)
        return PreparedDownloadAccess.Http(
            access.url,
            DownloadOptions(
                headers = access.headers,
                contentIdentity = access.contentIdentity,
                maxConcurrentSegments = downloadConcurrency().coerceIn(
                    PikPakConfig.MIN_DOWNLOAD_CONCURRENCY, PikPakConfig.MAX_DOWNLOAD_CONCURRENCY,
                ),
            ),
            refreshable = true,
        )
    }

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> = emptySizedSource()

    override suspend fun checkConnection(): ConnectionStatus {
        drive.list(accounts.accountScope(), pageSize = 1)
        return ConnectionStatus.SUCCESS
    }

    override suspend fun rootEntry(): MediaSourceEntry {
        val locator = PikPakResourceLocator(accounts.accountScope(), "", "", isDirectory = true)
        return MediaSourceEntry(MediaResourceRef(mediaSourceId, locator.resourceId, locatorJson.encodeToString(locator)),
            info.displayName, MediaSourceEntryKind.DIRECTORY)
    }

    override suspend fun browse(parent: MediaResourceRef?, pageToken: String?): MediaSourcePage {
        val locator = parent?.decodePikPakLocator(mediaSourceId)
        require(locator == null || locator.isDirectory) { "Only directories can be browsed" }
        val accountScope = locator?.accountScope ?: accounts.accountScope()
        val page = drive.list(accountScope, locator?.fileId.orEmpty(), pageToken)
        return MediaSourcePage(page.entries.map { it.toSourceEntry(mediaSourceId, accountScope, parent) }, page.nextPageToken)
    }

    override suspend fun search(keyword: String, parent: MediaResourceRef?, pageToken: String?): MediaSourcePage {
        require(pageToken == null) { "PikPak folder search does not expose pagination" }
        val locator = parent?.decodePikPakLocator(mediaSourceId)
        require(locator == null || locator.isDirectory)
        val accountScope = locator?.accountScope ?: accounts.accountScope()
        return MediaSourcePage(
            drive.search(accountScope, keyword, locator?.fileId.orEmpty())
                .map { it.toSourceEntry(mediaSourceId, accountScope, parent) },
        )
    }

    override suspend fun createMedia(reference: MediaResourceRef, request: MediaFetchRequest): Media =
        createPikPakMedia(mediaSourceId, reference, request)

    override suspend fun resolveResource(reference: MediaResourceRef, episode: EpisodeMetadata): MediaDataProvider<*> {
        val locator = reference.decodePikPakLocator(mediaSourceId)
        require(!locator.isDirectory)
        val access = drive.resolve(locator.accountScope, locator.fileId)
        return HttpStreamingMediaDataProvider(access.url, access.fileName, access.headers)
    }

    override fun close() = Unit

    class Factory(
        private val accounts: PikPakAccountProvider,
        private val drive: PikPakDriveAccess,
        private val downloadConcurrency: () -> Int = { PikPakConfig.Default.downloadConcurrency },
    ) : MediaSourceFactory {
        override val factoryId = FactoryId
        override val info = MediaSourceInfo("PikPak", "PikPak 网盘中的已有文件")
        override fun create(mediaSourceId: String, config: MediaSourceConfig, client: ScopedHttpClient): MediaSource =
            PikPakMediaSource(
                mediaSourceId, accounts, drive,
                config.deserializeArgumentsOrNull(PikPakMediaSourceArguments.serializer()) ?: PikPakMediaSourceArguments(),
                downloadConcurrency,
            )
    }
}

/** Account identity and file ID locate content; the remaining fields are display metadata cached for offline entry. */
@Serializable
internal data class PikPakResourceLocator(
    val accountScope: String,
    val fileId: String,
    val name: String,
    val isDirectory: Boolean = false,
    val size: Long? = null,
    val mimeType: String? = null,
    val modifiedTimeMillis: Long? = null,
) {
    val resourceId: String get() = Json.encodeToString(listOf(accountScope, fileId))
}

private val locatorJson = Json { ignoreUnknownKeys = true }

private fun MediaResourceRef.decodePikPakLocator(sourceId: String): PikPakResourceLocator {
    require(this.sourceId == sourceId && version == 1) { "Unsupported PikPak reference" }
    val decoded = locatorJson.decodeFromString<PikPakResourceLocator>(locator)
    require(decoded.accountScope.isNotEmpty() && (decoded.fileId.isNotEmpty() || decoded.isDirectory) && decoded.resourceId == resourceId)
    return decoded
}

internal fun PikPakDriveEntry.toSourceEntry(sourceId: String, accountScope: String, parent: MediaResourceRef?): MediaSourceEntry {
    val locator = PikPakResourceLocator(accountScope, fileId, name, isDirectory, size, mimeType, modifiedAt?.toEpochMilliseconds())
    val isVideo = !isDirectory && isVideoName(name, mimeType)
    return MediaSourceEntry(
        reference = MediaResourceRef(sourceId, locator.resourceId, locatorJson.encodeToString(locator)),
        name = name,
        kind = when {
            isDirectory -> MediaSourceEntryKind.DIRECTORY
            isVideo -> MediaSourceEntryKind.VIDEO
            else -> MediaSourceEntryKind.FILE
        },
        size = size,
        modifiedTimeMillis = locator.modifiedTimeMillis,
        suggestedEpisodeSort = if (isVideo) RawTitleParser.getDefault().parse(name).episodeRange?.knownSorts?.singleOrNull() else null,
        parent = parent,
    )
}

internal fun createPikPakMedia(sourceId: String, reference: MediaResourceRef, request: MediaFetchRequest): Media {
    val locator = reference.decodePikPakLocator(sourceId)
    require(!locator.isDirectory && isVideoName(locator.name, locator.mimeType)) { "Select a video file" }
    require(request.subjectId.isNotEmpty() && request.episodeId.isNotEmpty()) { "Select a subject and episode" }
    val parsed = RawTitleParser.getDefault().parse(locator.name)
    return DefaultMedia(
        mediaId = "$sourceId:${reference.resourceId}",
        mediaSourceId = sourceId,
        originalUrl = "https://mypikpak.com/drive/all",
        download = ResourceLocation.SourceResource(reference),
        originalTitle = locator.name,
        publishedTime = locator.modifiedTimeMillis ?: 0,
        properties = MediaProperties(
            subjectName = request.subjectNames.firstOrNull(),
            episodeName = request.episodeName,
            subtitleLanguageIds = parsed.subtitleLanguages.map { it.id },
            resolution = parsed.resolution?.id.orEmpty(),
            alliance = "",
            size = locator.size?.bytes ?: FileSize.Unspecified,
            subtitleKind = parsed.subtitleKind,
        ),
        episodeRange = EpisodeRange.single(request.episodeSort),
        location = MediaSourceLocation.Online,
        kind = MediaSourceKind.CloudDrive,
    )
}

private fun isVideoName(name: String, mimeType: String?): Boolean =
    mimeType?.startsWith("video/") == true || name.substringAfterLast('.', "").lowercase() in DroppedFileMedia.VIDEO_EXTENSIONS
