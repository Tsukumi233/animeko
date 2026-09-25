package me.him188.ani.app.ui.resource

import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.dao.LibraryEpisodeBindingEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryMatchSuggestionEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryResourceEntity
import me.him188.ani.app.domain.mediasource.library.ResourceEpisodeTarget
import me.him188.ani.app.domain.mediasource.library.ResourcePreviewStatus
import me.him188.ani.app.domain.mediasource.library.StoredScanMatchSuggestions
import me.him188.ani.datasources.api.source.MediaSourceEntryKind

internal enum class ResourcePendingStatus { UNRECOGNIZED, SUGGESTED, AMBIGUOUS, IGNORED, UNKNOWN_FILES, INVALID_STATE }

internal data class ResourcePendingFile(
    val resource: LibraryResourceEntity,
    val filePath: String?,
    val status: ResourcePendingStatus,
) {
    val key: Pair<String, String?> get() = resource.id to filePath
}

/** Confirmed file identities win over stored suggestions; a release is never treated as one video. */
internal fun pendingLibraryFiles(
    resources: List<LibraryResourceEntity>,
    bindings: List<LibraryEpisodeBindingEntity>,
    suggestions: List<LibraryMatchSuggestionEntity>,
): List<ResourcePendingFile> {
    val json = Json { ignoreUnknownKeys = true }
    val byResource = suggestions.associateBy { it.resourceId }
    val confirmed = bindings.groupBy { it.resourceId }
    return resources.flatMap { resource ->
        val saved = byResource[resource.id]
        val payload = try { saved?.let { json.decodeFromString<StoredScanMatchSuggestions>(it.suggestionJson) } }
        catch (_: IllegalArgumentException) {
            return@flatMap listOf(ResourcePendingFile(resource, null, ResourcePendingStatus.INVALID_STATE))
        }
        val bound = confirmed[resource.id].orEmpty().map { it.selectedFilePath }.toSet()
        val ignored = if (saved?.ignored == true) payload?.paths.orEmpty() else emptySet()
        fun row(path: String?): ResourcePendingFile {
            val suggestion = payload?.rows?.singleOrNull { it.selectedFilePath == path }
            val status = when {
                path in ignored -> ResourcePendingStatus.IGNORED
                suggestion?.status == ResourcePreviewStatus.AMBIGUOUS.name -> ResourcePendingStatus.AMBIGUOUS
                suggestion?.status in setOf(ResourcePreviewStatus.SUGGESTED.name, ResourcePreviewStatus.AUTO_ASSIGNABLE.name) -> ResourcePendingStatus.SUGGESTED
                else -> ResourcePendingStatus.UNRECOGNIZED
            }
            return ResourcePendingFile(resource, path, status)
        }
        when (resource.entryKind) {
            MediaSourceEntryKind.VIDEO.name -> if (null in bound) emptyList() else listOf(row(null))
            MediaSourceEntryKind.TORRENT.name -> {
                val knownPaths = (payload?.rows.orEmpty().map { it.selectedFilePath } + ignored + bound).filterNotNull().filter { it.isNotBlank() }.distinct()
                knownPaths.filterNot { it in bound }.map { row(it) } +
                        if (payload?.catalogComplete == true && knownPaths.isNotEmpty()) emptyList() else listOf(ResourcePendingFile(resource, null, ResourcePendingStatus.UNKNOWN_FILES))
            }
            else -> emptyList()
        }
    }
}

/** Only one unambiguous suggestion can prefill a confirmation; skipped files retain their decision. */
internal fun suggestedLibraryTarget(saved: LibraryMatchSuggestionEntity?, path: String?): ResourceEpisodeTarget? {
    if (saved == null) return null
    val payload = Json { ignoreUnknownKeys = true }.decodeFromString<StoredScanMatchSuggestions>(saved.suggestionJson)
    if (saved.ignored && path in payload.paths) return null
    val row = payload.rows.singleOrNull { it.selectedFilePath == path } ?: return null
    if (row.status !in setOf(ResourcePreviewStatus.SUGGESTED.name, ResourcePreviewStatus.AUTO_ASSIGNABLE.name)) return null
    return row.targets.singleOrNull()
}
