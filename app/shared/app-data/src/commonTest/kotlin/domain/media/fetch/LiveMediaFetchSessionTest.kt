/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package me.him188.ani.app.domain.media.fetch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.domain.episode.SubjectMediaFetchSessions
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.selector.DefaultMediaSelector
import me.him188.ani.app.domain.media.selector.MediaSelectorContext
import me.him188.ani.app.domain.mediasource.instance.createTestMediaSourceInstance
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.paging.SinglePagePagedSource
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.datasources.api.source.TestHttpMediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LiveMediaFetchSessionTest {
    private fun request(episode: Int = 1) = MediaFetchRequest(
        subjectId = "1", episodeId = episode.toString(), subjectNames = listOf("Subject"),
        episodeSort = EpisodeSort(episode), episodeName = "Episode $episode",
        episodes = listOf(1, 2).map { MediaFetchRequest.Episode(it.toString(), EpisodeSort(it)) },
    )

    @Test
    fun `confirmed revisions update retained session without querying automatic sources or changing selection`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var fetches = 0
        var confirmed = emptyList<Media>()
        val revision = MutableStateFlow(0L)
        val automatic = TestMediaList.first()
        val bound = TestMediaList[1]
        val source = createTestMediaSourceInstance(TestHttpMediaSource(fetch = {
            fetches++
            SinglePagePagedSource { listOf(MediaMatch(automatic, MatchKind.EXACT)).asFlow() }
        }))
        val fetcher = MediaSourceMediaFetcher(
            { MediaFetcherConfig.Default }, listOf(source), dispatcher,
            confirmedMedia = { _, _ -> confirmed }, confirmedMediaRevision = revision,
        )
        val sessions = SubjectMediaFetchSessions(backgroundScope) { fetcher.newSession(it) }
        val session = sessions.get(request())
        var results = emptyList<Media>()
        backgroundScope.launch { session.cumulativeResults.collect { results = it } }
        val selector = DefaultMediaSelector(
            flowOf(MediaSelectorContext.EmptyForPreview), session.cumulativeResults,
            flowOf(MediaPreference.Empty), flowOf(MediaPreference.Empty), flowOf(MediaSelectorSettings.Default),
            flowCoroutineContext = dispatcher, enableCaching = false,
        )
        runCurrent()
        assertEquals(listOf(automatic), results)
        assertTrue(selector.select(automatic))
        confirmed = listOf(bound)
        revision.value++
        runCurrent()
        assertEquals(listOf(bound, automatic), results)
        assertEquals(automatic, selector.selected.first())
        confirmed = listOf(TestMediaList[2])
        revision.value++
        runCurrent()
        assertEquals(listOf(TestMediaList[2], automatic), results)
        confirmed = emptyList()
        revision.value++
        runCurrent()
        assertEquals(listOf(automatic), results)
        assertEquals(1, fetches)
        assertSame(session, sessions.get(request(2)))
        assertSame(session, sessions.get(request(1)))
        assertEquals(automatic, selector.selected.first())
        assertEquals(1, fetches)
    }

    @Test
    fun `adding and removing an instance preserves a pending network query and edited request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        var starts = 0
        var cancellations = 0
        var addedStarts = 0
        val old = createTestMediaSourceInstance(TestHttpMediaSource(fetch = {
            starts++
            SinglePagePagedSource {
                try { gate.await() } finally { if (!gate.isCompleted) cancellations++ }
                listOf(MediaMatch(TestMediaList[0], MatchKind.EXACT)).asFlow()
            }
        }))
        val added = createTestMediaSourceInstance(TestHttpMediaSource(fetch = {
            addedStarts++
            SinglePagePagedSource { listOf(MediaMatch(TestMediaList[1], MatchKind.EXACT)).asFlow() }
        }))
        val instances = MutableStateFlow(listOf(old))
        val session = MediaSourceMediaFetcher(
            { MediaFetcherConfig.Default }, instances.value, dispatcher, mediaSourceUpdates = instances,
        ).newSession(request())
        var results = emptyList<Media>()
        backgroundScope.launch { session.cumulativeResults.collect { results = it } }
        runCurrent()
        val oldResult = session.mediaSourceResults.single()
        instances.value = listOf(old, added)
        runCurrent()
        assertSame(oldResult, session.mediaSourceResults.first())
        assertEquals(1, starts)
        assertEquals(0, cancellations)
        assertEquals(1, addedStarts)
        assertEquals(listOf(TestMediaList[1]), results)
        instances.value = listOf(old)
        runCurrent()
        assertTrue(results.isEmpty())
        assertEquals(0, cancellations)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(TestMediaList[0]), results)
        val edited = request().copy(subjectNames = listOf("Manually edited"))
        session.setFetchRequest(edited)
        runCurrent()
        assertEquals(2, starts)
        instances.value = listOf(old, added)
        runCurrent()
        assertEquals(edited, session.latestRequest.first())
        assertEquals(2, starts)
    }
    @Test
    fun `removing confirmed override restores original cached automatic metadata`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val automatic = TestMediaList.first()
        val confirmed = automatic.copy(originalTitle = "Confirmed metadata")
        var bindings = listOf<Media>(confirmed)
        var fetches = 0
        val revision = MutableStateFlow(0L)
        val source = createTestMediaSourceInstance(TestHttpMediaSource(fetch = {
            fetches++
            SinglePagePagedSource { listOf(MediaMatch(automatic, MatchKind.FUZZY)).asFlow() }
        }))
        val session = MediaSourceMediaFetcher(
            { MediaFetcherConfig.Default }, listOf(source), dispatcher,
            confirmedMedia = { _, _ -> bindings }, confirmedMediaRevision = revision,
        ).newSession(request())
        var results = emptyList<Media>()
        backgroundScope.launch { session.cumulativeResults.collect { results = it } }
        runCurrent()
        assertSame(confirmed, results.single())
        bindings = emptyList()
        revision.value++
        runCurrent()
        assertSame(automatic, results.single())
        assertEquals(1, fetches)
    }

    @Test
    fun `confirmed results survive failed automatic search and recover from a local read failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val saved = TestMediaList.first()
        var localFailure = false
        var fetches = 0
        val revision = MutableStateFlow(0L)
        val source = createTestMediaSourceInstance(TestHttpMediaSource(fetch = {
            fetches++
            error("Source offline")
        }))
        val session = MediaSourceMediaFetcher(
            { MediaFetcherConfig.Default }, listOf(source), dispatcher,
            confirmedMedia = { _, _ -> check(!localFailure); listOf(saved) }, confirmedMediaRevision = revision,
        ).newSession(request())
        var results = emptyList<Media>()
        backgroundScope.launch { session.cumulativeResults.collect { results = it } }
        runCurrent()
        assertEquals(listOf(saved), results)
        assertTrue(session.mediaSourceResults.single().state.value is MediaSourceFetchState.Failed)
        localFailure = true
        revision.value++
        runCurrent()
        assertTrue(results.isEmpty())
        localFailure = false
        revision.value++
        runCurrent()
        assertEquals(listOf(saved), results)
        assertEquals(1, fetches)
    }
}
