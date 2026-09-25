package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.flow.first
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.topic.ResourceLocation

/** 来源在使用时解析长期引用；访问令牌和临时链接不写入资源索引。 */
interface MediaSourcePlaybackCapability {
    suspend fun resolveResource(reference: MediaResourceRef, episode: EpisodeMetadata): MediaDataProvider<*>
}

class SourceResourceMediaResolver(private val sourceManager: MediaSourceManager) : MediaResolver {
    override fun supports(media: Media): Boolean = media.download is ResourceLocation.SourceResource

    override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<*> {
        val reference = (media.download as? ResourceLocation.SourceResource)?.reference
            ?: throw UnsupportedMediaException(media)
        val source = sourceManager.allInstances.first().firstOrNull {
            it.mediaSourceId == reference.sourceId
        }?.source ?: error("资源来源不可用，请检查来源设置")
        val playback = source as? MediaSourcePlaybackCapability ?: throw UnsupportedMediaException(media)
        return playback.resolveResource(reference, episode)
    }
}
