/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.data.repository.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.dao.LibraryEpisodeBindingEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryResourceEntity
import me.him188.ani.app.data.persistent.database.dao.ResourceLibraryDao
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaAssociation
import me.him188.ani.datasources.api.unwrapCached
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.platform.Uuid

data class ResourceAssociationInput(
    val entry: MediaSourceEntry,
    val subjectId: Int,
    val episodeId: Int,
    val media: Media,
    val selectedFilePath: String? = null,
)

/** 保存明确的资源归属；不改变收藏状态、播放历史或全局选源偏好。 */
class ResourceLibraryRepository(
    val dao: ResourceLibraryDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val writes = Mutex()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    val resources = dao.resources()
    val bindings = dao.bindings()

    suspend fun index(entry: MediaSourceEntry): LibraryResourceEntity = writes.withLock {
        val resource = entry.toEntity(dao.findResource(entry.reference.sourceId, entry.reference.resourceId)?.id)
        dao.upsertResource(resource)
        resource
    }

    suspend fun indexForScan(rootId: String, token: String, entry: MediaSourceEntry): Boolean = writes.withLock {
        val resource = entry.toEntity(dao.findResource(entry.reference.sourceId, entry.reference.resourceId)?.id)
        dao.recordScanEntry(rootId, token, resource)
    }

    suspend fun associate(
        entry: MediaSourceEntry,
        subjectId: Int,
        episodeId: Int,
        media: Media,
        selectedFilePath: String? = null,
    ): LibraryResourceEntity = writes.withLock {
        require(subjectId > 0 && episodeId > 0) { "A resource must be associated with an existing episode" }
        require(media.mediaSourceId == entry.reference.sourceId) { "Resource source does not match media" }
        val resource = entry.toEntity(dao.findResource(entry.reference.sourceId, entry.reference.resourceId)?.id)
        dao.confirmBinding(
            resource,
            LibraryEpisodeBindingEntity(
                resource.id, resource.sourceId, subjectId, episodeId,
                json.encodeToString(Media.serializer(), media), selectedFilePath,
            ),
        )
        mutableRevision.update { it + 1 }
        resource
    }

    suspend fun removeBinding(resourceId: String, subjectId: Int, episodeId: Int) = writes.withLock {
        dao.removeBinding(resourceId, subjectId, episodeId)
        mutableRevision.update { it + 1 }
    }

    /** 调用方先完成条目与剧集缓存，确认整批后一次提交，不暴露半批关联。 */
    suspend fun associateBatch(
        inputs: List<ResourceAssociationInput>,
        replaceFileBindings: Boolean = false,
    ) = writes.withLock {
        require(inputs.all { it.subjectId > 0 && it.episodeId > 0 && it.media.mediaSourceId == it.entry.reference.sourceId })
        val resources = LinkedHashMap<Pair<String, String>, LibraryResourceEntity>()
        val bindings = inputs.map { input ->
            val reference = input.entry.reference
            val key = reference.sourceId to reference.resourceId
            val resource = resources[key] ?: input.entry.toEntity(
                dao.findResource(reference.sourceId, reference.resourceId)?.id,
            ).also { resources[key] = it }
            LibraryEpisodeBindingEntity(resource.id, resource.sourceId, input.subjectId, input.episodeId,
                json.encodeToString(Media.serializer(), input.media), input.selectedFilePath)
        }
        dao.confirmBindings(resources.values.toList(), bindings, replaceFileBindings)
        mutableRevision.update { it + 1 }
    }

    suspend fun removeResource(resourceId: String) = writes.withLock {
        dao.removeResource(resourceId)
        mutableRevision.update { it + 1 }
    }

    fun decodeMedia(binding: LibraryEpisodeBindingEntity): Media = json.decodeFromString(binding.mediaJson)
    fun decodeReference(resource: LibraryResourceEntity): MediaResourceRef = json.decodeFromString(resource.referenceJson)

    /** 关联以条目为单位返回，切集共享同一批候选；缺失资源保留候选并由解析器报告错误。 */
    suspend fun candidates(sourceId: String, request: MediaFetchRequest): List<Media> {
        val subjectId = request.subjectId?.toIntOrNull() ?: return emptyList()
        val stored = dao.bindingsForSubject(sourceId, subjectId).first()
        return stored.groupBy { decodeMedia(it).mediaId }.values.map { bindings ->
            val first = bindings.first()
            val media = decodeMedia(first).unwrapCached()
            val resource = dao.findResource(first.resourceId)
            val location = if (media.download is ResourceLocation.SourceResource && resource != null) {
                ResourceLocation.SourceResource(decodeReference(resource))
            } else media.download
            media.copy(
                download = location,
                episodeRange = EpisodeRange.combined(bindings.mapNotNull { binding ->
                    request.episodes.find { it.episodeId == binding.episodeId.toString() }?.sort
                        ?.let(EpisodeRange::single)
                        ?: decodeMedia(binding).episodeRange
                }),
                association = MediaAssociation(
                    subjectId.toString(),
                    bindings.map { it.episodeId.toString() }.distinct(),
                    bindings.mapNotNull { binding ->
                        binding.selectedFilePath?.let { binding.episodeId.toString() to it }
                    }.toMap(),
                ),
            )
        }
    }

    private fun MediaSourceEntry.toEntity(existingId: String?) = LibraryResourceEntity(
        id = existingId ?: Uuid.randomString(),
        sourceId = reference.sourceId,
        resourceKey = reference.resourceId,
        referenceJson = json.encodeToString(reference),
        name = name,
        entryKind = kind.name,
        size = size,
        modifiedTimeMillis = modifiedTimeMillis,
    )
}
