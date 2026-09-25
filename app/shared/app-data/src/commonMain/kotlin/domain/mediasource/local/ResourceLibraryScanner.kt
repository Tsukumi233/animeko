package me.him188.ani.app.domain.mediasource.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.data.repository.media.ResourceLibraryRepository
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceBrowser
import me.him188.ani.utils.platform.Uuid
import me.him188.ani.utils.platform.currentTimeMillis
import kotlinx.serialization.json.Json

/** 扫描用户明确添加的根；只有完整扫描能判定资源缺失。 */
class ResourceLibraryScanner(private val library: ResourceLibraryRepository) {
    suspend fun scan(root: LibraryScanRootEntity, browser: MediaSourceBrowser): Boolean {
        val token = Uuid.randomString()
        library.dao.upsertScanRoot(root.copy(activeScanToken = token, error = null))
        try {
            val rootReference = Json.decodeFromString<MediaResourceRef>(root.referenceJson)
            val pending = ArrayDeque<MediaResourceRef>().apply { add(rootReference) }
            val visited = HashSet<String>()
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val directory = pending.removeFirst()
                if (!visited.add(directory.resourceId)) continue
                var pageToken: String? = null
                val pages = HashSet<String>()
                do {
                    val page = browser.browse(directory, pageToken)
                    for (entry in page.entries) {
                        require(entry.reference.sourceId == root.sourceId) { "Scan result belongs to a different source" }
                        if (entry.kind.isVideo) {
                            if (!library.indexForScan(root.id, token, entry)) return false
                        } else if (entry.kind.isContainer && root.recursive) {
                            pending.add(entry.reference)
                        }
                    }
                    pageToken = page.nextPageToken
                    check(pageToken == null || pages.add(pageToken)) { "Source repeated a page token" }
                } while (pageToken != null)
            }
            return library.dao.completeScan(root.id, token, currentTimeMillis())
        } catch (error: CancellationException) {
            withContext(NonCancellable) { library.dao.failScan(root.id, token, "扫描已取消") }
            throw error
        } catch (error: Exception) {
            library.dao.failScan(root.id, token, error.message ?: "扫描失败")
            throw error
        }
    }
}
