package me.him188.ani.app.ui.resource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.him188.ani.app.domain.mediasource.library.ResourcePreviewInput
import me.him188.ani.app.domain.mediasource.torrent.TorrentResourceBrowser
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceBrowser
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.source.MediaSourceSearchScope
import me.him188.ani.utils.platform.Uuid

data class ResourceBrowserState(
    val requestId: String = "",
    val sourceId: String? = null,
    val sourceName: String = "",
    val path: List<MediaSourceEntry> = emptyList(),
    val rootReference: MediaResourceRef? = null,
    val searchesIndex: Boolean = false,
    val query: String = "",
    val rows: List<ResourcePreviewInput> = emptyList(),
    val nextPageToken: String? = null,
    val searchScope: MediaSourceSearchScope = MediaSourceSearchScope.NONE,
    val needsSearch: Boolean = false,
    val loading: Boolean = false,
    val error: Throwable? = null,
    val loadedPageTokens: Set<String> = emptySet(),
)

/** 每次导航拥有独立请求身份，已取消或迟到的响应不能覆盖当前目录。 */
class ResourceBrowserController(
    private val scope: CoroutineScope,
    private val torrentBrowser: TorrentResourceBrowser,
    private val searchIndex: (suspend (sourceId: String, query: String) -> List<ResourcePreviewInput>)? = null,
) {
    private val mutableState = MutableStateFlow(ResourceBrowserState())
    val state = mutableState.asStateFlow()
    private var browser: MediaSourceBrowser? = null
    private var job: Job? = null

    fun open(sourceId: String, name: String, browser: MediaSourceBrowser) {
        this.browser = browser
        load(ResourceBrowserState(sourceId = sourceId, sourceName = name, searchScope = browser.searchScope))
    }

    fun close() {
        job?.cancel()
        browser = null
        mutableState.value = ResourceBrowserState()
    }

    fun enter(entry: MediaSourceEntry) {
        require(entry.kind.isContainer && entry.reference.sourceId == state.value.sourceId)
        load(state.value.copy(path = state.value.path + entry, query = "", rows = emptyList(), nextPageToken = null))
    }

    fun back() {
        if (state.value.path.isEmpty() || (state.value.rootReference != null && state.value.path.singleOrNull()?.reference == state.value.rootReference)) close()
        else load(state.value.copy(path = state.value.path.dropLast(1), query = "", rows = emptyList(), nextPageToken = null))
    }

    fun search(query: String) = load(state.value.copy(query = query, rows = emptyList(), nextPageToken = null))
    fun refresh() = load(state.value.copy(rows = emptyList(), nextPageToken = null))
    fun more() {
        if (!state.value.loading && state.value.nextPageToken != null) load(state.value, append = true)
    }

    private fun load(target: ResourceBrowserState, append: Boolean = false) {
        val browser = browser ?: return
        job?.cancel()
        val id = Uuid.randomString()
        val parent = target.path.lastOrNull()
        val inTorrent = parent?.kind == MediaSourceEntryKind.TORRENT
        val needsSearch = !inTorrent && parent == null && !browser.supportsRootBrowse && target.query.isBlank()
        val visited = if (append) target.loadedPageTokens + listOfNotNull(target.nextPageToken) else emptySet()
        val searchesIndex = !inTorrent && browser.searchScope == MediaSourceSearchScope.NONE && searchIndex != null
        val loading = target.copy(requestId = id, loading = !needsSearch, error = null, needsSearch = needsSearch, loadedPageTokens = visited,
            searchScope = if (inTorrent) MediaSourceSearchScope.NONE else if (searchesIndex) MediaSourceSearchScope.SOURCE else browser.searchScope,
            searchesIndex = searchesIndex)
        mutableState.value = loading
        if (needsSearch) return
        job = scope.launch {
            try {
                var resolved = loading
                if (parent == null && target.query.isBlank() && browser.supportsRootBrowse) {
                    browser.rootEntry()?.let { root ->
                        require(root.reference.sourceId == target.sourceId && root.kind == MediaSourceEntryKind.DIRECTORY) { "Invalid source root" }
                        resolved = loading.copy(path = listOf(root), rootReference = root.reference)
                        mutableState.update { if (it.requestId == id) resolved else it }
                    }
                }
                currentCoroutineContext().ensureActive()
                val currentParent = resolved.path.lastOrNull()
                val rows: List<ResourcePreviewInput>
                val nextToken: String?
                if (inTorrent) {
                    val listing = torrentBrowser.browse(parent!!.reference)
                    rows = listing.files.map { file -> ResourcePreviewInput(parent, file.pathInTorrent) }
                    nextToken = null
                } else if (searchesIndex && target.query.isNotBlank()) {
                    rows = requireNotNull(searchIndex)(requireNotNull(target.sourceId), target.query)
                    require(rows.all { it.entry.reference.sourceId == target.sourceId }) { "Index returned foreign entries" }
                    nextToken = null
                } else {
                    val token = if (append) target.nextPageToken else null
                    val page = if (target.query.isBlank()) browser.browse(currentParent?.reference, token)
                    else browser.search(target.query, currentParent?.reference, token)
                    require(page.entries.all { it.reference.sourceId == target.sourceId }) { "Source returned foreign entries" }
                    check(page.nextPageToken == null || page.nextPageToken !in visited) { "Source repeated a page token" }
                    rows = page.entries.map { ResourcePreviewInput(it, folderName = currentParent?.name, parentReference = currentParent?.reference ?: it.parent) }
                    nextToken = page.nextPageToken
                }
                mutableState.update { current ->
                    if (current.requestId != id) current else resolved.copy(
                        rows = ((if (append) target.rows else emptyList()) + rows).distinctBy { it.identity },
                        nextPageToken = nextToken, loading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { if (it.requestId == id) it.copy(loading = false, error = e) else it }
            }
        }
    }
}
