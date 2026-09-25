package me.him188.ani.app.ui.resource

import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.data.persistent.database.dao.LibrarySourceCredentialsEntity
import me.him188.ani.app.data.repository.media.MediaSourceInstanceRepository
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.subject.SubjectSearchRepository
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.mediasource.fileservice.FileServiceArguments
import me.him188.ani.app.domain.mediasource.fileservice.FileServiceMediaSource
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.app.domain.mediasource.library.AssociateResourcesUseCase
import me.him188.ani.app.domain.mediasource.library.ResourceAssociationPreviewBuilder
import me.him188.ani.app.domain.mediasource.library.ResourceEpisodeOption
import me.him188.ani.app.domain.mediasource.library.ResourceEpisodeSelection
import me.him188.ani.app.domain.mediasource.library.ResourceEpisodeTarget
import me.him188.ani.app.domain.mediasource.library.ResourceFileIdentity
import me.him188.ani.app.domain.mediasource.library.ResourcePreviewInput
import me.him188.ani.app.domain.mediasource.local.LocalFileMediaSource
import me.him188.ani.app.domain.mediasource.local.LocalFileMediaSourceArguments
import me.him188.ani.app.domain.mediasource.local.LocalResourceAccess
import me.him188.ani.app.domain.mediasource.local.ResourceLibraryScanner
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

class ResourceLibraryViewModel(
    private val manager: MediaSourceManager,
    private val instances: MediaSourceInstanceRepository,
    val library: ResourceLibraryRepository,
    private val subjects: SubjectCollectionRepository,
    private val search: SubjectSearchRepository,
    private val settings: SettingsRepository,
    private val localAccess: LocalResourceAccess,
    private val scanner: ResourceLibraryScanner,
    private val associate: AssociateResourcesUseCase,
    torrentBrowser: TorrentResourceBrowser,
) : AbstractViewModel() {
    val sources = manager.allInstances.stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
    val resources = library.resources.stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
    val bindings = library.bindings.stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
    val roots = library.dao.scanRoots().stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
    val browser = ResourceBrowserController(backgroundScope, torrentBrowser)
    val selected = MutableStateFlow<List<ResourcePreviewInput>>(emptyList())
    val association = MutableStateFlow(ResourceAssociationUiState())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<Throwable?>(null)
    val subjectQuery = MutableStateFlow("")
    val pikpakConfig = settings.pikpakConfig.flow.stateIn(backgroundScope, SharingStarted.Eagerly, PikPakConfig.Default)
    val subjectResults = combine(subjectQuery, settings.uiSettings.flow) { keyword, ui ->
        SubjectSearchQuery(keyword, nsfw = if (ui.searchSettings.nsfwMode == NsfwMode.HIDE) false else null)
    }.flatMapLatest { query ->
        if (query.keywords.isBlank()) flowOf(PagingData.empty()) else search.searchSubjects(query)
    }.cachedIn(backgroundScope)
    private val sourceWrites = Mutex()
    private val preview = ResourceAssociationPreviewBuilder()
    private var subjectLoad: Job? = null
    private val subjectMetadata = mutableMapOf<Int, StateFlow<SubjectCollectionInfo?>>()

    fun subjectDisplayInfo(subjectId: Int) = subjects.getSubjectDisplayInfoOffline(subjectId)

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
        selected.value = listOf(input)
        beginAssociation()
        if (target != null) {
            association.update { it.copy(targets = mapOf(input.identity to target)) }
            chooseSubject(target.subjectId)
        }
    }

    fun beginAssociation() {
        if (selected.value.isEmpty()) return
        subjectLoad?.cancel()
        association.value = ResourceAssociationUiState(visible = true)
        subjectQuery.value = preview.build(selected.value, emptyList()).firstOrNull()?.titleSuggestions?.firstOrNull().orEmpty()
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
        if (inputs.isEmpty() || inputs.any { it.identity !in state.targets }) return
        association.update { it.copy(saving = true, error = null) }
        backgroundScope.launch {
            try {
                associate(inputs.map { input ->
                    val target = state.targets.getValue(input.identity)
                    ResourceEpisodeSelection(input.entry, target.subjectId, target.episodeId, input.selectedFilePath)
                })
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

    fun addLocal(uris: List<String>, directory: Boolean, name: String) = action {
        sourceWrites.withLock {
            val id = if (directory) Uuid.randomString() else "user-local-files"
            val entries = uris.map { localAccess.stat(it) }
            val displayName = if (directory) entries.single().name else name
            val args = LocalFileMediaSourceArguments(displayName)
            if (instances.flow.first().none { it.mediaSourceId == id }) {
                instances.add(MediaSourceSave(id, id, LocalFileMediaSource.FactoryId, true,
                    MediaSourceConfig(serializedArguments = Json.encodeToJsonElement(args))))
            }
            val refs = entries.map { MediaResourceRef(id, it.uri) }
            val source = LocalFileMediaSource(id, localAccess, { refs }, args)
            val picked = mutableListOf<ResourcePreviewInput>()
            for (entry in entries) {
                val item = source.entry(entry.uri)
                val root = library.dao.scanRoots().first().find {
                    it.sourceId == id && Json.decodeFromString<MediaResourceRef>(it.referenceJson) == item.reference
                } ?: LibraryScanRootEntity(Uuid.randomString(), id, Json.encodeToString(item.reference), item.name, recursive = directory)
                library.dao.upsertScanRoot(root)
                if (item.kind.isVideo) {
                    library.index(item)
                    picked += ResourcePreviewInput(item)
                }
                else if (directory) scanner.scan(root, source)
            }
            if (directory) {
                picked += library.dao.resourcesForSource(id).first().filter { it.available }.map { resource ->
                    ResourcePreviewInput(MediaSourceEntry(library.decodeReference(resource), resource.name, MediaSourceEntryKind.VIDEO, resource.size, resource.modifiedTimeMillis))
                }
            }
            manager.allInstances.first { list -> list.any { it.mediaSourceId == id } }
            selected.value = picked
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
        }
    }

    fun scanCurrent() = action {
        val state = browser.state.value
        val parent = state.path.lastOrNull()?.takeIf { it.kind == MediaSourceEntryKind.DIRECTORY } ?: return@action
        val source = sources.value.find { it.mediaSourceId == state.sourceId }?.source as? MediaSourceBrowser ?: return@action
        val root = roots.value.find { it.sourceId == state.sourceId && Json.decodeFromString<MediaResourceRef>(it.referenceJson) == parent.reference }
            ?: LibraryScanRootEntity(Uuid.randomString(), parent.reference.sourceId, Json.encodeToString(parent.reference), parent.name)
        scanner.scan(root, source)
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

fun createResourceLibraryViewModel(): ResourceLibraryViewModel = KoinPlatform.getKoin().run {
    ResourceLibraryViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
}
