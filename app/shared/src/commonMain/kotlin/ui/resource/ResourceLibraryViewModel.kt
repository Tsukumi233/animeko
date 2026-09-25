package me.him188.ani.app.ui.resource

import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.data.persistent.database.dao.LibrarySourceCredentialsEntity
import me.him188.ani.app.data.repository.media.MediaSourceInstanceRepository
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.data.repository.media.ResourceIgnoredInput
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.subject.SubjectSearchRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.fileservice.FileServiceArguments
import me.him188.ani.app.domain.mediasource.fileservice.FileServiceMediaSource
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.app.domain.mediasource.library.AssociateResourcesUseCase
import me.him188.ani.app.domain.mediasource.library.ConfirmScanMatchingRulesUseCase
import me.him188.ani.app.domain.mediasource.library.ResourceAssociationPreviewBuilder
import me.him188.ani.app.domain.mediasource.library.ResourceEpisodeOption
import me.him188.ani.app.domain.mediasource.library.ResourceEpisodeSelection
import me.him188.ani.app.domain.mediasource.library.ResourceEpisodeTarget
import me.him188.ani.app.domain.mediasource.library.ResourceFileIdentity
import me.him188.ani.app.domain.mediasource.library.ResourcePreviewInput
import me.him188.ani.app.domain.mediasource.library.StoredScanMatchSuggestions
import me.him188.ani.app.domain.mediasource.local.LocalFileMediaSource
import me.him188.ani.app.domain.mediasource.local.LocalFileMediaSourceArguments
import me.him188.ani.app.domain.mediasource.local.LocalResourceAccess
import me.him188.ani.app.domain.mediasource.local.LocalResourceRelocationController
import me.him188.ani.app.domain.mediasource.local.LocalResourceRelocationUseCase
import me.him188.ani.app.domain.mediasource.local.ResourceLibraryScanCoordinator
import me.him188.ani.app.domain.mediasource.pikpak.PikPakMediaSource
import me.him188.ani.app.domain.mediasource.pikpak.PikPakMediaSourceArguments
import me.him188.ani.app.domain.mediasource.torrent.TorrentResourceBrowser
import me.him188.ani.app.domain.search.SubjectSearchQuery
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.datasources.api.source.FactoryId
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceBrowser
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.platform.Uuid
import org.koin.mp.KoinPlatform

data class ResourceAssociationUiState(
    val requestId: String = Uuid.randomString(),
    val visible: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val subjects: List<SubjectCollectionInfo> = emptyList(),
    val targets: Map<ResourceFileIdentity, ResourceEpisodeTarget> = emptyMap(),
    val ignored: Set<ResourceFileIdentity> = emptySet(),
    val error: Throwable? = null,
)

data class ResourcePlaybackTarget(val subjectId: Int, val episodeId: Int, val resourceId: String)

class ResourceLibraryViewModel(
    private val manager: MediaSourceManager,
    private val instances: MediaSourceInstanceRepository,
    val library: ResourceLibraryRepository,
    private val subjects: SubjectCollectionRepository,
    private val search: SubjectSearchRepository,
    private val settings: SettingsRepository,
    private val localAccess: LocalResourceAccess,
    private val scanCoordinator: ResourceLibraryScanCoordinator,
    private val associate: AssociateResourcesUseCase,
    torrentBrowser: TorrentResourceBrowser,
    matchingRules: ConfirmScanMatchingRulesUseCase,
    private val initialSubjectId: Int? = null,
    private val initialEpisodeId: Int? = null,
    private val initialFiles: List<String> = emptyList(),
) : AbstractViewModel() {
    val sources = manager.allInstances.map { entries -> entries.filterNot { it.source.kind == MediaSourceKind.LocalCache } }
        .stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
    val resources = library.resources.stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
    val suggestions = library.dao.suggestions().stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
    val bindings = library.bindings.stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
    val roots = library.dao.scanRoots().stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
    val browser = ResourceBrowserController(backgroundScope, torrentBrowser,
        onTorrentListed = { entry, paths ->
            library.dao.findResource(entry.reference.sourceId, entry.reference.resourceId)?.let { resource ->
                library.recordTorrentFiles(resource.id, entry.reference, paths)
            }
        },
    ) { sourceId, query ->
        library.searchIndexed(sourceId, query).map { result ->
            val resource = result.resource
            ResourcePreviewInput(MediaSourceEntry(library.decodeReference(resource), resource.name,
                MediaSourceEntryKind.valueOf(resource.entryKind), resource.size, resource.modifiedTimeMillis),
                selectedFilePath = result.selectedFilePath)
        }
    }
    val showBrowser = MutableStateFlow(false)
    val selected = MutableStateFlow<List<ResourcePreviewInput>>(emptyList())
    val association = MutableStateFlow(ResourceAssociationUiState())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<Throwable?>(null)
    val connectionEditor = MutableStateFlow<ResourceConnectionEditor?>(null)
    val subjectQuery = MutableStateFlow("")
    val pendingPlayback = MutableStateFlow<ResourcePlaybackTarget?>(null)
    val willPlayOnConfirmation: Boolean get() = initialSubjectId != null && initialEpisodeId != null && selected.value.count {
        it.identity !in association.value.ignored && association.value.targets[it.identity] == ResourceEpisodeTarget(initialSubjectId, initialEpisodeId)
    } == 1
    val pikpakConfig = settings.pikpakConfig.flow.stateIn(backgroundScope, SharingStarted.Eagerly, PikPakConfig.Default)
    @OptIn(FlowPreview::class)
    val subjectResults = combine(subjectQuery.debounce(300), settings.uiSettings.flow) { keyword, ui ->
        SubjectSearchQuery(keyword, nsfw = if (ui.searchSettings.nsfwMode == NsfwMode.HIDE) false else null)
    }.distinctUntilChanged().flatMapLatest { query ->
        if (query.keywords.isBlank()) flowOf(PagingData.empty()) else search.searchSubjects(query)
    }.cachedIn(backgroundScope)
    val scanRules = ResourceScanRuleController(backgroundScope,
        { subjects.librarySubjectCollectionFlow(it).first() }, matchingRules::confirm, matchingRules::remove)
    val activeScanRoots = scanCoordinator.activeRootIds
    val relocation = LocalResourceRelocationController.create(
        backgroundScope, LocalResourceRelocationUseCase(library, localAccess),
        resolveSource = { sourceId ->
            manager.allInstances.first().singleOrNull { it.mediaSourceId == sourceId }?.source as? LocalFileMediaSource
                ?: error("Local source is unavailable")
        },
        onCommitted = { browser.close(); selected.value = emptyList() },
    )

    fun refreshStaleRoots() { scanCoordinator.refreshStaleOnForeground() }
    private val sourceWrites = Mutex()
    private val preview = ResourceAssociationPreviewBuilder()
    private var subjectLoad: Job? = null
    private var initialFilesHandled = false
    private val subjectMetadata = mutableMapOf<Int, StateFlow<SubjectCollectionInfo?>>()

    fun subjectDisplayInfo(subjectId: Int) = subjects.getSubjectDisplayInfoOffline(subjectId)

    fun importInitialFiles(name: String) {
        if (initialFilesHandled || initialFiles.isEmpty()) return
        initialFilesHandled = true
        addLocal(initialFiles, directory = null, name = name)
    }

    fun subjectCollection(subjectId: Int): StateFlow<SubjectCollectionInfo?> = subjectMetadata.getOrPut(subjectId) {
        subjects.librarySubjectCollectionFlow(subjectId).stateIn(backgroundScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    fun toggle(input: ResourcePreviewInput) {
        selected.update { current ->
            if (current.any { it.identity == input.identity }) current.filterNot { it.identity == input.identity }
            else current + input
        }
    }

    fun selectIndexed(resourceId: String, filePath: String? = null, target: ResourceEpisodeTarget? = null) {
        val resource = resources.value.find { it.id == resourceId } ?: return
        val input = ResourcePreviewInput(MediaSourceEntry(library.decodeReference(resource), resource.name,
            MediaSourceEntryKind.valueOf(resource.entryKind), resource.size, resource.modifiedTimeMillis), selectedFilePath = filePath)
        if (input.entry.kind == MediaSourceEntryKind.TORRENT && filePath == null) {
            val source = sources.value.find { it.mediaSourceId == resource.sourceId }?.source
            val sourceBrowser = source as? MediaSourceBrowser
            if (sourceBrowser == null) {
                error.value = IllegalStateException("Source is unavailable for browsing")
                return
            }
            browser.openTorrent(input.entry, source.info.displayName, sourceBrowser)
            showBrowser.value = true
        } else {
            selected.value = listOf(input)
            openAssociation(target)
        }
    }

    fun beginAssociation() = openAssociation()

    private fun openAssociation(explicitTarget: ResourceEpisodeTarget? = null) {
        if (selected.value.isEmpty()) return
        subjectLoad?.cancel()
        val inputs = selected.value
        val initial = ResourceAssociationUiState(visible = true, loading = true)
        association.value = initial
        subjectQuery.value = preview.build(inputs, emptyList()).firstOrNull()?.titleSuggestions?.firstOrNull().orEmpty()
        subjectLoad = backgroundScope.launch {
            try {
                val ignored = mutableSetOf<ResourceFileIdentity>()
                val targets = mutableMapOf<ResourceFileIdentity, ResourceEpisodeTarget>()
                val storedBindings = library.bindings.first()
                for (input in inputs) {
                    val reference = input.entry.reference
                    val resource = library.dao.findResource(reference.sourceId, reference.resourceId) ?: continue
                    val binding = storedBindings.singleOrNull { it.resourceId == resource.id && it.selectedFilePath == input.selectedFilePath }
                    if (binding != null) targets[input.identity] = ResourceEpisodeTarget(binding.subjectId, binding.episodeId)
                    else {
                        val saved = library.dao.findSuggestion(resource.id)
                        if (saved?.let { input.selectedFilePath in library.decodeIgnoredFiles(it) } == true) {
                            ignored += input.identity
                        } else {
                            suggestedLibraryTarget(saved, input.selectedFilePath)?.let { targets[input.identity] = it }
                        }
                    }
                }
                if (explicitTarget != null) targets[inputs.single().identity] = explicitTarget
                else if (initialSubjectId != null && initialEpisodeId != null && inputs.size == 1) {
                    targets[inputs.single().identity] = ResourceEpisodeTarget(initialSubjectId, initialEpisodeId)
                    ignored -= inputs.single().identity
                }
                association.update { if (it.requestId == initial.requestId) it.copy(targets = targets, ignored = ignored) else it }
                val catalog = (targets.values.map { it.subjectId } + listOfNotNull(initialSubjectId)).distinct()
                    .map { subjects.librarySubjectCollectionFlow(it).first() }
                val options = catalog.flatMap { subject -> subject.episodes.map { ep ->
                    ResourceEpisodeOption(ResourceEpisodeTarget(subject.subjectId, ep.episodeId), ep.episodeInfo.sort)
                } }
                val suggestions = preview.build(inputs, options).mapNotNull { row -> row.targets.singleOrNull()?.let { row.input.identity to it } }.toMap()
                association.update { if (it.requestId == initial.requestId) it.copy(subjects = catalog, targets = suggestions + targets, loading = false) else it }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                association.update { if (it.requestId == initial.requestId) it.copy(loading = false, error = e) else it }
            }
        }
    }

    fun chooseSubject(subjectId: Int) {
        if (association.value.loading || association.value.saving) return
        val requestId = association.value.requestId
        association.update { it.copy(loading = true, error = null) }
        subjectLoad = backgroundScope.launch {
            try {
                val subject = subjects.librarySubjectCollectionFlow(subjectId).first()
                association.update { current ->
                    if (!current.visible || current.requestId != requestId) return@update current
                    val catalog = (current.subjects.filterNot { it.subjectId == subjectId } + subject)
                    val options = catalog.flatMap { item -> item.episodes.map { ep ->
                        ResourceEpisodeOption(ResourceEpisodeTarget(item.subjectId, ep.episodeId), ep.episodeInfo.sort)
                    } }
                    val suggestions = preview.build(selected.value, options).mapNotNull { row ->
                        row.targets.singleOrNull()?.let { row.input.identity to it }
                    }.toMap()
                    current.copy(loading = false, subjects = catalog, targets = suggestions + current.targets)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                association.update { if (it.requestId == requestId) it.copy(loading = false, error = e) else it }
            }
        }
    }

    fun setTarget(identity: ResourceFileIdentity, target: ResourceEpisodeTarget?) {
        association.update { it.copy(targets = if (target == null) it.targets - identity else it.targets + (identity to target)) }
    }

    fun ignore(identity: ResourceFileIdentity, ignore: Boolean) {
        association.update { it.copy(ignored = if (ignore) it.ignored + identity else it.ignored - identity) }
    }

    fun assignInOrder(subjectId: Int, firstEpisodeId: Int) {
        val catalog = association.value.subjects.find { it.subjectId == subjectId } ?: return
        val episodes = catalog.episodes.dropWhile { it.episodeId != firstEpisodeId }
        val inputs = selected.value.filterNot { it.identity in association.value.ignored }
        if (episodes.size < inputs.size) return
        val targets = preview.assignInOrder(inputs, episodes.take(inputs.size).map { ResourceEpisodeTarget(subjectId, it.episodeId) })
        association.update { it.copy(targets = it.targets + targets) }
    }

    fun confirm() {
        val state = association.value
        if (state.saving || state.loading) return
        val inputs = selected.value.filterNot { it.identity in state.ignored }
        if (selected.value.isEmpty() || inputs.any { it.identity !in state.targets }) return
        val ignored = selected.value.filter { it.identity in state.ignored }.map { ResourceIgnoredInput(it.entry, it.selectedFilePath) }
        association.update { it.copy(saving = true, error = null) }
        backgroundScope.launch {
            try {
                associate(inputs.map { input ->
                    val target = state.targets.getValue(input.identity)
                    ResourceEpisodeSelection(input.entry, target.subjectId, target.episodeId, input.selectedFilePath)
                }, ignored = ignored)
                val playbackTarget = if (initialSubjectId != null && initialEpisodeId != null) ResourceEpisodeTarget(initialSubjectId, initialEpisodeId) else null
                inputs.singleOrNull { playbackTarget != null && state.targets[it.identity] == playbackTarget }?.let { input ->
                    val reference = input.entry.reference
                    val resource = library.dao.findResource(reference.sourceId, reference.resourceId)
                    if (resource != null && initialSubjectId != null && initialEpisodeId != null) {
                        pendingPlayback.value = ResourcePlaybackTarget(initialSubjectId, initialEpisodeId, resource.id)
                    }
                }
                association.value = ResourceAssociationUiState()
                selected.value = emptyList()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { association.update { it.copy(saving = false, error = e) } }
        }
    }

    fun dismissAssociation() {
        if (association.value.saving) return
        subjectLoad?.cancel()
        association.value = ResourceAssociationUiState()
    }

    fun addLocal(uris: List<String>, directory: Boolean?, name: String) = action {
        sourceWrites.withLock {
            val allEntries = uris.distinct().map { localAccess.stat(it) }.distinctBy { it.uri }
            if (directory != null) require(allEntries.all { it.isDirectory == directory })
            val picked = mutableListOf<ResourcePreviewInput>()
            // 每个目录拥有独立来源；单文件共享本地文件来源，整批只打开一次关联确认。
            val groups = allEntries.map { listOf(it) }
            for (entries in groups) {
                val isDirectory = entries.first().isDirectory
                val localSourceIds = instances.flow.first().filter { it.factoryId == LocalFileMediaSource.FactoryId }
                    .map { it.mediaSourceId }.toSet()
                val existingRoot = if (isDirectory) library.dao.scanRoots().first().firstOrNull {
                    it.sourceId in localSourceIds &&
                        Json.decodeFromString<MediaResourceRef>(it.referenceJson).locator == entries.single().uri
                } else null
                val id = if (isDirectory) existingRoot?.sourceId ?: Uuid.randomString() else "user-local-files"
                val displayName = if (isDirectory) entries.single().name else name
                val args = LocalFileMediaSourceArguments(displayName)
                if (instances.flow.first().none { it.mediaSourceId == id }) {
                    instances.add(MediaSourceSave(id, id, LocalFileMediaSource.FactoryId, true,
                        MediaSourceConfig(serializedArguments = Json.encodeToJsonElement(args))))
                }
                val refs = entries.map { MediaResourceRef(id, it.uri) }
                val source = LocalFileMediaSource(id, localAccess, { refs }, args)
                for (entry in entries) {
                    val item = source.entry(entry.uri)
                    val root = library.dao.scanRoots().first().find {
                        it.sourceId == id && Json.decodeFromString<MediaResourceRef>(it.referenceJson) == item.reference
                    } ?: LibraryScanRootEntity(Uuid.randomString(), id, Json.encodeToString(item.reference), item.name, recursive = isDirectory)
                    library.dao.upsertScanRoot(root)
                    if (item.kind.isVideo) {
                        library.index(item)
                        picked += ResourcePreviewInput(item)
                    }
                    else if (isDirectory) {
                        manager.allInstances.first { list -> list.any { it.mediaSourceId == id } }
                        scanCoordinator.request(root.id)
                    }
                }
                if (isDirectory) {
                    picked += library.dao.resourcesForSource(id).first().filter { it.available }.map { resource ->
                        val parent = library.dao.findSuggestion(resource.id)?.let { suggestion ->
                            Json.decodeFromString<StoredScanMatchSuggestions>(suggestion.suggestionJson)
                                .rows.firstOrNull { it.selectedFilePath == null }?.parentReference
                        }
                        val folder = parent?.let { localAccess.stat(it.locator).name } ?: displayName
                        ResourcePreviewInput(MediaSourceEntry(library.decodeReference(resource), resource.name,
                            MediaSourceEntryKind.VIDEO, resource.size, resource.modifiedTimeMillis, parent = parent),
                            folderName = folder, parentReference = parent)
                    }
                }
                manager.allInstances.first { list -> list.any { it.mediaSourceId == id } }
            }
            selected.value = picked.distinctBy { it.identity }
            beginAssociation()
        }
    }

    fun addFileService(args: FileServiceArguments, username: String, password: String, domain: String) = action {
        val id = Uuid.randomString()
        library.dao.saveCredentials(LibrarySourceCredentialsEntity(id, username, password, domain))
        try {
            instances.add(MediaSourceSave(id, id, FileServiceMediaSource.FactoryId, true,
                MediaSourceConfig(serializedArguments = Json.encodeToJsonElement(args))))
        } catch (e: Exception) {
            withContext(NonCancellable) { library.dao.removeCredentials(id) }
            throw e
        }
        openSource(id)
    }

    private suspend fun openSource(id: String) {
        val instance = manager.allInstances.first { entries -> entries.any { it.mediaSourceId == id } }
            .single { it.mediaSourceId == id }
        browser.open(id, instance.source.info.displayName, instance.source as MediaSourceBrowser)
        showBrowser.value = true
    }

    fun editFileService(instanceId: String) = action {
        val save = instances.flow.first().single { it.instanceId == instanceId }
        require(save.factoryId == FileServiceMediaSource.FactoryId)
        val args = Json.decodeFromJsonElement<FileServiceArguments>(requireNotNull(save.config.serializedArguments))
        val credentials = library.dao.credentials(save.mediaSourceId)
        connectionEditor.value = ResourceConnectionEditor(save, args, credentials?.username.orEmpty(), credentials?.domain.orEmpty())
    }

    fun updateFileService(editor: ResourceConnectionEditor, args: FileServiceArguments, username: String, password: String, domain: String) = action {
        sourceWrites.withLock {
            val original = requireNotNull(instances.flow.first().singleOrNull { it.instanceId == editor.save.instanceId })
            check(original == editor.save) { "Connection changed while editing" }
            val credentials = library.dao.credentials(original.mediaSourceId)
            val sameAccount = credentials?.username.orEmpty() == username && credentials?.domain.orEmpty() == domain
            val updated = LibrarySourceCredentialsEntity(original.mediaSourceId, username,
                password.ifEmpty { if (sameAccount) credentials?.password.orEmpty() else "" }, domain)
            library.dao.saveCredentials(updated)
            try {
                check(instances.updateSave(original.instanceId) {
                    check(this == original) { "Connection changed while editing" }
                    copy(config = config.copy(serializedArguments = Json.encodeToJsonElement(args)))
                }) { "Connection was removed" }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    if (credentials == null) library.dao.removeCredentials(original.mediaSourceId)
                    else library.dao.saveCredentials(credentials)
                }
                throw e
            }
            if (browser.state.value.sourceId == original.mediaSourceId) browser.close()
            selected.update { inputs -> inputs.filterNot { it.identity.sourceId == original.mediaSourceId } }
            connectionEditor.value = null
        }
    }

    fun removeFileService(editor: ResourceConnectionEditor) = action {
        sourceWrites.withLock {
            val save = instances.flow.first().singleOrNull { it.instanceId == editor.save.instanceId }
            check(save == editor.save) { "Connection changed while editing" }
            instances.remove(editor.save.instanceId)
            library.dao.removeCredentials(editor.save.mediaSourceId)
            if (browser.state.value.sourceId == editor.save.mediaSourceId) browser.close()
            selected.update { inputs -> inputs.filterNot { it.identity.sourceId == editor.save.mediaSourceId } }
            connectionEditor.value = null
        }
    }

    fun addPikPak(username: String, password: String) = action {
        sourceWrites.withLock {
            settings.pikpakConfig.update {
                require(username.isNotBlank())
                val changed = this.username != username
                require(!changed || password.isNotBlank())
                copy(username = username, password = password.ifEmpty { if (changed) "" else this.password },
                    refreshToken = if (changed || password.isNotBlank()) "" else refreshToken)
            }
            if (instances.flow.first().none { it.factoryId == PikPakMediaSource.FactoryId }) {
                val id = Uuid.randomString()
                instances.add(MediaSourceSave(id, id, PikPakMediaSource.FactoryId, true,
                    MediaSourceConfig(serializedArguments = Json.encodeToJsonElement(PikPakMediaSourceArguments()))))
            }
            openSource(instances.flow.first().first { it.factoryId == PikPakMediaSource.FactoryId }.mediaSourceId)
        }
    }

    private suspend fun currentScanRoot(): LibraryScanRootEntity? {
        val state = browser.state.value
        val parent = state.path.lastOrNull()?.takeIf { it.kind == MediaSourceEntryKind.DIRECTORY } ?: return null
        return library.dao.scanRoots().first().find {
            it.sourceId == state.sourceId && Json.decodeFromString<MediaResourceRef>(it.referenceJson) == parent.reference
        } ?: LibraryScanRootEntity(Uuid.randomString(), parent.reference.sourceId, Json.encodeToString(parent.reference), parent.name)
            .also { library.dao.upsertScanRoot(it) }
    }

    fun scanCurrent() = action { currentScanRoot()?.let(::scanRoot) }

    fun configureCurrentScanRules() = action {
        currentScanRoot()?.let { scanRules.open(it); subjectQuery.value = it.name }
    }

    fun configureScanRules(root: LibraryScanRootEntity) {
        scanRules.open(root)
        subjectQuery.value = root.name
    }

    fun scanRoot(root: LibraryScanRootEntity) {
        backgroundScope.launch {
            try {
                scanCoordinator.request(root.id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e }
        }
    }

    fun cancelScan(rootId: String) {
        backgroundScope.launch { scanCoordinator.cancel(rootId) }
    }
    fun removeScanRoot(rootId: String) = action {
        scanCoordinator.cancel(rootId)
        library.dao.removeScanRoot(rootId)
    }

    fun removeBinding(resourceId: String, subjectId: Int, episodeId: Int) = action { library.removeBinding(resourceId, subjectId, episodeId) }
    fun removeResource(resourceId: String) = action { library.removeResource(resourceId) }
    fun action(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        backgroundScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e }
            finally { busy.value = false }
        }
    }
}

fun createResourceLibraryViewModel(subjectId: Int? = null, episodeId: Int? = null, initialFiles: List<String> = emptyList()): ResourceLibraryViewModel = KoinPlatform.getKoin().run {
    ResourceLibraryViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), subjectId, episodeId, initialFiles)
}
