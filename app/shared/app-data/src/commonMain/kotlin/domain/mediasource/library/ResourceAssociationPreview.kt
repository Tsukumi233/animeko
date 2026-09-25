/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.mediasource.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.him188.ani.app.domain.media.DroppedFileMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.topic.titles.RawTitleParser
import me.him188.ani.datasources.api.topic.titles.parse

@Serializable
data class ResourceFileIdentity(val sourceId: String, val resourceId: String, val selectedFilePath: String? = null)

data class ResourcePreviewInput(
    val entry: MediaSourceEntry,
    val selectedFilePath: String? = null,
    val folderName: String? = null,
    /** Scanners supply the actual directory reference when a provider omits root-entry parent metadata. */
    val parentReference: MediaResourceRef? = entry.parent,
) {
    val identity: ResourceFileIdentity get() = ResourceFileIdentity(entry.reference.sourceId, entry.reference.resourceId, selectedFilePath)
    val fileName: String get() = selectedFilePath?.substringAfterLast('/')?.substringAfterLast('\\') ?: entry.name
}

@Serializable
data class ResourceEpisodeTarget(val subjectId: Int, val episodeId: Int) {
    init { require(subjectId > 0 && episodeId > 0) }
}

/** A caller-provided episode catalog can contain multiple seasons and typed special episodes. */
data class ResourceEpisodeOption(val target: ResourceEpisodeTarget, val sort: EpisodeSort)

@Serializable
enum class ProtectedResourceDecisionKind { CONFIRMED, IGNORED }

/** Persisted bindings, including user corrections, and ignored suggestions take priority over all rules. */
data class ProtectedResourceDecision(
    val identity: ResourceFileIdentity,
    val kind: ProtectedResourceDecisionKind,
    val target: ResourceEpisodeTarget? = null,
) {
    init { require((kind == ProtectedResourceDecisionKind.CONFIRMED) == (target != null)) }
}

@Serializable
data class ConfirmedResourceEpisodeMapping(val sort: EpisodeSort, val target: ResourceEpisodeTarget)

/** Only create and persist this rule after the user confirms its scope and mappings. */
@Serializable
data class ConfirmedResourceMatchingRule(
    val id: String,
    val sourceId: String,
    val parentResourceId: String,
    val mappings: List<ConfirmedResourceEpisodeMapping>,
    /** Empty means the user explicitly accepted all titles within this exact container. */
    val acceptedTitles: Set<String> = emptySet(),
    val parentLocator: String = parentResourceId,
    val parentVersion: Int = 1,
) {
    init {
        require(id.isNotBlank() && sourceId.isNotBlank() && parentResourceId.isNotBlank())
        require(parentVersion > 0)
        require(mappings.isNotEmpty() && mappings.map { it.sort }.distinct().size == mappings.size)
        require(mappings.map { it.target }.distinct().size == mappings.size)
    }
    companion object {
        fun forParent(
            id: String,
            parent: MediaResourceRef,
            mappings: List<ConfirmedResourceEpisodeMapping>,
            acceptedTitles: Set<String> = emptySet(),
        ): ConfirmedResourceMatchingRule = ConfirmedResourceMatchingRule(
            id, parent.sourceId, parent.resourceId, mappings, acceptedTitles, parent.locator, parent.version,
        )
    }
}

/** The versioned payload stored in LibraryScanRoot.matchingRuleJson. Unknown versions must not be applied. */
@Serializable
data class ConfirmedResourceMatchingRules(val version: Int = 1, val rules: List<ConfirmedResourceMatchingRule>) {
    init { require(version == 1 && rules.map { it.id }.distinct().size == rules.size) }
    fun encode(): String = Json.encodeToString(this)
    companion object {
        fun decode(json: String): ConfirmedResourceMatchingRules = Json.decodeFromString(json)
    }
}

enum class ResourcePreviewStatus {
    CONFIRMED, IGNORED, SUGGESTED, AUTO_ASSIGNABLE, AMBIGUOUS, UNRECOGNIZED, MULTIPLE_EPISODES, NOT_VIDEO,
}

data class ResourceAssociationPreviewRow(
    val input: ResourcePreviewInput,
    val titleSuggestions: List<String>,
    val episodeSort: EpisodeSort?,
    val status: ResourcePreviewStatus,
    val targets: List<ResourceEpisodeTarget> = emptyList(),
    val matchedRuleId: String? = null,
)

/** Pure preview calculation. The caller performs explicit confirmation or guarded rule commits separately. */
class ResourceAssociationPreviewBuilder(private val parser: RawTitleParser = RawTitleParser.getDefault()) {
    fun build(
        inputs: List<ResourcePreviewInput>,
        episodeOptions: List<ResourceEpisodeOption>,
        protectedDecisions: List<ProtectedResourceDecision> = emptyList(),
        confirmedRules: ConfirmedResourceMatchingRules? = null,
    ): List<ResourceAssociationPreviewRow> {
        require(inputs.map { it.identity }.distinct().size == inputs.size) { "Duplicate resource input" }
        require(protectedDecisions.map { it.identity }.distinct().size == protectedDecisions.size)
        val protected = protectedDecisions.associateBy { it.identity }
        val rows = inputs.map { input ->
            val stem = if (input.fileName.substringAfterLast('.', "").lowercase() in DroppedFileMedia.VIDEO_EXTENSIONS) {
                input.fileName.substringBeforeLast('.')
            } else input.fileName
            val parsed = parser.parse(stem)
            val folder = input.folderName?.let { parser.parse(it) }
            val titles = (listOfNotNull(parsed.chineseTitle) + parsed.otherTitles +
                    listOfNotNull(folder?.chineseTitle) + folder?.otherTitles.orEmpty()).distinct()
                .ifEmpty { listOfNotNull(input.folderName?.takeIf { it.isNotBlank() }, stem.takeIf { it.isNotBlank() }).distinct() }
            val sorts = parsed.episodeRange?.knownSorts?.take(2)?.toList().orEmpty()
            val sort = input.entry.suggestedEpisodeSort ?: sorts.singleOrNull()
            fun row(status: ResourcePreviewStatus, targets: List<ResourceEpisodeTarget> = emptyList(), rule: String? = null) =
                ResourceAssociationPreviewRow(input, titles, sort, status, targets, rule)
            val decision = protected[input.identity]
            when {
                decision != null -> row(
                    if (decision.kind == ProtectedResourceDecisionKind.IGNORED) ResourcePreviewStatus.IGNORED else ResourcePreviewStatus.CONFIRMED,
                    listOfNotNull(decision.target),
                )
                !isVideo(input) -> row(ResourcePreviewStatus.NOT_VIDEO)
                sorts.size > 1 -> row(ResourcePreviewStatus.MULTIPLE_EPISODES)
                sort == null -> row(ResourcePreviewStatus.UNRECOGNIZED)
                else -> {
                    val matches = confirmedRules?.rules.orEmpty().mapNotNull { rule ->
                        if (rule.sourceId != input.identity.sourceId || rule.sourceId != input.parentReference?.sourceId ||
                            rule.parentResourceId != input.parentReference?.resourceId ||
                            rule.parentLocator != input.parentReference?.locator ||
                            rule.parentVersion != input.parentReference?.version) return@mapNotNull null
                        if (rule.acceptedTitles.isNotEmpty() && titles.none { title ->
                                rule.acceptedTitles.any { it.trim().equals(title.trim(), ignoreCase = true) }
                            }) return@mapNotNull null
                        rule.mappings.singleOrNull { it.sort == sort }?.let { rule.id to it.target }
                    }
                    val candidates = episodeOptions.filter { it.sort == sort }.map { it.target }.distinct()
                    when {
                        matches.size == 1 -> if (episodeOptions.any { it.target == matches.single().second }) {
                            row(ResourcePreviewStatus.AUTO_ASSIGNABLE, listOf(matches.single().second), matches.single().first)
                        } else row(ResourcePreviewStatus.UNRECOGNIZED)
                        matches.size > 1 -> row(ResourcePreviewStatus.AMBIGUOUS, matches.map { it.second }.distinct())
                        candidates.size == 1 -> row(ResourcePreviewStatus.SUGGESTED, candidates)
                        candidates.size > 1 -> row(ResourcePreviewStatus.AMBIGUOUS, candidates)
                        else -> row(ResourcePreviewStatus.UNRECOGNIZED)
                    }
                }
            }
        }
        val candidates = rows.filter { it.status == ResourcePreviewStatus.AUTO_ASSIGNABLE || it.status == ResourcePreviewStatus.SUGGESTED }
        val counts = rows.filter { it.status == ResourcePreviewStatus.AUTO_ASSIGNABLE || it.status == ResourcePreviewStatus.SUGGESTED ||
                it.status == ResourcePreviewStatus.AMBIGUOUS }
            .flatMap { row -> row.targets.map { row.input.identity.sourceId to it } }.groupingBy { it }.eachCount()
        val occupied = protectedDecisions.mapNotNull { decision -> decision.target?.let { decision.identity.sourceId to it } }.toSet()
        return rows.map { row ->
            if (row in candidates && row.targets.any { target ->
                    val key = row.input.identity.sourceId to target
                    counts.getValue(key) > 1 || key in occupied
                }) row.copy(status = ResourcePreviewStatus.AMBIGUOUS, matchedRuleId = null) else row
        }
    }

    /** The UI supplies the explicitly selected file order and target order, including cross-season splits. */
    fun assignInOrder(inputs: List<ResourcePreviewInput>, targets: List<ResourceEpisodeTarget>): Map<ResourceFileIdentity, ResourceEpisodeTarget> {
        require(inputs.size == targets.size && inputs.map { it.identity }.distinct().size == inputs.size)
        require(inputs.all(::isVideo))
        return inputs.map { it.identity }.zip(targets).toMap()
    }

    private fun isVideo(input: ResourcePreviewInput): Boolean = when (input.entry.kind) {
        MediaSourceEntryKind.VIDEO -> true
        MediaSourceEntryKind.TORRENT -> input.selectedFilePath != null &&
                input.fileName.substringAfterLast('.', "").lowercase() in DroppedFileMedia.VIDEO_EXTENSIONS
        else -> false
    }
}
