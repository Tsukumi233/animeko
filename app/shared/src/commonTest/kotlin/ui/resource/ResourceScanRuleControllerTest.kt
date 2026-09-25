package me.him188.ani.app.ui.resource

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.createTestSubjectCollection
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.domain.mediasource.library.ConfirmedResourceMatchingRules
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(TestOnly::class)
class ResourceScanRuleControllerTest {
    private val parent = MediaResourceRef("source", "folder", "account/folder")
    private fun root(id: String = "root") = LibraryScanRootEntity(id, "source", Json.encodeToString(parent), "Show")
    private fun episode(id: Int, sort: EpisodeSort, season: EpisodeSort? = sort) = EpisodeCollectionInfo(
        EpisodeInfo.Empty.copy(episodeId = id, sort = sort, ep = season), UnifiedCollectionType.NOT_COLLECTED,
    )
    private fun subject(id: Int = 1) = createTestSubjectCollection(id, listOf(
        episode(11, EpisodeSort(13), EpisodeSort(1)), episode(12, EpisodeSort(14), EpisodeSort(2)),
        episode(13, EpisodeSort(1, EpisodeType.SP), null),
    ), UnifiedCollectionType.NOT_COLLECTED)

    @Test fun `scope and all title choices are explicit and edits invalidate acknowledgement`() = runTest {
        var saved: ConfirmedResourceMatchingRules? = null
        val controller = ResourceScanRuleController(backgroundScope, { subject() }, { _, rules -> saved = rules }, {})
        controller.open(root())
        controller.chooseSubject(1)
        runCurrent()
        controller.edit { it.copy(acceptedTitles = "") }
        controller.confirmScope(true)
        assertFalse(controller.state.value.canSave)
        controller.edit { it.copy(acceptAllTitles = true) }
        assertFalse(controller.state.value.canSave)
        controller.confirmScope(true)
        controller.save()
        runCurrent()
        assertEquals(emptySet(), assertNotNull(saved).rules.single().acceptedTitles)
        assertEquals(parent.locator, saved!!.rules.single().parentLocator)
        assertEquals(3, saved!!.rules.single().mappings.size)
        assertNull(controller.state.value.root)
    }

    @Test fun `season numbering and special episodes retain typed mappings`() {
        val state = ResourceScanRuleState(root = root(), subject = subject(), acceptAllTitles = true, scopeConfirmed = true)
        assertEquals(listOf(EpisodeSort(13), EpisodeSort(14), EpisodeSort(1, EpisodeType.SP)), state.mappings.map { it.sort })
        assertEquals(listOf(EpisodeSort(1), EpisodeSort(2), EpisodeSort(1, EpisodeType.SP)), state.copy(seasonNumbers = true).mappings.map { it.sort })
        val duplicate = state.copy(subject = subject().copy(episodes = listOf(episode(11, EpisodeSort(1)), episode(12, EpisodeSort(1)))))
        assertFalse(duplicate.canSave)
        assertTrue(duplicate.copy(excludedEpisodes = setOf(12)).canSave)
    }

    @Test fun `late subject response cannot overwrite a newer choice in the same dialog`() = runTest {
        val release = CompletableDeferred<Unit>()
        val controller = ResourceScanRuleController(backgroundScope, { id ->
            if (id == 1) withContext(NonCancellable) { release.await() }
            subject(id)
        }, { _, _ -> }, {})
        controller.open(root())
        controller.chooseSubject(1)
        runCurrent()
        controller.chooseSubject(2)
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(2, controller.state.value.subject!!.subjectId)
    }

    @Test fun `closing or changing folders discards late metadata and failures remain retryable`() = runTest {
        val release = CompletableDeferred<Unit>()
        val controller = ResourceScanRuleController(backgroundScope, { withContext(NonCancellable) { release.await(); subject() } },
            { _, _ -> error("offline") }, {})
        controller.open(root())
        controller.chooseSubject(1)
        runCurrent()
        controller.confirmScope(true)
        assertFalse(controller.state.value.scopeConfirmed)
        controller.open(root("another"))
        release.complete(Unit)
        runCurrent()
        assertNull(controller.state.value.subject)
        controller.chooseSubject(1)
        runCurrent()
        controller.confirmScope(true)
        controller.save()
        runCurrent()
        assertNotNull(controller.state.value.error)
        assertTrue(controller.state.value.canSave)
        assertEquals("another", controller.state.value.root!!.id)
    }
}
