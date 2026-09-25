package me.him188.ani.app.domain.mediasource.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.app.domain.mediasource.library.ApplyScanMatchingRulesUseCase
import me.him188.ani.app.domain.mediasource.library.ResourceFileIdentity
import me.him188.ani.app.domain.mediasource.library.ResourcePreviewInput
import me.him188.ani.app.domain.mediasource.torrent.TorrentResourceFile
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceBrowser
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.source.TorrentMediaSourceReferences
import me.him188.ani.utils.platform.Uuid
import me.him188.ani.utils.platform.currentTimeMillis

/** 扫描用户明确添加的根；完整枚举后才保存建议、应用确认规则和判定缺失。 */
class ResourceLibraryScanner(
    private val library: ResourceLibraryRepository,
    private val applyRules: ApplyScanMatchingRulesUseCase,
    private val torrentFiles: suspend (MediaResourceRef) -> List<TorrentResourceFile>,
) {
    suspend fun scan(root: LibraryScanRootEntity, browser: MediaSourceBrowser): Boolean {
        val token = Uuid.randomString()
        val snapshot = library.dao.beginScan(root, token) ?: return false
        try {
            val rootReference = Json.decodeFromString<MediaResourceRef>(snapshot.referenceJson)
            require(rootReference.sourceId == snapshot.sourceId)
            val inputs = linkedMapOf<ResourceFileIdentity, ResourcePreviewInput>()
            suspend fun record(input: ResourcePreviewInput): Boolean {
                val previous = inputs[input.identity]
                if (previous == null) inputs[input.identity] = input
                require(previous == null || previous == input) { "Source returned conflicting versions of one file" }
                return library.indexForScan(snapshot.id, token, input.entry)
            }
            suspend fun torrent(entry: MediaSourceEntry): Boolean {
                if (!library.indexForScan(snapshot.id, token, entry)) return false
                for (file in torrentFiles(entry.reference).filter { it.isVideo }) {
                    currentCoroutineContext().ensureActive()
                    if (!record(ResourcePreviewInput(entry, file.pathInTorrent, entry.name, entry.reference))) return false
                }
                return true
            }
            val torrentRoot = runCatching { TorrentMediaSourceReferences.decode(rootReference) }.getOrNull()
            if (torrentRoot != null) {
                if (!torrent(MediaSourceEntry(rootReference, snapshot.name, MediaSourceEntryKind.TORRENT))) return false
            } else {
                val pending = ArrayDeque<Pair<MediaResourceRef, String>>().apply { add(rootReference to snapshot.name) }
                val visited = HashSet<MediaResourceRef>()
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val (directory, name) = pending.removeFirst()
                    if (!visited.add(directory)) continue
                    var pageToken: String? = null
                    val pages = HashSet<String>()
                    do {
                        val page = browser.browse(directory, pageToken)
                        for (entry in page.entries) {
                            currentCoroutineContext().ensureActive()
                            require(entry.reference.sourceId == snapshot.sourceId) { "Scan result belongs to a different source" }
                            when {
                                entry.kind.isVideo -> if (!record(ResourcePreviewInput(entry, folderName = name, parentReference = directory))) return false
                                entry.kind == MediaSourceEntryKind.TORRENT && snapshot.recursive -> if (!torrent(entry)) return false
                                entry.kind.isContainer && snapshot.recursive -> pending.add(entry.reference to entry.name)
                            }
                        }
                        pageToken = page.nextPageToken
                        check(pageToken == null || pages.add(pageToken)) { "Source repeated a page token" }
                    } while (pageToken != null)
                }
            }
            currentCoroutineContext().ensureActive()
            return applyRules(snapshot, inputs.values.toList(), currentTimeMillis()).also { committed ->
                if (!committed) library.dao.failScan(snapshot.id, token, "扫描结果已失效，请重新扫描")
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) { library.dao.failScan(snapshot.id, token, "扫描已取消") }
            throw error
        } catch (error: Exception) {
            library.dao.failScan(snapshot.id, token, error.message ?: "扫描失败")
            throw error
        }
    }
}
