package me.him188.ani.app.data.persistent.database.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceLibraryDaoTest {
    private fun test(block: suspend (ResourceLibraryDao) -> Unit) = runBlocking {
        val database = createTestAniDatabase()
        try {
            block(database.resourceLibraryDao())
        } finally {
            database.close()
        }
    }

    private fun resource() = LibraryResourceEntity("video", "disk", "01.mkv", "{}", "01.mkv", "VIDEO")

    @Test
    fun `confirmed association is idempotent without a collection row`() = test { dao ->
        dao.upsertSuggestion(LibraryMatchSuggestionEntity("video", "{}"))
        val binding = LibraryEpisodeBindingEntity("video", "disk", 1, 2, "{}")
        repeat(2) { dao.confirmBinding(resource(), binding) }
        assertEquals(listOf(binding), dao.bindings().first())
        assertTrue(dao.suggestions().first().isEmpty())
        dao.removeBinding("video", 1, 2)
        assertEquals(resource(), dao.findResource("video"))
    }

    @Test
    fun `stale or unfinished scans preserve availability`() = test { dao ->
        dao.upsertResource(resource())
        dao.upsertScanRoot(LibraryScanRootEntity("root", "disk", "{}", "root", activeScanToken = "new"))
        dao.upsertScanEntry(LibraryScanEntryEntity("root", "video", "old"))
        assertFalse(dao.completeScan("root", "old", 10))
        assertTrue(dao.findResource("video")!!.available)
        assertTrue(dao.completeScan("root", "new", 20))
        assertFalse(dao.findResource("video")!!.available)
        assertEquals(20L, dao.findScanRoot("root")!!.lastCompletedMillis)
    }

    @Test
    fun `overlapping root retains resource until both completed scans miss it`() = test { dao ->
        dao.upsertResource(resource())
        for (root in listOf("parent", "child")) {
            dao.upsertScanRoot(LibraryScanRootEntity(root, "disk", "{}", root, activeScanToken = "new"))
            dao.upsertScanEntry(LibraryScanEntryEntity(root, "video", "old"))
        }
        assertTrue(dao.completeScan("parent", "new", 10))
        assertTrue(dao.findResource("video")!!.available)
        assertTrue(dao.completeScan("child", "new", 20))
        assertFalse(dao.findResource("video")!!.available)
    }
}
