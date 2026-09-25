package me.him188.ani.datasources.dmhy

import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceBrowser
import me.him188.ani.datasources.api.source.MediaSourceResourceFactory
import me.him188.ani.datasources.api.source.MediaSourceSearchScope
import me.him188.ani.datasources.api.source.MediaSourcePage
import me.him188.ani.datasources.api.source.TorrentMediaSourceReferences
import me.him188.ani.datasources.api.source.toOnlineMedia
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.datasources.api.topic.TopicCategory
import me.him188.ani.datasources.api.topic.guessTorrentFromUrl
import me.him188.ani.datasources.api.topic.titles.toTopicDetails
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.DownloadSearchQuery
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceFactory
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.TopicMediaSource
import me.him188.ani.datasources.api.topic.Topic
import me.him188.ani.datasources.dmhy.impl.DmhyPagedSourceImpl
import me.him188.ani.datasources.dmhy.impl.protocol.Network
import me.him188.ani.utils.ktor.ScopedHttpClient
import me.him188.ani.utils.logging.error
import kotlin.coroutines.cancellation.CancellationException

class DmhyMediaSource(
    private val client: ScopedHttpClient,
) : TopicMediaSource(), MediaSourceBrowser, MediaSourceResourceFactory {
    override val supportsRootBrowse: Boolean get() = false
    override val searchScope: MediaSourceSearchScope get() = MediaSourceSearchScope.SOURCE
    override suspend fun browse(parent: MediaResourceRef?, pageToken: String?): MediaSourcePage =
        throw UnsupportedOperationException("Search for a torrent release first")

    override suspend fun search(keyword: String, parent: MediaResourceRef?, pageToken: String?): MediaSourcePage {
        require(parent == null)
        val page = pageToken?.toIntOrNull() ?: if (pageToken == null) 1 else error("Invalid page token")
        require(page > 0)
        val response = network.list(page = page, keyword = keyword)
        val entries = response.list.mapNotNull { topic ->
            val release = Topic(
                topicId = topic.id, publishedTimeMillis = topic.publishedTimeMillis,
                category = TopicCategory.ANIME, rawTitle = topic.rawTitle, commentsCount = topic.commentsCount,
                downloadLink = ResourceLocation.guessTorrentFromUrl(topic.magnetLink) ?: return@mapNotNull null,
                size = topic.size,
                alliance = topic.alliance?.name ?: topic.rawTitle.substringBeforeLast(']').substringAfterLast('['),
                author = topic.author, details = topic.details?.toTopicDetails(), originalLink = topic.link,
            )
            TorrentMediaSourceReferences.entry(release.toOnlineMedia(mediaSourceId))
        }
        // The protocol parser has no next-page metadata; an empty raw page terminates traversal.
        return MediaSourcePage(entries, if (response.list.isNotEmpty()) (page + 1).toString() else null)
    }

    override suspend fun createMedia(reference: MediaResourceRef, request: MediaFetchRequest): Media =
        TorrentMediaSourceReferences.decode(reference, mediaSourceId)
    class Factory : MediaSourceFactory {
        override val factoryId: FactoryId = FactoryId(ID)
        override val info: MediaSourceInfo get() = INFO
        override fun create(
            mediaSourceId: String,
            config: MediaSourceConfig,
            client: ScopedHttpClient
        ): MediaSource = DmhyMediaSource(client)
    }

    companion object {
        const val ID = "dmhy"
        val INFO = MediaSourceInfo(
            displayName = "動漫花園",
            description = "动漫资源聚合网站",
            iconUrl = "https://dmhy.org/favicon.ico",
            iconResourceId = "dmhy.png",
        )
    }

    override val info: MediaSourceInfo get() = INFO
    private val network by lazy {
        Network(client)
    }

    override val mediaSourceId: String get() = ID

    override suspend fun checkConnection(): ConnectionStatus {
        return try {
            network.list()
            ConnectionStatus.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to check connection" }
            ConnectionStatus.FAILED
        }
    }

    override suspend fun startSearch(query: DownloadSearchQuery): SizedSource<Topic> {
        return DmhyPagedSourceImpl(query, network)
    }
}
