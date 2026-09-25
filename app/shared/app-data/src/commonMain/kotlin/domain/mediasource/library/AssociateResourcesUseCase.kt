/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.mediasource.library

import kotlinx.coroutines.flow.first
import me.him188.ani.app.data.repository.media.ResourceAssociationInput
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.data.repository.media.ResourceIgnoredInput
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.domain.media.fetch.create
import me.him188.ani.datasources.api.MediaAssociation
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.source.MediaSourceResourceFactory
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.unwrapCached

/** 用户在对应预览中确认的一项；BT 合集引用保持指向原发布，文件路径单独记录。 */
data class ResourceEpisodeSelection(
    val entry: MediaSourceEntry,
    val subjectId: Int,
    val episodeId: Int,
    val selectedFilePath: String? = null,
)

/**
 * 确认关联的公共入口，供资源浏览、当前集选文件与拖放使用。
 * 先准备整批条目元数据和候选，全部成功后一次保存；不修改观看进度或选源偏好。
 */
class AssociateResourcesUseCase(
    private val subjects: SubjectCollectionRepository,
    private val library: ResourceLibraryRepository,
    private val factories: suspend () -> Map<String, MediaSourceResourceFactory>,
) {
    /** 返回的候选携带确认的身份，可交给当前剧集的 MediaSelector.select。 */
    suspend operator fun invoke(
        selections: List<ResourceEpisodeSelection>,
        ignored: List<ResourceIgnoredInput> = emptyList(),
    ): List<ResourceAssociationInput> {
        if (selections.isEmpty()) {
            if (ignored.isNotEmpty()) library.associateBatch(emptyList(), ignored = ignored)
            return emptyList()
        }
        validate(selections)
        val sources = factories()
        val metadata = selections.map { it.subjectId }.distinct().associateWith { subjectId ->
            subjects.librarySubjectCollectionFlow(subjectId).first()
        }
        val inputs = selections.map { selection ->
            val subject = metadata.getValue(selection.subjectId)
            require(subject.subjectId == selection.subjectId)
            val episode = subject.episodes.singleOrNull { it.episodeId == selection.episodeId }?.episodeInfo
                ?: throw NoSuchElementException("Episode ${selection.episodeId} does not belong to subject ${selection.subjectId}")
            val factory = sources[selection.entry.reference.sourceId]
                ?: throw NoSuchElementException("Source ${selection.entry.reference.sourceId} is unavailable")
            val media = factory.createMedia(
                selection.entry.reference,
                MediaFetchRequest.create(subject.subjectInfo, episode, subject.episodes.map { it.episodeInfo }),
            ).unwrapCached().copy(
                episodeRange = EpisodeRange.single(episode.sort),
                association = MediaAssociation(
                    selection.subjectId.toString(),
                    listOf(selection.episodeId.toString()),
                    selection.selectedFilePath?.let { mapOf(selection.episodeId.toString() to it) }.orEmpty(),
                ),
            )
            ResourceAssociationInput(selection.entry, selection.subjectId, selection.episodeId, media, selection.selectedFilePath)
        }
        library.associateBatch(inputs, replaceFileBindings = true, ignored = ignored)
        return inputs
    }

    private fun validate(selections: List<ResourceEpisodeSelection>) {
        for (selection in selections) {
            require(selection.subjectId > 0 && selection.episodeId > 0)
            when (selection.entry.kind) {
                MediaSourceEntryKind.VIDEO -> require(selection.selectedFilePath == null)
                MediaSourceEntryKind.TORRENT -> require(!selection.selectedFilePath.isNullOrBlank()) {
                    "Select an exact torrent file before associating an episode"
                }
                else -> throw IllegalArgumentException("Only video files can be associated with episodes")
            }
        }
        // 一个视频文件只对应一集；一个 BT 发布可用不同文件对应多集。
        require(selections.groupBy { Triple(it.entry.reference.sourceId, it.entry.reference.resourceId, it.selectedFilePath) }
            .values.all { group -> group.map { it.subjectId to it.episodeId }.distinct().size == 1 }) {
            "A video file cannot represent multiple episodes"
        }
        require(selections.groupBy { Triple(it.entry.reference.sourceId to it.entry.reference.resourceId, it.subjectId, it.episodeId) }
            .values.all { group -> group.map { it.selectedFilePath }.distinct().size == 1 }) {
            "Select one file per episode within a torrent"
        }
    }
}
