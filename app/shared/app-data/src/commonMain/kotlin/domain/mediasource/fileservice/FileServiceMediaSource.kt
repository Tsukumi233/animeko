/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.mediasource.fileservice

import kotlinx.coroutines.CoroutineScope
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.media.DroppedFileMedia
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
import me.him188.ani.utils.httpdownloader.DownloadOptions
import me.him188.ani.utils.io.DigestAlgorithm
import me.him188.ani.utils.io.digest
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.ktor.UnsafeScopedHttpClientApi

@Serializable
enum class FileServiceProtocol { WEBDAV, SMB }

/** Credentials belong to local storage keyed by source ID, never this exportable configuration. */
@Serializable
@OptIn(DontForgetToRegisterCodec::class)
data class FileServiceArguments(
    override val name: String,
    val protocol: FileServiceProtocol,
    /** HTTP(S) root URL for WebDAV, host name for SMB. */
    val endpoint: String,
    val share: String = "",
    val root: String = "",
    val port: Int = 445,
    override val tier: MediaSourceTier = MediaSourceTier.Fallback,
) : MediaSourceArguments

object FileServiceMediaSourceCodec : DefaultMediaSourceCodec<FileServiceArguments>(
    FileServiceMediaSource.FactoryId, FileServiceArguments::class, 1, FileServiceArguments.serializer(),
)

class FileServiceMediaSource(
    override val mediaSourceId: String,
    private val arguments: FileServiceArguments,
    private val credentials: FileServiceCredentialProvider,
    private val access: FileServiceAccess,
) : MediaSource, MediaSourceBrowser, MediaSourceResourceFactory, MediaSourcePlaybackCapability, MediaDownloadCapability {
    companion object {
        val FactoryId = FactoryId("file-service")
    }

    override val kind = MediaSourceKind.FileService
    override val info = MediaSourceInfo(arguments.name, arguments.protocol.name, tier = arguments.tier)
    // Protocols do not provide a source-wide name search. The resource library searches its own index.
    override val searchScope = MediaSourceSearchScope.NONE
    override val transport = if (arguments.protocol == FileServiceProtocol.WEBDAV) DownloadTransport.HTTP else DownloadTransport.BYTE_RANGE

    private suspend fun account(): FileServiceCredentials = credentials.get(mediaSourceId) ?: FileServiceCredentials("", "")

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> = emptySizedSource()
    override suspend fun checkConnection(): ConnectionStatus {
        access.stat("", account())
        return ConnectionStatus.SUCCESS
    }

    override suspend fun browse(parent: MediaResourceRef?, pageToken: String?): MediaSourcePage {
        require(pageToken == null) { "This file server returns a complete directory listing" }
        val account = account()
        val parentLocator = parent?.decode(mediaSourceId)
        if (parentLocator != null) {
            require(parentLocator.entry.directory)
            checkScope(parentLocator, account)
        }
        val scope = arguments.scope(account)
        val result = access.list(parentLocator?.entry?.path.orEmpty(), account)
        // A credential edit while listing must not publish records under another account's scope.
        require(scope == arguments.scope(account())) { "File service account changed" }
        return MediaSourcePage(result.map { it.toSourceEntry(mediaSourceId, scope, parent) })
    }

    override suspend fun createMedia(reference: MediaResourceRef, request: MediaFetchRequest): Media {
        val locator = reference.decode(mediaSourceId)
        require(!locator.entry.directory && locator.entry.isVideo())
        require(request.subjectId.isNotEmpty() && request.episodeId.isNotEmpty())
        val parsed = RawTitleParser.getDefault().parse(locator.entry.name)
        return DefaultMedia(
            mediaId = "$mediaSourceId:${reference.resourceId}",
            mediaSourceId = mediaSourceId,
            originalUrl = "",
            download = ResourceLocation.SourceResource(reference),
            originalTitle = locator.entry.name,
            publishedTime = locator.entry.modifiedTimeMillis ?: 0,
            properties = MediaProperties(
                subjectName = request.subjectNames.firstOrNull(), episodeName = request.episodeName,
                subtitleLanguageIds = parsed.subtitleLanguages.map { it.id }, resolution = parsed.resolution?.id.orEmpty(),
                alliance = "", size = locator.entry.size?.bytes ?: FileSize.Unspecified, subtitleKind = parsed.subtitleKind,
            ),
            episodeRange = EpisodeRange.single(request.episodeSort), location = MediaSourceLocation.Online, kind = kind,
        )
    }

    override fun supports(media: Media): Boolean = media.mediaSourceId == mediaSourceId &&
        (media.download as? ResourceLocation.SourceResource)?.reference?.sourceId == mediaSourceId

    override suspend fun resolveResource(reference: MediaResourceRef, episode: EpisodeMetadata): MediaDataProvider<*> {
        val locator = reference.decode(mediaSourceId)
        val account = account()
        checkScope(locator, account)
        require(!locator.entry.directory)
        return when (val access = access) {
            is WebDavFileServiceAccess -> HttpStreamingMediaDataProvider(access.url(locator.entry.path), locator.entry.name, access.headers(account))
            is SmbFileServiceAccess -> FileServiceMediaDataProvider(locator.entry.name) {
                // Credentials are resolved again when the player opens/reopens an input.
                val current = account()
                checkScope(locator, current)
                access.open(locator.entry.path, current)
            }
            else -> error("Unsupported file service playback")
        }
    }

    override suspend fun prepare(request: MediaDownloadAccessRequest, scope: CoroutineScope): PreparedDownloadAccess {
        require(supports(request.media))
        val reference = requireNotNull(request.resourceRef)
        require(reference == (request.media.download as ResourceLocation.SourceResource).reference)
        val locator = reference.decode(mediaSourceId)
        val account = account()
        checkScope(locator, account)
        val stat = access.stat(locator.entry.path, account)
        require(!stat.directory)
        return when (val access = access) {
            is WebDavFileServiceAccess -> PreparedDownloadAccess.Http(
                access.url(stat.path),
                DownloadOptions(
                    headers = access.headers(account) + if (stat.contentIdentity?.startsWith("etag:") == true) {
                        mapOf("If-Match" to stat.contentIdentity.removePrefix("etag:"))
                    } else emptyMap(),
                    contentIdentity = stat.contentIdentity?.let { "${locator.scope}:$it" },
                ),
                refreshable = true,
            )
            is SmbFileServiceAccess -> PreparedDownloadAccess.ByteRange(
                requireNotNull(stat.size) { "SMB did not provide file length" },
                stat.contentIdentity?.let { "${locator.scope}:$it" },
            ) {
                val current = account()
                checkScope(locator, current)
                val file = access.open(stat.path, current)
                if (file.size != stat.size || (stat.contentIdentity != null && stat.contentIdentity != file.contentIdentity)) {
                    file.close()
                    throw FileServiceAccessException("File changed before opening the download")
                }
                file
            }
            else -> error("Unsupported file service download")
        }
    }

    private fun checkScope(locator: FileServiceLocator, credentials: FileServiceCredentials) {
        require(locator.scope == arguments.scope(credentials)) { "Resource belongs to a different file service connection or account" }
    }

    override fun close() = access.close()

    class Factory(private val credentials: FileServiceCredentialProvider) : MediaSourceFactory {
        override val factoryId = FactoryId
        override val allowMultipleInstances = true
        override val info = MediaSourceInfo("WebDAV / SMB")

        @OptIn(UnsafeScopedHttpClientApi::class)
        override fun create(mediaSourceId: String, config: MediaSourceConfig, client: ScopedHttpClient): MediaSource {
            val arguments = requireNotNull(config.deserializeArgumentsOrNull(FileServiceArguments.serializer())) { "File service configuration is required" }
            val access = when (arguments.protocol) {
                FileServiceProtocol.WEBDAV -> WebDavFileServiceAccess(arguments.endpoint, client.borrowForever().client)
                FileServiceProtocol.SMB -> createSmbFileServiceAccess(arguments.endpoint, arguments.port, arguments.share, arguments.root)
            }
            return FileServiceMediaSource(mediaSourceId, arguments, credentials, access)
        }
    }
}

@Serializable
private data class FileServiceLocator(val scope: String, val entry: FileServiceEntry) {
    val id: String get() = Json.encodeToString(listOf(scope, entry.path))
}

private val referenceJson = Json { ignoreUnknownKeys = true }

private fun MediaResourceRef.decode(sourceId: String): FileServiceLocator {
    require(this.sourceId == sourceId && version == 1)
    return referenceJson.decodeFromString<FileServiceLocator>(locator).also {
        checkedRelativePath(it.entry.path)
        require(it.id == resourceId)
    }
}

private fun FileServiceArguments.scope(credentials: FileServiceCredentials): String =
    Json.encodeToString(listOf(protocol.name, endpoint, port.toString(), share, root, credentials.username, credentials.domain))
        .encodeToByteString().digest(DigestAlgorithm.SHA256).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

private fun FileServiceEntry.isVideo(): Boolean = !directory &&
    (mimeType?.startsWith("video/") == true || name.substringAfterLast('.', "").lowercase() in DroppedFileMedia.VIDEO_EXTENSIONS)

private fun FileServiceEntry.toSourceEntry(sourceId: String, scope: String, parent: MediaResourceRef?): MediaSourceEntry {
    val locator = FileServiceLocator(scope, this)
    return MediaSourceEntry(
        MediaResourceRef(sourceId, locator.id, referenceJson.encodeToString(locator)), name,
        when { directory -> MediaSourceEntryKind.DIRECTORY; isVideo() -> MediaSourceEntryKind.VIDEO; else -> MediaSourceEntryKind.FILE },
        size = size, modifiedTimeMillis = modifiedTimeMillis,
        suggestedEpisodeSort = if (isVideo()) RawTitleParser.getDefault().parse(name).episodeRange?.knownSorts?.singleOrNull() else null,
        parent = parent,
    )
}
