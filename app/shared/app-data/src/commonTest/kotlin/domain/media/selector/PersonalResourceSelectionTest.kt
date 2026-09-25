/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */


package me.him188.ani.app.domain.media.selector

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.domain.media.selector.testFramework.collectEvents
import me.him188.ani.app.domain.media.selector.testFramework.runFetchMediaSelectorTestSuite
import me.him188.ani.app.domain.media.selector.testFramework.runSimpleMediaSelectorTestSuite
import me.him188.ani.app.domain.player.extension.PlayerLoadErrorHandler
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaAssociation
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.test.DisabledOnNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@DisabledOnNative
class PersonalResourceSelectionTest {
    @Test
    fun `personal source type preferences use ordinary selection and manual source events`() {
        for (kind in personalKinds) runSimpleMediaSelectorTestSuite {
            initSubject("Example")
            preferenceApi.savedUserPreference.value = MediaPreference.Any
            preferenceApi.mediaSelectorSettings.value = MediaSelectorSettings.AllVisible.copy(preferKind = kind)
            val candidates = (personalKinds + MediaSourceKind.WEB).map {
                media(sourceId = it.name, kind = it, subjectName = "Example")
            }
            mediaApi.addMedia(*candidates.toTypedArray())
            assertEquals(kind, selector.trySelectDefault()?.kind)
            selector.unselect()
            val target = candidates.single { it.kind == kind }
            val events = selector.collectEvents { selector.select(target) }
            assertEquals(target.mediaSourceId, events.onPreferWebSource.single().event.mediaSourceId)
        }
    }

    @Test
    fun `each personal source kind can be remembered ahead of default kind`() {
        for (kind in personalKinds) runFetchMediaSelectorTestSuite {
            initSubject("Example")
            preferenceApi.savedUserPreference.value = MediaPreference.Any
            preferenceApi.mediaSelectorSettings.value = MediaSelectorSettings.AllVisible.copy(preferKind = MediaSourceKind.WEB)
            val (_, session, sources) = configureFetchSession {
                object {
                    val personal by source(kind)
                    val online by web()
                }
            }
            val selection = testScope().async(start = CoroutineStart.UNDISPATCHED) {
                MediaAutoSelector(selector).select(session, MediaAutoSelector.Config(preferredSourceId = "personal"))
            }
            sources.online.complete(media(kind = MediaSourceKind.WEB, subjectName = "Example"))
            testScope().runCurrent()
            assertFalse(selection.isCompleted)
            sources.personal.complete(media(kind = kind, subjectName = "Example"))
            testScope().runCurrent()
            assertEquals("personal", selection.await()?.mediaSourceId)
        }
    }

    @Test
    fun `empty personal source falls back to later online results`() {
        for (kind in personalKinds) runFetchMediaSelectorTestSuite {
            initSubject("Example")
            preferenceApi.savedUserPreference.value = MediaPreference.Any
            preferenceApi.mediaSelectorSettings.value = MediaSelectorSettings.AllVisible.copy(preferKind = kind)
            val (_, session, sources) = configureFetchSession {
                object {
                    val personal by source(kind)
                    val online by web()
                }
            }
            val selection = testScope().async(start = CoroutineStart.UNDISPATCHED) {
                MediaAutoSelector(selector).select(session)
            }
            sources.personal.complete(emptyList<Media>())
            testScope().runCurrent()
            assertFalse(selection.isCompleted)
            sources.online.complete(media(kind = MediaSourceKind.WEB, subjectName = "Example"))
            testScope().runCurrent()
            assertEquals("online", selection.await()?.mediaSourceId)
        }
    }

    @Test
    fun `failed personal resource falls back without writing preferences`() {
        for (kind in personalKinds) runFetchMediaSelectorTestSuite {
            initSubject("Example")
            preferenceApi.savedUserPreference.value = MediaPreference.Any
            preferenceApi.mediaSelectorSettings.value = MediaSelectorSettings.AllVisible.copy(preferKind = kind)
            val (_, session, sources) = configureFetchSession {
                object {
                    val personal by source(kind)
                    val online by web()
                }
            }
            sources.personal.complete(media(kind = kind, subjectName = "Example"))
            sources.online.complete(media(kind = MediaSourceKind.WEB, subjectName = "Example"))
            testScope().runCurrent()
            selector.select(selector.filteredCandidatesMedia.first().first { it.kind == kind })
            val handler = PlayerLoadErrorHandler(getPreferKind = { kind }, getSourceTiers = { preferenceApi.sourceTiers!! })
            val events = selector.collectEvents {
                val replacement = testScope().async { handler.handleError(session, selector) }
                testScope().advanceUntilIdle()
                replacement.await()
            }
            assertEquals("online", selector.selected.value?.mediaSourceId)
            assertEquals(0, events.onChangePreference.size)
            assertEquals(0, events.onPreferWebSource.size)
        }
    }

    @Test
    fun `confirmed association overrides title and BT hiding but respects subtitles and episode identity`() =
        runSimpleMediaSelectorTestSuite {
            initSubject("Example")
            val context = preferenceApi.mediaSelectorContext.value
            preferenceApi.mediaSelectorContext.value = context.copy(subjectInfo = context.subjectInfo!!.copy(subjectId = 123))
            preferenceApi.mediaSelectorSettings.value = MediaSelectorSettings.AllVisible.copy(hideSingleEpisodeForCompleted = true)
            preferenceApi.savedDefaultPreference.value = MediaPreference.Any.copy(showWithoutSubtitle = false)
            preferenceApi.savedUserPreference.value = MediaPreference.Any.copy(showWithoutSubtitle = false)
            val bound = media(subjectName = "Completely different title").copy(
                association = MediaAssociation("123", listOf(context.episodeInfo!!.episodeId.toString())),
            )
            val noSubtitle = bound.copy(mediaId = "no-subtitle", properties = bound.properties.copy(subtitleLanguageIds = emptyList()))
            val anotherEpisode = bound.copy(mediaId = "another-episode", association = MediaAssociation("123", listOf("99999")))
            mediaApi.addMedia(bound, noSubtitle, anotherEpisode)
            val results = selector.filteredCandidates.first()
            val included = results.filterIsInstance<MaybeExcludedMedia.Included>().single()
            assertEquals(bound.mediaId, included.result.mediaId)
            assertEquals(MatchMetadata.SubjectMatchKind.EXACT, included.metadata.subjectMatchKind)
            assertEquals(setOf(MediaExclusionReason.MediaWithoutSubtitle::class, MediaExclusionReason.EpisodeMismatch::class),
                results.filterIsInstance<MaybeExcludedMedia.Excluded>().map { it.exclusionReason::class }.toSet())
        }

    context(scope: TestScope)
    private fun testScope(): TestScope = implicit()

    private companion object {
        val personalKinds = listOf(MediaSourceKind.LocalFile, MediaSourceKind.FileService, MediaSourceKind.CloudDrive)
    }
}
