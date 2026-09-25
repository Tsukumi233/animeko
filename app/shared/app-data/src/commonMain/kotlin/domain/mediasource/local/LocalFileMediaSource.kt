package me.him188.ani.app.domain.mediasource.local

import kotlinx.serialization.Serializable
import me.him188.ani.app.domain.media.DroppedFileMedia
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.app.domain.media.resolver.MediaSourcePlaybackCapability
import me.him188.ani.app.domain.mediasource.codec.DefaultMediaSourceCodec
import me.him188.ani.app.domain.mediasource.codec.DontForgetToRegisterCodec
import me.him188.ani.app.domain.mediasource.codec.MediaSourceArguments
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.paging.emptySizedSource
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaFetchRequest
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
import me.him188.ani.utils.ktor.ScopedHttpClient

@OptIn(DontForgetToRegisterCodec::class)
@Serializable
data class LocalFileMediaSourceArguments(
    override val name: String = "本地文件",
    override val tier: MediaSourceTier = MediaSourceTier.Fallback,
) : MediaSourceArguments

object LocalFileMediaSourceCodec : DefaultMediaSourceCodec<LocalFileMediaSourceArguments>(
    LocalFileMediaSource.FactoryId, LocalFileMediaSourceArguments::class, 1, LocalFileMediaSourceArguments.serializer(),
)

/** 只浏览用户添加的根；未关联文件不进入自动选源。 */
class LocalFileMediaSource(
    override val mediaSourceId: String,
    private val access: LocalResourceAccess,
    private val roots: suspend (String) -> List<MediaResourceRef>,
    arguments: LocalFileMediaSourceArguments = LocalFileMediaSourceArguments(),
) : MediaSource, MediaSourceBrowser, MediaSourceResourceFactory, MediaSourcePlaybackCapability {
    companion object { val FactoryId = FactoryId("local-files") }
    override val kind = MediaSourceKind.LocalFile
    override val location = MediaSourceLocation.Local
    override val info = MediaSourceInfo(arguments.name, "用户选择的本地文件和目录", tier = arguments.tier)
    override val searchScope = MediaSourceSearchScope.CURRENT_CONTAINER
    override suspend fun fetch(query: MediaFetchRequest) = emptySizedSource()
    override suspend fun checkConnection() = ConnectionStatus.SUCCESS

    private fun checkReference(reference: MediaResourceRef) {
        require(reference.sourceId == mediaSourceId && reference.version == 1)
        require(reference.resourceId == reference.locator) { "Invalid local resource identity" }
    }

    suspend fun entry(uri: String): MediaSourceEntry = access.stat(uri).toEntry()

    override suspend fun browse(parent: MediaResourceRef?, pageToken: String?): MediaSourcePage {
        require(pageToken == null)
        val entries = if (parent == null) {
            roots(mediaSourceId).map { reference ->
                checkReference(reference)
                access.stat(reference.locator).toEntry()
            }
        } else {
            checkReference(parent)
            access.list(parent.locator).map { it.toEntry(parent) }
        }
        return MediaSourcePage(entries.sortedWith(compareBy({ !it.kind.isContainer }, { it.name })))
    }

    override suspend fun search(keyword: String, parent: MediaResourceRef?, pageToken: String?): MediaSourcePage =
        browse(parent, pageToken).let { page -> page.copy(entries = page.entries.filter { it.name.contains(keyword, true) }) }

    override suspend fun createMedia(reference: MediaResourceRef, request: MediaFetchRequest): Media {
        checkReference(reference)
        val entry = access.stat(reference.locator)
        require(!entry.isDirectory && entry.name.substringAfterLast('.', "").lowercase() in DroppedFileMedia.VIDEO_EXTENSIONS)
        require(request.subjectId.toIntOrNull()?.let { it > 0 } == true && request.episodeId.toIntOrNull()?.let { it > 0 } == true)
        val parsed = RawTitleParser.getDefault().parse(entry.name)
        return DefaultMedia(
            mediaId = "$mediaSourceId:${reference.resourceId}",
            mediaSourceId = mediaSourceId,
            originalUrl = reference.locator,
            download = ResourceLocation.SourceResource(reference),
            originalTitle = entry.name,
            publishedTime = entry.modifiedMillis ?: 0,
            properties = MediaProperties(
                subjectName = request.subjectNames.firstOrNull(), episodeName = request.episodeName,
                subtitleLanguageIds = parsed.subtitleLanguages.map { it.id },
                resolution = parsed.resolution?.id.orEmpty(), alliance = "",
                size = entry.size?.bytes ?: FileSize.Unspecified, subtitleKind = parsed.subtitleKind,
            ),
            episodeRange = EpisodeRange.single(request.episodeSort), location = location, kind = kind,
        )
    }

    override suspend fun resolveResource(reference: MediaResourceRef, episode: EpisodeMetadata): MediaDataProvider<*> {
        checkReference(reference)
        return access.createMediaDataProvider(reference.locator)
    }

    private fun LocalResourceEntry.toEntry(parent: MediaResourceRef? = null): MediaSourceEntry {
        val isVideo = !isDirectory && name.substringAfterLast('.', "").lowercase() in DroppedFileMedia.VIDEO_EXTENSIONS
        return MediaSourceEntry(
            MediaResourceRef(mediaSourceId, uri), name,
            when { isDirectory -> MediaSourceEntryKind.DIRECTORY; isVideo -> MediaSourceEntryKind.VIDEO; else -> MediaSourceEntryKind.FILE },
            size, modifiedMillis,
            if (isVideo) RawTitleParser.getDefault().parse(name).episodeRange?.knownSorts?.singleOrNull() else null,
            parent,
        )
    }

    override fun close() = Unit

    class Factory(private val access: LocalResourceAccess, private val roots: suspend (String) -> List<MediaResourceRef>) : MediaSourceFactory {
        override val factoryId = FactoryId
        override val allowMultipleInstances = true
        override val info = MediaSourceInfo("本地文件", "用户选择的本地文件和目录")
        override fun create(mediaSourceId: String, config: MediaSourceConfig, client: ScopedHttpClient): MediaSource =
            LocalFileMediaSource(mediaSourceId, access, roots,
                config.deserializeArgumentsOrNull(LocalFileMediaSourceArguments.serializer()) ?: LocalFileMediaSourceArguments())
    }
}
