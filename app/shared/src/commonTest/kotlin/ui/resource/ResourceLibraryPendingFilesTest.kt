package me.him188.ani.app.ui.resource

import kotlinx.serialization.json.Json
import me.him188.ani.app.data.persistent.database.dao.LibraryEpisodeBindingEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryMatchSuggestionEntity
import me.him188.ani.app.data.persistent.database.dao.LibraryResourceEntity
import me.him188.ani.app.domain.mediasource.library.ResourceEpisodeTarget
import me.him188.ani.app.domain.mediasource.library.StoredResourceMatchSuggestion
import me.him188.ani.app.domain.mediasource.library.StoredScanMatchSuggestions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceLibraryPendingFilesTest {
    private val release = LibraryResourceEntity("r", "bt", "release", "{}", "Collection", "TORRENT")
    private fun binding(path: String) = LibraryEpisodeBindingEntity("r", "bt", 1, 11, "{}", path)
    private fun row(path: String, status: String = "SUGGESTED") = StoredResourceMatchSuggestion(path, emptyList(), null, status, emptyList(), null)
    private fun saved(payload: StoredScanMatchSuggestions, ignored: Boolean = false) = LibraryMatchSuggestionEntity("r", Json.encodeToString(payload), ignored)

    @Test fun `confirmation restores only one unambiguous target and respects skipped paths`() {
        val target = ResourceEpisodeTarget(1, 11)
        val match = row("A/01.mkv").copy(targets = listOf(target))
        assertEquals(target, suggestedLibraryTarget(saved(StoredScanMatchSuggestions(rows = listOf(match))), "A/01.mkv"))
        assertEquals(null, suggestedLibraryTarget(saved(StoredScanMatchSuggestions(rows = listOf(match))), "B/01.mkv"))
        assertEquals(null, suggestedLibraryTarget(saved(StoredScanMatchSuggestions(setOf("A/01.mkv"), listOf(match)), true), "A/01.mkv"))
        assertEquals(null, suggestedLibraryTarget(saved(StoredScanMatchSuggestions(rows = listOf(match.copy(status = "AMBIGUOUS")))), "A/01.mkv"))
    }

    @Test fun `partial confirmation retains other exact paths and skipped decisions`() {
        val payload = StoredScanMatchSuggestions(setOf("A/01.mkv", "B/01.mkv"),
            listOf(row("A/01.mkv"), row("B/01.mkv"), row("C/01.mkv", "AMBIGUOUS")), catalogComplete = true)
        val pending = pendingLibraryFiles(listOf(release), listOf(binding("A/01.mkv")), listOf(saved(payload, true)))
        assertEquals(listOf("B/01.mkv", "C/01.mkv"), pending.map { it.filePath })
        assertEquals(listOf(ResourcePendingStatus.IGNORED, ResourcePendingStatus.AMBIGUOUS), pending.map { it.status })
    }

    @Test fun `incomplete catalogue remains browsable after its known file is bound`() {
        val pending = pendingLibraryFiles(listOf(release), listOf(binding("A/01.mkv")), emptyList())
        assertEquals(ResourcePendingStatus.UNKNOWN_FILES, pending.single().status)
        assertEquals(null, pending.single().filePath)
    }

    @Test fun `complete bound catalogue disappears and removed binding becomes pending`() {
        val suggestion = saved(StoredScanMatchSuggestions(rows = listOf(row("A/01.mkv", "CONFIRMED")), catalogComplete = true))
        assertTrue(pendingLibraryFiles(listOf(release), listOf(binding("A/01.mkv")), listOf(suggestion)).isEmpty())
        assertEquals(ResourcePendingStatus.UNRECOGNIZED,
            pendingLibraryFiles(listOf(release), emptyList(), listOf(suggestion)).single().status)
    }

    @Test fun `unsupported stored decisions are explicit and cannot become file associations`() {
        val pending = pendingLibraryFiles(listOf(release), emptyList(), listOf(LibraryMatchSuggestionEntity("r", """{"version":2}""")))
        assertEquals(ResourcePendingStatus.INVALID_STATE, pending.single().status)
        assertEquals(null, pending.single().filePath)
    }
}
