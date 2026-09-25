package me.him188.ani.app.ui.resource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import me.him188.ani.app.data.models.episode.displayName
import me.him188.ani.app.domain.media.DroppedFileMedia
import me.him188.ani.app.domain.mediasource.fileservice.FileServiceMediaSource
import me.him188.ani.app.domain.mediasource.fileservice.FileServiceProtocol
import me.him188.ani.app.domain.mediasource.library.ResourceEpisodeTarget
import me.him188.ani.app.domain.mediasource.library.ResourceFileIdentity
import me.him188.ani.app.domain.mediasource.library.ResourcePreviewInput
import me.him188.ani.app.ui.lang.*
import me.him188.ani.datasources.api.source.MediaSourceBrowser
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.source.MediaSourceSearchScope
import org.jetbrains.compose.resources.stringResource

@Composable
fun ResourceLibraryScreen(
    viewModel: ResourceLibraryViewModel,
    onPlay: (subjectId: Int, episodeId: Int, resourceId: String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(0),
    initialPage: Int = 0,
    navigationIcon: @Composable () -> Unit = {},
    downloads: @Composable () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(initialPage) }
    val showBrowser by viewModel.showBrowser.collectAsStateWithLifecycle()
    LaunchedEffect(showBrowser) {
        if (showBrowser) { page = 1; viewModel.showBrowser.value = false }
    }
    var addMenu by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf<FileServiceProtocol?>(null) }
    var pikpakAccount by remember { mutableStateOf(false) }
    val localName = stringResource(Lang.resource_local_files)
    LaunchedEffect(viewModel) { viewModel.importInitialFiles(localName) }
    val pickers = rememberResourceFilePickers(
        onFiles = { viewModel.addLocal(it, false, localName) },
        onDirectory = { viewModel.addLocal(listOf(it), true, localName) },
        onError = { viewModel.error.value = it },
    )
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val pendingPlayback by viewModel.pendingPlayback.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPlayback) {
        pendingPlayback?.let {
            viewModel.pendingPlayback.value = null
            onPlay(it.subjectId, it.episodeId, it.resourceId)
        }
    }
    Column(modifier.windowInsetsPadding(windowInsets).consumeWindowInsets(windowInsets)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                navigationIcon()
                Text(stringResource(Lang.resource_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp))
            }
            Row {
                TextButton(onSettings) { Text(stringResource(Lang.resource_settings)) }
                Box {
                    TextButton({ addMenu = true }, enabled = !busy) { Text(stringResource(Lang.resource_add)) }
                    DropdownMenu(addMenu, { addMenu = false }) {
                        DropdownMenuItem({ Text(stringResource(Lang.resource_pick_files)) }, { addMenu = false; pickers.files() })
                        DropdownMenuItem({ Text(stringResource(Lang.resource_pick_directory)) }, { addMenu = false; pickers.directory() })
                        DropdownMenuItem({ Text("WebDAV") }, { addMenu = false; connection = FileServiceProtocol.WEBDAV })
                        DropdownMenuItem({ Text("SMB") }, { addMenu = false; connection = FileServiceProtocol.SMB })
                        DropdownMenuItem({ Text("PikPak") }, { addMenu = false; pikpakAccount = true })
                    }
                }
            }
        }
        SecondaryTabRow(page) {
            listOf(Lang.resource_mine, Lang.resource_sources, Lang.resource_downloads).forEachIndexed { index, label ->
                Tab(page == index, { page = index }, text = { Text(stringResource(label)) })
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) Text(stringResource(Lang.resource_load_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        if (selected.isNotEmpty() && page != 2) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ viewModel.selected.value = emptyList() }) { Text(stringResource(Lang.resource_clear_selection)) }
                Button(viewModel::beginAssociation) { Text(stringResource(Lang.resource_associate_count, selected.size)) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                0 -> MyResources(viewModel, onPlay)
                1 -> {
                    val state by viewModel.browser.state.collectAsStateWithLifecycle()
                    if (state.sourceId == null) {
                        val sources by viewModel.sources.collectAsStateWithLifecycle()
                        LazyColumn(Modifier.fillMaxSize()) {
                            item { ResourceScanRoots(viewModel) }
                            if (sources.isEmpty()) item { ResourceEmptyText(stringResource(Lang.resource_no_sources)) }
                            items(sources, key = { it.instanceId }) { instance ->
                                val browser = instance.source as? MediaSourceBrowser
                                ListItem(
                                    headlineContent = { Text(instance.source.info.displayName) },
                                    trailingContent = if (instance.source is FileServiceMediaSource) ({ TextButton({ viewModel.editFileService(instance.instanceId) }, enabled = !busy) { Text(stringResource(Lang.resource_edit)) } }) else null,
                                    supportingContent = { Text(stringResource(if (browser == null) Lang.resource_no_browser else if (instance.isEnabled) Lang.resource_enabled else Lang.resource_disabled)) },
                                    modifier = Modifier.clickable(enabled = browser != null) {
                                        viewModel.browser.open(instance.mediaSourceId, instance.source.info.displayName, browser!!)
                                    },
                                )
                            }
                        }
                    } else ResourceBrowserContent(
                        state, selected.map { it.identity }.toSet(),
                        onBack = viewModel.browser::back, onSearch = viewModel.browser::search,
                        onRefresh = viewModel.browser::refresh, onMore = viewModel.browser::more,
                        onEnter = { viewModel.browser.enter(it.entry) }, onToggle = viewModel::toggle,
                        onScan = viewModel::scanCurrent, canScan = !busy,
                        scanControls = { ResourceCurrentScanControls(viewModel) },
                    )
                }
                2 -> downloads()
            }
        }
    }
    ResourceScanRulesDialog(viewModel)
    ResourceAssociationDialog(viewModel)
    if (pikpakAccount) {
        val config by viewModel.pikpakConfig.collectAsStateWithLifecycle()
        var username by remember(config.username) { mutableStateOf(config.username) }
        var password by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pikpakAccount = false }, title = { Text("PikPak") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Lang.resource_pikpak_account_shared))
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(Lang.resource_username)) }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(Lang.resource_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
        }, confirmButton = {
            TextButton({ viewModel.addPikPak(username.trim(), password); pikpakAccount = false; page = 1 }, enabled = username.isNotBlank() &&
                    (password.isNotBlank() || (username.trim() == config.username && (config.password.isNotBlank() || config.refreshToken.isNotBlank())))) {
                Text(stringResource(Lang.resource_add))
            }
        }, dismissButton = { TextButton({ pikpakAccount = false }) { Text(stringResource(Lang.resource_cancel)) } })
    }
    val editor by viewModel.connectionEditor.collectAsStateWithLifecycle()
    editor?.let { current ->
        ResourceConnectionDialog(current.arguments.protocol,
            onDismiss = { viewModel.connectionEditor.value = null },
            onSave = { args, username, password, domain -> viewModel.updateFileService(current, args, username, password, domain) },
            editor = current, saving = busy, hasError = error != null,
            onRemove = { viewModel.removeFileService(current) })
    }
    connection?.let { protocol ->
        ResourceConnectionDialog(protocol, onDismiss = { connection = null }, onSave = { args, username, password, domain ->
            viewModel.addFileService(args, username, password, domain)
            connection = null
            page = 1
        })
    }
}

@Composable
private fun MyResources(viewModel: ResourceLibraryViewModel, onPlay: (Int, Int, String) -> Unit) {
    val resources by viewModel.resources.collectAsStateWithLifecycle()
    val bindings by viewModel.bindings.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val bySource = sources.associateBy { it.mediaSourceId }
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        if (resources.isEmpty()) item { ResourceEmptyText(stringResource(Lang.resource_empty_library)) }
        bindings.groupBy { it.subjectId }.forEach { (subjectId, entries) ->
            item(key = "subject:$subjectId") {
                val info by viewModel.subjectDisplayInfo(subjectId).collectAsStateWithLifecycle(null)
                Text(info?.displayName ?: "#$subjectId", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            }
            items(entries, key = { "${it.resourceId}:${it.subjectId}:${it.episodeId}" }) { binding ->
                val resource = resources.find { it.id == binding.resourceId }
                val subject by viewModel.subjectCollection(subjectId).collectAsStateWithLifecycle()
                val episode = subject?.episodes?.find { it.episodeId == binding.episodeId }?.episodeInfo
                val source = bySource[binding.sourceId]?.source
                ResourceLibraryEntry(
                    name = resource?.name.orEmpty(),
                    sourceName = source?.info?.displayName,
                    sourceKind = source?.kind,
                    episodeLabel = episode?.let { "${it.sort} ${it.displayName}" },
                    filePath = binding.selectedFilePath,
                    missing = resource?.available == false,
                    onClick = { onPlay(subjectId, binding.episodeId, binding.resourceId) },
                    trailingContent = {
                        Row {
                            TextButton({ viewModel.selectIndexed(binding.resourceId, binding.selectedFilePath, ResourceEpisodeTarget(binding.subjectId, binding.episodeId)) }) { Text(stringResource(Lang.resource_edit)) }
                            TextButton({ viewModel.removeBinding(binding.resourceId, subjectId, binding.episodeId) }) { Text(stringResource(Lang.resource_unlink)) }
                        }
                    },
                )
            }
        }
        val pending = pendingLibraryFiles(resources, bindings, suggestions)
        if (pending.isNotEmpty()) item { Text(stringResource(Lang.resource_pending), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
        items(pending, key = { "pending:${it.resource.id}:${it.filePath}" }) { row ->
            val resource = row.resource
            val source = bySource[resource.sourceId]?.source
            val status = when (row.status) {
                ResourcePendingStatus.UNKNOWN_FILES -> Lang.resource_browse_release
                ResourcePendingStatus.INVALID_STATE -> Lang.resource_file_decision_error
                ResourcePendingStatus.IGNORED -> Lang.resource_file_ignored
                ResourcePendingStatus.SUGGESTED -> Lang.resource_file_suggested
                ResourcePendingStatus.AMBIGUOUS -> Lang.resource_file_ambiguous
                ResourcePendingStatus.UNRECOGNIZED -> Lang.resource_choose_episode
            }
            ResourceLibraryEntry(
                name = row.filePath?.substringAfterLast('/')?.substringAfterLast('\\') ?: resource.name,
                sourceName = source?.info?.displayName,
                sourceKind = source?.kind,
                statusLabel = stringResource(status),
                filePath = row.filePath,
                missing = !resource.available,
                onClick = { viewModel.selectIndexed(resource.id, row.filePath) },
                trailingContent = if (row.filePath == null) ({ TextButton({ viewModel.removeResource(resource.id) }) { Text(stringResource(Lang.resource_remove_index)) } }) else null,
            )
        }
    }
}

@Composable
internal fun ResourceBrowserContent(
    state: ResourceBrowserState,
    selected: Set<ResourceFileIdentity>,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onEnter: (ResourcePreviewInput) -> Unit,
    onToggle: (ResourcePreviewInput) -> Unit,
    onScan: () -> Unit,
    canScan: Boolean,
    scanControls: @Composable () -> Unit = {},
) {
    var query by remember(state.sourceId, state.path, state.query) { mutableStateOf(state.query) }
    Column(Modifier.fillMaxSize().testTag("resource-browser")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onBack) { Text(stringResource(Lang.resource_back)) }
            TextButton(onRefresh, enabled = !state.loading) { Text(stringResource(Lang.resource_refresh)) }
            if (state.path.lastOrNull()?.kind == MediaSourceEntryKind.DIRECTORY) {
                TextButton(onScan, enabled = canScan) { Text(stringResource(Lang.resource_scan_directory)) }
            }
        }
        Text((listOf(state.sourceName) + state.path.map { it.name }.let { if (it.firstOrNull() == state.sourceName) it.drop(1) else it }).joinToString(" / "), modifier = Modifier.padding(horizontal = 16.dp))
        scanControls()
        if (state.searchScope != MediaSourceSearchScope.NONE) Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, modifier = Modifier.weight(1f).testTag("resource-search"), singleLine = true,
                label = { Text(stringResource(if (state.searchesIndex) Lang.resource_search_index else if (state.searchScope == MediaSourceSearchScope.SOURCE) Lang.resource_search_source else Lang.resource_search_folder)) })
            TextButton({ onSearch(query) }, enabled = !state.loading) { Text(stringResource(Lang.resource_search)) }
        }
        if (state.searchesIndex) Text(stringResource(Lang.resource_search_index_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (state.error != null) item { ResourceEmptyText(stringResource(Lang.resource_load_failed)) }
            else if (!state.loading && state.rows.isEmpty()) item {
                ResourceEmptyText(stringResource(if (state.needsSearch) Lang.resource_enter_keyword else Lang.resource_empty_folder))
            }
            items(state.rows, key = { "${it.identity.sourceId}:${it.identity.resourceId}:${it.selectedFilePath}" }) { row ->
                val container = row.selectedFilePath == null && row.entry.kind.isContainer
                val video = row.entry.kind.isVideo || (row.selectedFilePath != null && row.fileName.substringAfterLast('.', "").lowercase() in DroppedFileMedia.VIDEO_EXTENSIONS)
                ListItem(
                    headlineContent = { Text(row.fileName) },
                    leadingContent = { Icon(if (container) Icons.Rounded.Folder else if (video) Icons.Rounded.VideoFile else Icons.Rounded.InsertDriveFile, null) },
                    supportingContent = row.selectedFilePath?.let { { Text(it) } },
                    trailingContent = if (video) ({ Checkbox(row.identity in selected, { onToggle(row) }) }) else null,
                    modifier = Modifier.clickable(enabled = container || video) { if (container) onEnter(row) else onToggle(row) },
                )
            }
            if (state.nextPageToken != null) item { TextButton(onMore, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Lang.resource_more)) } }
        }
    }
}

@Composable
private fun ResourceEmptyText(text: String) { Text(text, modifier = Modifier.padding(24.dp)) }

@Composable
private fun ResourceAssociationDialog(viewModel: ResourceLibraryViewModel) {
    val state by viewModel.association.collectAsStateWithLifecycle()
    if (!state.visible) return
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val query by viewModel.subjectQuery.collectAsStateWithLifecycle()
    val results = viewModel.subjectResults.collectAsLazyPagingItems()
    var showResults by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = viewModel::dismissAssociation,
        title = { Text(stringResource(Lang.resource_associate)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                OutlinedTextField(query, { viewModel.subjectQuery.value = it; showResults = true }, label = { Text(stringResource(Lang.resource_search_subject)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    if (results.loadState.refresh is LoadState.Loading || state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (results.loadState.refresh is LoadState.Error || state.error != null) item {
                        Text(stringResource(Lang.resource_load_failed), color = MaterialTheme.colorScheme.error)
                        TextButton({ showResults = true; results.retry() }) { Text(stringResource(Lang.resource_refresh)) }
                    }
                    if (showResults && query.isNotBlank() && results.itemCount == 0 && results.loadState.refresh is LoadState.NotLoading) item {
                        Text(stringResource(Lang.resource_no_subject_results))
                    }
                    if (showResults) items(results.itemCount) { index -> results[index]?.subjectInfo?.let { subject ->
                        ListItem(headlineContent = { Text(subject.displayName) }, modifier = Modifier.clickable(enabled = !state.loading && !state.saving) { showResults = false; viewModel.chooseSubject(subject.subjectId) })
                    } }
                    state.subjects.forEach { subject ->
                        item(key = "catalog:${subject.subjectId}") {
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                TextButton({ expanded = true }) { Text(stringResource(Lang.resource_assign_order, subject.subjectInfo.displayName)) }
                                DropdownMenu(expanded, { expanded = false }) {
                                    subject.episodes.forEachIndexed { index, episode -> DropdownMenuItem(
                                        { Text("${episode.episodeInfo.sort} ${episode.episodeInfo.displayName}") },
                                        { viewModel.assignInOrder(subject.subjectId, episode.episodeId); expanded = false },
                                        enabled = !state.saving && subject.episodes.size - index >= selected.count { it.identity !in state.ignored },
                                    ) }
                                }
                            }
                        }
                    }
                    items(selected, key = { "${it.identity}:${it.selectedFilePath}" }) { input ->
                        var expanded by remember { mutableStateOf(false) }
                        val target = state.targets[input.identity]
                        val subject = state.subjects.find { it.subjectId == target?.subjectId }
                        val episode = subject?.episodes?.find { it.episodeId == target?.episodeId }
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(input.fileName)
                            Box {
                                TextButton({ expanded = true }, enabled = !state.saving && input.identity !in state.ignored) {
                                    Text(if (episode == null) stringResource(Lang.resource_choose_episode) else "${subject?.subjectInfo?.displayName.orEmpty()} · ${episode.episodeInfo.sort}")
                                }
                                DropdownMenu(expanded, { expanded = false }) {
                                    state.subjects.forEach { item -> item.episodes.forEach { ep ->
                                        DropdownMenuItem({ Text("${item.subjectInfo.displayName} · ${ep.episodeInfo.sort} ${ep.episodeInfo.displayName}") }, {
                                            viewModel.setTarget(input.identity, ResourceEpisodeTarget(item.subjectId, ep.episodeId)); expanded = false
                                        })
                                    } }
                                }
                            }
                            Row { Checkbox(input.identity in state.ignored, { viewModel.ignore(input.identity, it) }, enabled = !state.saving); Text(stringResource(Lang.resource_ignore)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(viewModel::confirm, enabled = !state.loading && !state.saving && selected.isNotEmpty() && selected.all { it.identity in state.ignored || it.identity in state.targets }) { Text(stringResource(if (viewModel.willPlayOnConfirmation) Lang.resource_confirm_play else Lang.resource_confirm)) } },
        dismissButton = { TextButton(viewModel::dismissAssociation, enabled = !state.saving) { Text(stringResource(Lang.resource_cancel)) } },
    )
}
