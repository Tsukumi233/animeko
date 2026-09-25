/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.datasources.api.source

import kotlinx.serialization.Serializable
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media

/**
 * 来源内的长期资源引用。[resourceId] 在分页和重复浏览间稳定；[locator] 由来源解释，
 * 可以包含文档 URI、文件路径或资源定位信息，但不包含凭证和短期播放地址。
 */
@Serializable
data class MediaResourceRef(
    val sourceId: String,
    val resourceId: String,
    val locator: String = resourceId,
    val version: Int = 1,
) {
    init {
        require(sourceId.isNotBlank()) { "A resource must belong to a source" }
        require(resourceId.isNotBlank()) { "A resource must have a stable identity" }
        require(version > 0) { "Invalid resource reference version" }
    }
}

@Serializable
enum class MediaSourceEntryKind {
    DIRECTORY, SUBJECT, PLAYLIST, VIDEO, TORRENT, FILE;

    val isContainer: Boolean get() = this == DIRECTORY || this == SUBJECT || this == PLAYLIST || this == TORRENT
    val isVideo: Boolean get() = this == VIDEO
}

/** 来源原有结构中的一项；未识别剧集的文件仍可浏览和手工关联。 */
@Serializable
data class MediaSourceEntry(
    val reference: MediaResourceRef,
    val name: String,
    val kind: MediaSourceEntryKind,
    val size: Long? = null,
    val modifiedTimeMillis: Long? = null,
    val suggestedEpisodeSort: EpisodeSort? = null,
    val parent: MediaResourceRef? = null,
    val subtitle: String? = null,
)

data class MediaSourcePage(
    val entries: List<MediaSourceEntry>,
    val nextPageToken: String? = null,
)

enum class MediaSourceSearchScope {
    NONE, CURRENT_CONTAINER, SOURCE;
}

/**
 * 数据源可选的人工浏览能力。能力缺失与空结果有不同语义。
 * 网络、鉴权和不存在错误直接传播，调用方不得将错误转换为空列表。
 */
interface MediaSourceBrowser {
    val supportsRootBrowse: Boolean get() = true
    val searchScope: MediaSourceSearchScope get() = MediaSourceSearchScope.NONE

    suspend fun browse(parent: MediaResourceRef? = null, pageToken: String? = null): MediaSourcePage

    suspend fun search(
        keyword: String,
        parent: MediaResourceRef? = null,
        pageToken: String? = null,
    ): MediaSourcePage = throw UnsupportedOperationException("This source does not support searching")
}

/** 将用户明确选中的资源转换为候选；调用方负责保存其剧集归属。 */
interface MediaSourceResourceFactory {
    suspend fun createMedia(reference: MediaResourceRef, request: MediaFetchRequest): Media
}
