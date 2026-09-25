package me.him188.ani.app.ui.resource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.models.episode.displayName
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.ui.lang.*
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ResourceCurrentScanControls(viewModel: ResourceLibraryViewModel) {
    val browser by viewModel.browser.state.collectAsStateWithLifecycle()
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val parent = browser.path.lastOrNull()?.takeIf { it.kind == MediaSourceEntryKind.DIRECTORY } ?: return
    val root = roots.find { Json.decodeFromString<MediaResourceRef>(it.referenceJson) == parent.reference }
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (root != null) ResourceScanRootStatus(root, viewModel::scanRoot, viewModel::cancelScan)
        TextButton(viewModel::configureCurrentScanRules) { Text(stringResource(Lang.resource_scan_rules)) }
    }
}

@Composable
internal fun ResourceScanRoots(viewModel: ResourceLibraryViewModel) {
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val resources by viewModel.resources.collectAsStateWithLifecycle()
    val folders = roots.filterNot { root -> resources.any { resource ->
        resource.entryKind == MediaSourceEntryKind.VIDEO.name && resource.sourceId == root.sourceId &&
                viewModel.library.decodeReference(resource) == Json.decodeFromString<MediaResourceRef>(root.referenceJson)
    } }
    if (folders.isEmpty()) return
    Column(Modifier.padding(16.dp)) {
        Text(stringResource(Lang.resource_scan_roots), style = MaterialTheme.typography.titleMedium)
        folders.forEach { root ->
            Text(root.name, style = MaterialTheme.typography.titleSmall)
            ResourceScanRootStatus(root, viewModel::scanRoot, viewModel::cancelScan)
            Row {
                TextButton({ viewModel.configureScanRules(root) }) { Text(stringResource(Lang.resource_scan_rules)) }
                TextButton({ viewModel.removeScanRoot(root.id) }) { Text(stringResource(Lang.resource_scan_forget)) }
            }
        }
        Text(stringResource(Lang.resource_scan_forget_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ResourceScanRootStatus(root: LibraryScanRootEntity, onScan: (LibraryScanRootEntity) -> Unit, onCancel: (String) -> Unit) {
    Column {
        if (root.activeScanToken != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton({ onCancel(root.id) }) { Text(stringResource(Lang.resource_scan_cancel)) }
        } else TextButton({ onScan(root) }) { Text(stringResource(if (root.error != null) Lang.resource_scan_retry else Lang.resource_scan_directory)) }
        Text(stringResource(if (root.lastCompletedMillis == null) Lang.resource_scan_first_hint else Lang.resource_scan_completed), style = MaterialTheme.typography.bodySmall)
        if (root.error != null) Text(root.error!!, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun ResourceScanRulesDialog(viewModel: ResourceLibraryViewModel) {
    val state by viewModel.scanRules.state.collectAsStateWithLifecycle()
    val root = state.root ?: return
    val query by viewModel.subjectQuery.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val sourceName = sources.find { it.mediaSourceId == root.sourceId }?.source?.info?.displayName ?: root.sourceId
    val results = viewModel.subjectResults.collectAsLazyPagingItems()
    var showResults by remember(root.id) { mutableStateOf(true) }
    AlertDialog(onDismissRequest = viewModel.scanRules::dismiss,
        title = { Text(stringResource(Lang.resource_scan_rules)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Text(listOf(sourceName, root.name).distinct().joinToString(" / "), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Lang.resource_scan_scope_hint), style = MaterialTheme.typography.bodySmall)
                if (root.matchingRuleJson != null) Text(stringResource(Lang.resource_scan_replace_rules), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(query, { viewModel.subjectQuery.value = it; showResults = true },
                    label = { Text(stringResource(Lang.resource_search_subject)) }, singleLine = true,
                    enabled = !state.saving, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    if (state.loading || results.loadState.refresh is LoadState.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (state.error != null || results.loadState.refresh is LoadState.Error) item { Text(stringResource(Lang.resource_load_failed), color = MaterialTheme.colorScheme.error) }
                    if (showResults) items(results.itemCount) { index -> results[index]?.subjectInfo?.let { subject ->
                        ListItem(headlineContent = { Text(subject.displayName) }, modifier = Modifier.clickable(enabled = !state.loading && !state.saving) {
                            showResults = false
                            viewModel.scanRules.chooseSubject(subject.subjectId)
                        })
                    } }
                    item { ResourceScanRuleOptions(state, viewModel.scanRules::edit, viewModel.scanRules::confirmScope) }
                    if (root.matchingRuleJson != null) item {
                        TextButton(viewModel.scanRules::remove, enabled = !state.saving && !state.loading) { Text(stringResource(Lang.resource_scan_remove_rule)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(viewModel.scanRules::save, enabled = state.canSave) { Text(stringResource(Lang.resource_confirm)) } },
        dismissButton = { TextButton(viewModel.scanRules::dismiss, enabled = !state.saving) { Text(stringResource(Lang.resource_cancel)) } },
    )
}

@Composable
internal fun ResourceScanRuleOptions(
    state: ResourceScanRuleState,
    onEdit: ((ResourceScanRuleState) -> ResourceScanRuleState) -> Unit,
    onConfirmScope: (Boolean) -> Unit,
) {
    val subject = state.subject ?: return
    val enabled = !state.loading && !state.saving
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(subject.subjectInfo.displayName, style = MaterialTheme.typography.titleMedium)
        Row {
            RadioButton(!state.seasonNumbers, { onEdit { it.copy(seasonNumbers = false) } }, enabled = enabled)
            Text(stringResource(Lang.resource_scan_sort))
        }
        Row {
            RadioButton(state.seasonNumbers, { onEdit { it.copy(seasonNumbers = true) } }, enabled = enabled)
            Text(stringResource(Lang.resource_scan_season))
        }
        OutlinedTextField(state.acceptedTitles, { value -> onEdit { it.copy(acceptedTitles = value) } },
            enabled = enabled && !state.acceptAllTitles, label = { Text(stringResource(Lang.resource_scan_titles)) }, modifier = Modifier.fillMaxWidth())
        Row {
            Checkbox(state.acceptAllTitles, { value -> onEdit { it.copy(acceptAllTitles = value) } }, enabled = enabled, modifier = Modifier.testTag("scan-all-titles"))
            Text(stringResource(Lang.resource_scan_all_titles))
        }
        Row {
            Checkbox(state.scopeConfirmed, onConfirmScope, enabled = enabled, modifier = Modifier.testTag("scan-confirm-scope"))
            Text(stringResource(Lang.resource_scan_confirm_scope))
        }
        Text(stringResource(Lang.resource_scan_mapping_preview), style = MaterialTheme.typography.titleSmall)
        subject.episodes.forEach { episode ->
            val sort = scanEpisodeNumber(episode.episodeInfo, state.seasonNumbers)
            Row {
                Checkbox(episode.episodeId !in state.excludedEpisodes && sort != null, { checked -> onEdit {
                    it.copy(excludedEpisodes = if (checked) it.excludedEpisodes - episode.episodeId else it.excludedEpisodes + episode.episodeId)
                } }, enabled = enabled && sort != null)
                Text("${sort ?: "—"} → ${episode.episodeInfo.sort} ${episode.episodeInfo.displayName}")
            }
        }
        if (state.mappings.map { it.sort }.distinct().size != state.mappings.size) {
            Text(stringResource(Lang.resource_scan_duplicate_sort), color = MaterialTheme.colorScheme.error)
        }
    }
}
