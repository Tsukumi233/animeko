package me.him188.ani.app.ui.resource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.domain.mediasource.library.ConfirmedResourceEpisodeMapping
import me.him188.ani.app.domain.mediasource.library.ConfirmedResourceMatchingRule
import me.him188.ani.app.domain.mediasource.library.ConfirmedResourceMatchingRules
import me.him188.ani.app.domain.mediasource.library.ResourceEpisodeTarget
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.utils.platform.Uuid

internal fun scanEpisodeNumber(episode: EpisodeInfo, seasonNumbers: Boolean): EpisodeSort? =
    (if (episode.sort is EpisodeSort.Special) episode.sort else if (seasonNumbers) episode.ep else episode.sort)
        ?.takeUnless { it is EpisodeSort.Unknown }

data class ResourceScanRuleState(
    val requestId: String = Uuid.randomString(),
    val root: LibraryScanRootEntity? = null,
    val subject: SubjectCollectionInfo? = null,
    val seasonNumbers: Boolean = false,
    val acceptedTitles: String = "",
    val acceptAllTitles: Boolean = false,
    val excludedEpisodes: Set<Int> = emptySet(),
    val scopeConfirmed: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: Throwable? = null,
) {
    val mappings: List<ConfirmedResourceEpisodeMapping> get() = subject?.let { subject ->
        subject.episodes.filterNot { it.episodeId in excludedEpisodes }.mapNotNull { episode ->
            val sort = scanEpisodeNumber(episode.episodeInfo, seasonNumbers)
            sort?.let { ConfirmedResourceEpisodeMapping(it, ResourceEpisodeTarget(subject.subjectId, episode.episodeId)) }
        }
    }.orEmpty()
    val canSave: Boolean get() = root != null && !loading && !saving && scopeConfirmed &&
            (acceptAllTitles || acceptedTitles.lineSequence().any { it.isNotBlank() }) && mappings.isNotEmpty() &&
            mappings.map { it.sort }.distinct().size == mappings.size

    fun confirmedRules(): ConfirmedResourceMatchingRules {
        require(canSave)
        val parent = Json.decodeFromString<MediaResourceRef>(requireNotNull(root).referenceJson)
        return ConfirmedResourceMatchingRules(rules = listOf(ConfirmedResourceMatchingRule.forParent(
            Uuid.randomString(), parent, mappings,
            if (acceptAllTitles) emptySet() else acceptedTitles.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )))
    }
}

/** Each dialog owns a request identity so late metadata cannot change a different directory's rule. */
class ResourceScanRuleController(
    private val scope: CoroutineScope,
    private val loadSubject: suspend (Int) -> SubjectCollectionInfo,
    private val save: suspend (String, ConfirmedResourceMatchingRules) -> Unit,
    private val remove: suspend (String) -> Unit,
) {
    val state = MutableStateFlow(ResourceScanRuleState())
    private var load: Job? = null

    fun open(root: LibraryScanRootEntity) {
        load?.cancel()
        state.value = ResourceScanRuleState(root = root)
        if (root.matchingRuleJson != null) {
            try {
                val rule = ConfirmedResourceMatchingRules.decode(root.matchingRuleJson!!).rules.singleOrNull()
                val subjectId = rule?.mappings?.map { it.target.subjectId }?.distinct()?.singleOrNull()
                if (subjectId != null) loadSubject(subjectId, rule)
            } catch (e: Exception) { state.update { it.copy(error = e) } }
        }
    }
    fun dismiss() {
        if (state.value.saving) return
        load?.cancel()
        state.value = ResourceScanRuleState()
    }
    fun edit(transform: (ResourceScanRuleState) -> ResourceScanRuleState) {
        if (!state.value.saving && !state.value.loading) state.update { transform(it).copy(scopeConfirmed = false, error = null) }
    }
    fun confirmScope(confirmed: Boolean) { state.update { if (it.saving || it.loading) it else it.copy(scopeConfirmed = confirmed) } }
    fun chooseSubject(id: Int) = loadSubject(id, null)
    private fun loadSubject(id: Int, savedRule: ConfirmedResourceMatchingRule?) {
        if (state.value.saving) return
        load?.cancel()
        val request = Uuid.randomString()
        state.update { it.copy(requestId = request, loading = true, error = null, scopeConfirmed = false) }
        load = scope.launch {
            try {
                val subject = loadSubject(id)
                val mappedIds = savedRule?.mappings?.map { it.target.episodeId }?.toSet()
                val seasonNumbers = savedRule != null && savedRule.mappings.any { mapping ->
                    subject.episodes.find { it.episodeId == mapping.target.episodeId }?.episodeInfo?.sort != mapping.sort
                } && savedRule.mappings.all { mapping ->
                    subject.episodes.find { it.episodeId == mapping.target.episodeId }?.episodeInfo?.let { scanEpisodeNumber(it, true) } == mapping.sort
                }
                state.update { if (it.requestId != request) it else it.copy(subject = subject, loading = false,
                    acceptedTitles = savedRule?.acceptedTitles?.joinToString("\n") ?: subject.subjectInfo.displayName,
                    acceptAllTitles = savedRule?.acceptedTitles?.isEmpty() ?: false,
                    seasonNumbers = seasonNumbers,
                    excludedEpisodes = if (mappedIds == null) emptySet() else subject.episodes.map { it.episodeId }.toSet() - mappedIds) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { if (it.requestId != request) it else it.copy(loading = false, error = e) } }
        }
    }
    fun save() {
        val current = state.value
        if (!current.canSave) return
        val rules = current.confirmedRules()
        submit { save(requireNotNull(current.root).id, rules) }
    }
    fun remove() {
        val root = state.value.root ?: return
        submit { remove(root.id) }
    }
    private fun submit(block: suspend () -> Unit) {
        if (state.value.loading || state.value.saving) return
        state.update { it.copy(saving = true, error = null) }
        scope.launch {
            try { block(); state.value = ResourceScanRuleState() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(saving = false, error = e) } }
        }
    }
}
