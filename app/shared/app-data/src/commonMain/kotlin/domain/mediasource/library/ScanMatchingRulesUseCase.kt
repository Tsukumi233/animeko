/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.mediasource.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.dao.LibraryMatchSuggestionEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaResourceRef

/** Only explicit user confirmation calls confirm; generated previews never save rules. */
class ConfirmScanMatchingRulesUseCase(
    private val library: ResourceLibraryRepository,
    private val subjects: SubjectCollectionRepository,
) {
    suspend fun confirm(rootId: String, rules: ConfirmedResourceMatchingRules) {
        val root = requireNotNull(library.dao.findScanRoot(rootId)) { "Scan root does not exist" }
        val reference = Json.decodeFromString<MediaResourceRef>(root.referenceJson)
        require(reference.sourceId == root.sourceId)
        require(rules.rules.isNotEmpty())
        require(rules.rules.all { it.sourceId == reference.sourceId && it.parentResourceId == reference.resourceId &&
            it.parentLocator == reference.locator && it.parentVersion == reference.version }) { "Rules must match the exact scan root" }
        for ((subjectId, mappings) in rules.rules.flatMap { it.mappings }.groupBy { it.target.subjectId }) {
            val subject = subjects.librarySubjectCollectionFlow(subjectId).first()
            require(subject.subjectId == subjectId && mappings.all { mapping -> subject.episodes.any { it.episodeId == mapping.target.episodeId } }) {
                "Rule target episode does not belong to its subject"
            }
        }
        check(library.dao.updateMatchingRules(root, rules.encode())) { "Scan root changed during confirmation" }
    }

    suspend fun remove(rootId: String) {
        val root = library.dao.findScanRoot(rootId) ?: return
        check(library.dao.updateMatchingRules(root, null)) { "Scan root changed while removing rules" }
    }
}

@Serializable
data class StoredResourceMatchSuggestion(
    val selectedFilePath: String?,
    val titleSuggestions: List<String>,
    val episodeSort: EpisodeSort?,
    val status: String,
    val targets: List<ResourceEpisodeTarget>,
    val parentReference: MediaResourceRef?,
)

/** paths/version remain compatible with LibraryIgnoredFiles; ignored decisions and suggestions share the row. */
@Serializable
data class StoredScanMatchSuggestions(
    val paths: Set<String?> = emptySet(),
    val rows: List<StoredResourceMatchSuggestion> = emptyList(),
    val version: Int = 1,
) {
    init { require(version == 1) }
}

class ApplyScanMatchingRulesUseCase(
    private val library: ResourceLibraryRepository,
    private val subjects: SubjectCollectionRepository,
    private val associate: AssociateResourcesUseCase,
    private val preview: ResourceAssociationPreviewBuilder = ResourceAssociationPreviewBuilder(),
) {
    /** Called only after enumeration finishes; preparation is outside the guarded Room commit. */
    suspend operator fun invoke(root: LibraryScanRootEntity, inputs: List<ResourcePreviewInput>, completedMillis: Long): Boolean {
        if (library.dao.findScanRoot(root.id) != root) return false
        val resources = library.dao.resourcesForSource(root.sourceId).first()
        val byKey = resources.associateBy { it.resourceKey }
        val byId = resources.associateBy { it.id }
        val bindings = library.dao.bindingsForSourceSnapshot(root.sourceId)
        val suggestions = library.dao.suggestionsForSourceSnapshot(root.sourceId)
        val ignored = suggestions.associate { it.resourceId to library.decodeIgnoredFiles(it) }
        val protected = bindings.mapNotNull { binding -> byId[binding.resourceId]?.let { resource ->
            ProtectedResourceDecision(ResourceFileIdentity(root.sourceId, resource.resourceKey, binding.selectedFilePath),
                ProtectedResourceDecisionKind.CONFIRMED, ResourceEpisodeTarget(binding.subjectId, binding.episodeId))
        } } + ignored.flatMap { (id, paths) -> paths.mapNotNull { path -> byId[id]?.let { resource ->
            ProtectedResourceDecision(ResourceFileIdentity(root.sourceId, resource.resourceKey, path), ProtectedResourceDecisionKind.IGNORED)
        } } }

        var ruleError: String? = null
        var rules: ConfirmedResourceMatchingRules? = null
        var options = emptyList<ResourceEpisodeOption>()
        if (root.matchingRuleJson != null) {
            try {
                rules = ConfirmedResourceMatchingRules.decode(root.matchingRuleJson)
                val reference = Json.decodeFromString<MediaResourceRef>(root.referenceJson)
                require(reference.sourceId == root.sourceId)
                require(rules.rules.isNotEmpty())
                require(rules.rules.all { it.sourceId == root.sourceId && it.parentResourceId == reference.resourceId &&
                    it.parentLocator == reference.locator && it.parentVersion == reference.version })
                options = rules.rules.flatMap { it.mappings }.groupBy { it.target.subjectId }.flatMap { (id, mappings) ->
                    val subject = subjects.librarySubjectCollectionFlow(id).first()
                    require(subject.subjectId == id)
                    mappings.map { mapping ->
                        require(subject.episodes.any { it.episodeId == mapping.target.episodeId })
                        ResourceEpisodeOption(mapping.target, mapping.sort)
                    }
                }.distinct()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ruleError = "匹配规则不可用，请修复或移除规则"
                rules = null
                options = emptyList()
            }
        }
        val rows = preview.build(inputs, options, protected, rules.takeIf { root.lastCompletedMillis != null })
        val automatic = rows.filter { it.status == ResourcePreviewStatus.AUTO_ASSIGNABLE }
        val prepared = associate.prepare(automatic.map { row ->
            val target = row.targets.single()
            ResourceEpisodeSelection(row.input.entry, target.subjectId, target.episodeId, row.input.selectedFilePath)
        })
        val records = rows.groupBy { byKey.getValue(it.input.identity.resourceId).id }.map { (resourceId, resourceRows) ->
            val paths = ignored[resourceId].orEmpty()
            val payload = StoredScanMatchSuggestions(paths, resourceRows.map { row ->
                StoredResourceMatchSuggestion(row.input.selectedFilePath, row.titleSuggestions, row.episodeSort,
                    if (row.status == ResourcePreviewStatus.AUTO_ASSIGNABLE) ResourcePreviewStatus.CONFIRMED.name else row.status.name,
                    row.targets, row.input.parentReference)
            })
            LibraryMatchSuggestionEntity(resourceId, Json.encodeToString(payload), paths.isNotEmpty())
        }
        val expectedResources = inputs.map { byKey.getValue(it.identity.resourceId) }.distinctBy { it.id }
        require(inputs.all { input ->
            val resource = byKey.getValue(input.identity.resourceId)
            library.decodeReference(resource) == input.entry.reference && resource.name == input.entry.name &&
                resource.entryKind == input.entry.kind.name && resource.size == input.entry.size &&
                resource.modifiedTimeMillis == input.entry.modifiedTimeMillis
        }) { "Indexed resource changed during enumeration" }
        return library.commitScanMatches(root, bindings, suggestions, expectedResources, prepared, records, completedMillis, ruleError)
    }
}
