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
import kotlinx.serialization.Serializable
import me.him188.ani.app.data.persistent.database.dao.LibraryEpisodeBindingEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryMatchSuggestionEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryResourceEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.data.persistent.database.dao.ResourceLibraryDao
import me.him188.ani.app.domain.mediasource.library.ResourceAssociationPreviewBuilder
import me.him188.ani.app.domain.mediasource.library.ResourcePreviewInput
import me.him188.ani.app.domain.mediasource.library.StoredResourceMatchSuggestion
import me.him188.ani.app.domain.mediasource.library.StoredScanMatchSuggestions
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaAssociation
import me.him188.ani.datasources.api.unwrapCached
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
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

data class ResourceIgnoredInput(val entry: MediaSourceEntry, val selectedFilePath: String? = null) {
    init {
        require((entry.kind == MediaSourceEntryKind.VIDEO && selectedFilePath == null) ||
                (entry.kind == MediaSourceEntryKind.TORRENT && !selectedFilePath.isNullOrBlank()))
    }
}

/** null 代表独立视频，非空路径代表 BT 发布中的精确文件。 */
@Serializable
data class LibraryIgnoredFiles(val paths: Set<String?> = emptySet(), val version: Int = 1) {
    init { require(version == 1) }
}

data class IndexedLibraryResource(val resource: LibraryResourceEntity, val selectedFilePath: String? = null)

@Serializable
private data class IndexedSuggestionFile(val selectedFilePath: String? = null)

@Serializable
private data class IndexedSuggestionFiles(
    val paths: Set<String?> = emptySet(),
    val rows: List<IndexedSuggestionFile> = emptyList(),
    val version: Int = 1,
) {
    init { require(version == 1) }
}

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

    suspend fun commitScanMatches(
        root: LibraryScanRootEntity,
        expectedBindings: List<LibraryEpisodeBindingEntity>,
        expectedSuggestions: List<LibraryMatchSuggestionEntity>,
        expectedResources: List<LibraryResourceEntity>,
        inputs: List<ResourceAssociationInput>,
        suggestions: List<LibraryMatchSuggestionEntity>,
        completedMillis: Long,
        ruleError: String?,
    ): Boolean = writes.withLock {
        val resources = expectedResources.associateBy { it.sourceId to it.resourceKey }
        val bindings = inputs.map { input ->
            val resource = resources.getValue(input.entry.reference.sourceId to input.entry.reference.resourceId)
            LibraryEpisodeBindingEntity(resource.id, resource.sourceId, input.subjectId, input.episodeId,
                json.encodeToString(Media.serializer(), input.media), input.selectedFilePath)
        }
        dao.completeScanWithMatches(root, expectedBindings, expectedSuggestions, expectedResources, bindings,
            suggestions, completedMillis, ruleError).also { committed ->
            if (committed) mutableRevision.update { it + 1 }
        }
    }

    suspend fun associate(
        entry: MediaSourceEntry,
        subjectId: Int,
        episodeId: Int,
        media: Media,
        selectedFilePath: String? = null,
    ): LibraryResourceEntity = associateBatch(
        listOf(ResourceAssociationInput(entry, subjectId, episodeId, media, selectedFilePath)),
    ).single()

    suspend fun removeBinding(resourceId: String, subjectId: Int, episodeId: Int) = writes.withLock {
        dao.removeBinding(resourceId, subjectId, episodeId)
        mutableRevision.update { it + 1 }
    }

    /** 调用方先完成条目与剧集缓存，确认整批后一次提交，不暴露半批关联。 */
    suspend fun associateBatch(
        inputs: List<ResourceAssociationInput>,
        replaceFileBindings: Boolean = false,
        ignored: List<ResourceIgnoredInput> = emptyList(),
    ) = writes.withLock {
        require(inputs.all { it.subjectId > 0 && it.episodeId > 0 && it.media.mediaSourceId == it.entry.reference.sourceId })
        val confirmedFiles = inputs.map { Triple(it.entry.reference.sourceId, it.entry.reference.resourceId, it.selectedFilePath) }.toSet()
        require(ignored.none { Triple(it.entry.reference.sourceId, it.entry.reference.resourceId, it.selectedFilePath) in confirmedFiles })
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
        for (input in ignored) {
            val reference = input.entry.reference
            val key = reference.sourceId to reference.resourceId
            if (key !in resources) resources[key] = input.entry.toEntity(dao.findResource(reference.sourceId, reference.resourceId)?.id)
        }
        val suggestions = resources.values.mapNotNull { resource ->
            val previousSuggestion = dao.findSuggestion(resource.id)
            val previous = previousSuggestion?.let(::decodeIgnoredFiles).orEmpty()
            val catalog = if (resource.entryKind == MediaSourceEntryKind.TORRENT.name) previousSuggestion?.let {
                json.decodeFromString<StoredScanMatchSuggestions>(it.suggestionJson)
            } else null
            val confirmed = bindings.filter { it.resourceId == resource.id }.map { it.selectedFilePath }.toSet()
            val skipped = ignored.filter { it.entry.reference.sourceId == resource.sourceId && it.entry.reference.resourceId == resource.resourceKey }
                .map { it.selectedFilePath }
            val paths = previous - confirmed + skipped
            if (catalog != null && (catalog.rows.isNotEmpty() || catalog.catalogComplete)) {
                LibraryMatchSuggestionEntity(resource.id, json.encodeToString(catalog.copy(paths = paths)), ignored = paths.isNotEmpty())
            } else if (paths.isEmpty()) null else LibraryMatchSuggestionEntity(resource.id, json.encodeToString(LibraryIgnoredFiles(paths)), ignored = true)
        }
        dao.confirmBindings(resources.values.toList(), bindings, replaceFileBindings, suggestions)
        mutableRevision.update { it + 1 }
        resources.values.toList()
    }

    /** A successful metadata listing records exact video paths without changing bindings or ignored decisions. */
    suspend fun recordTorrentFiles(resourceId: String, reference: MediaResourceRef, paths: List<String>) {
        require(paths.all { it.isNotBlank() } && paths.distinct().size == paths.size)
        writes.withLock {
            val resource = requireNotNull(dao.findResource(resourceId)) { "Resource was removed" }
            require(resource.entryKind == MediaSourceEntryKind.TORRENT.name && decodeReference(resource) == reference) { "Resource changed during metadata listing" }
            val previous = dao.findSuggestion(resourceId)
            val stored = previous?.let { json.decodeFromString<StoredScanMatchSuggestions>(it.suggestionJson) }
            val existingRows = stored?.rows.orEmpty().associateBy { it.selectedFilePath }
            val entry = MediaSourceEntry(reference, resource.name, MediaSourceEntryKind.TORRENT, resource.size, resource.modifiedTimeMillis)
            val rows = ResourceAssociationPreviewBuilder().build(paths.map { ResourcePreviewInput(entry, it) }, emptyList()).map {
                existingRows[it.input.selectedFilePath] ?: StoredResourceMatchSuggestion(it.input.selectedFilePath, it.titleSuggestions, it.episodeSort, it.status.name, it.targets, it.input.parentReference)
            }
            val ignored = previous?.let(::decodeIgnoredFiles).orEmpty()
            val payload = StoredScanMatchSuggestions(ignored, rows, catalogComplete = true)
            check(dao.storeTorrentCatalog(resource, previous, LibraryMatchSuggestionEntity(resourceId, json.encodeToString(payload), ignored.isNotEmpty()))) {
                "Resource decisions changed during metadata listing"
            }
        }
    }

    /** Source-wide name search over saved index records; it never queries or claims to enumerate a server. */
    suspend fun searchIndexed(sourceId: String, query: String): List<IndexedLibraryResource> {
        if (query.isBlank()) return emptyList()
        val resources = dao.resourcesForSource(sourceId).first()
        val bindings = dao.bindingsForSourceSnapshot(sourceId).groupBy { it.resourceId }
        val suggestions = dao.suggestionsForSourceSnapshot(sourceId).associateBy { it.resourceId }
        return resources.flatMap { resource ->
            val nameMatches = resource.name.contains(query, ignoreCase = true)
            when (resource.entryKind) {
                MediaSourceEntryKind.VIDEO.name -> if (nameMatches) listOf(IndexedLibraryResource(resource)) else emptyList()
                MediaSourceEntryKind.TORRENT.name -> {
                    val stored = suggestions[resource.id]?.let { json.decodeFromString<IndexedSuggestionFiles>(it.suggestionJson) }
                    val paths = (bindings[resource.id].orEmpty().map { it.selectedFilePath } +
                            stored?.paths.orEmpty() + stored?.rows.orEmpty().map { it.selectedFilePath })
                        .filterNotNull().distinct()
                    if (paths.isEmpty() && nameMatches) listOf(IndexedLibraryResource(resource))
                    else paths.filter { nameMatches || it.contains(query, ignoreCase = true) }.map { IndexedLibraryResource(resource, it) }
                }
                else -> emptyList()
            }
        }
    }

    suspend fun removeResource(resourceId: String) = writes.withLock {
        dao.removeResource(resourceId)
        mutableRevision.update { it + 1 }
    }

    fun decodeMedia(binding: LibraryEpisodeBindingEntity): Media = json.decodeFromString(binding.mediaJson)
    fun decodeReference(resource: LibraryResourceEntity): MediaResourceRef = json.decodeFromString(resource.referenceJson)

    fun decodeIgnoredFiles(suggestion: LibraryMatchSuggestionEntity): Set<String?> =
        if (!suggestion.ignored) emptySet() else json.decodeFromString<LibraryIgnoredFiles>(suggestion.suggestionJson).paths

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
