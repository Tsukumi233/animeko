package me.him188.ani.app.data.persistent.database.dao

import androidx.room.useWriterConnection
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceLibraryIncrementalScanTest {
    @Test fun `unchanged completed scans touch neither resource nor suggestion rows`() = runBlocking {
        val database = createTestAniDatabase()
        try {
            val dao = database.resourceLibraryDao()
            val resource = LibraryResourceEntity("video", "source", "file", "{}", "01.mkv", "VIDEO")
            val suggestion = LibraryMatchSuggestionEntity("video", "suggestion")
            val saved = LibraryScanRootEntity("root", "source", "{}", "Root")
            dao.upsertScanRoot(saved)
            val first = requireNotNull(dao.beginScan(saved, "first"))
            assertTrue(dao.recordScanEntry("root", "first", resource))
            assertTrue(dao.completeScanWithMatches(first, emptyList(), emptyList(), listOf(resource), emptyList(), listOf(suggestion), 1, null))
            database.useWriterConnection { connection ->
                connection.usePrepared("CREATE TABLE scan_test_writes (table_name TEXT)") { it.step() }
                for (table in listOf("library_resource", "library_match_suggestion")) {
                    for (action in listOf("INSERT", "UPDATE", "DELETE")) {
                        connection.usePrepared("CREATE TRIGGER count_${table}_$action AFTER $action ON $table BEGIN INSERT INTO scan_test_writes VALUES ('$table'); END") { it.step() }
                    }
                }
            }
            val second = requireNotNull(dao.beginScan(requireNotNull(dao.findScanRoot("root")), "second"))
            repeat(2) { assertTrue(dao.recordScanEntry("root", "second", resource)) }
            assertTrue(dao.completeScanWithMatches(second, emptyList(), listOf(suggestion), listOf(resource), emptyList(), listOf(suggestion), 2, null))
            val unchangedWrites = database.useWriterConnection { connection ->
                connection.usePrepared("SELECT COUNT(*) FROM scan_test_writes") { it.step(); it.getLong(0) }
            }
            assertEquals(0L, unchangedWrites)
            val changed = resource.copy(name = "Renamed 01.mkv", size = 4096)
            val third = requireNotNull(dao.beginScan(requireNotNull(dao.findScanRoot("root")), "third"))
            assertTrue(dao.recordScanEntry("root", "third", changed))
            val changedSuggestion = suggestion.copy(suggestionJson = "updated suggestion")
            assertTrue(dao.completeScanWithMatches(third, emptyList(), listOf(suggestion), listOf(changed), emptyList(), listOf(changedSuggestion), 3, null))
            val changedWrites = database.useWriterConnection { connection ->
                connection.usePrepared("SELECT COUNT(*) FROM scan_test_writes") { it.step(); it.getLong(0) }
            }
            assertEquals(2L, changedWrites)
            assertEquals(changed, dao.findResource("video"))
            val missing = requireNotNull(dao.beginScan(requireNotNull(dao.findScanRoot("root")), "missing"))
            assertTrue(dao.completeScanWithMatches(missing, emptyList(), listOf(changedSuggestion), emptyList(), emptyList(), emptyList(), 4, null))
            assertEquals(false, dao.findResource("video")?.available)
        } finally { database.close() }
    }
}
