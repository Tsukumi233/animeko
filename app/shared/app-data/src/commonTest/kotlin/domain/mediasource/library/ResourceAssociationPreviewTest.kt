/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.mediasource.library

import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResourceAssociationPreviewTest {
    private val builder = ResourceAssociationPreviewBuilder()
    private val first = ResourceEpisodeTarget(1, 11)
    private val second = ResourceEpisodeTarget(2, 21)
    private val main = EpisodeSort(1)
    private val special = EpisodeSort("SP01")
    private val options get() = listOf(ResourceEpisodeOption(first, main), ResourceEpisodeOption(second, special))
    private fun input(id: String, name: String = "[Group] Show - 01 [1080p].mkv", parent: String = "folder") = ResourcePreviewInput(
        MediaSourceEntry(MediaResourceRef("source", id), name, MediaSourceEntryKind.VIDEO,
            parent = MediaResourceRef("source", parent)),
    )
    private fun rules(vararg rules: ConfirmedResourceMatchingRule) = ConfirmedResourceMatchingRules(rules = rules.toList())
    private fun rule(id: String = "one", target: ResourceEpisodeTarget = first, parent: String = "folder") =
        ConfirmedResourceMatchingRule(id, "source", parent, listOf(ConfirmedResourceEpisodeMapping(main, target)))

    @Test
    fun `real filename parser only suggests on first scan`() {
        val result = builder.build(listOf(input("a")), listOf(ResourceEpisodeOption(first, main))).single()
        assertEquals(ResourcePreviewStatus.SUGGESTED, result.status)
        assertEquals(main, result.episodeSort)
        assertTrue(result.titleSuggestions.isNotEmpty())
    }

    @Test
    fun `only one confirmed scoped typed rule allows automatic assignment`() {
        val result = builder.build(listOf(input("a")), options, confirmedRules = rules(rule())).single()
        assertEquals(ResourcePreviewStatus.AUTO_ASSIGNABLE, result.status)
        assertEquals(listOf(first), result.targets)
        assertEquals("one", result.matchedRuleId)
        assertEquals(ResourcePreviewStatus.UNRECOGNIZED,
            builder.build(listOf(input("a", parent = "different")), emptyList(), confirmedRules = rules(rule())).single().status)
        assertEquals(ResourcePreviewStatus.AMBIGUOUS,
            builder.build(listOf(input("a")), emptyList(), confirmedRules = rules(rule(), rule("two"))).single().status)
    }

    @Test
    fun `special and main episodes with same number remain different`() {
        val source = input("special", "[Group] Show [SP01] [1080p].mkv")
        val result = builder.build(listOf(source), listOf(ResourceEpisodeOption(first, main), ResourceEpisodeOption(second, special))).single()
        assertEquals(special, result.episodeSort)
        assertEquals(listOf(second), result.targets)
        assertEquals(ResourcePreviewStatus.SUGGESTED, result.status)
    }

    @Test
    fun `manual corrections and ignored files survive rule and filename changes`() {
        val corrected = input("a")
        val ignored = input("b")
        val rows = builder.build(listOf(corrected, ignored), emptyList(), listOf(
            ProtectedResourceDecision(corrected.identity, ProtectedResourceDecisionKind.CONFIRMED, second),
            ProtectedResourceDecision(ignored.identity, ProtectedResourceDecisionKind.IGNORED),
        ), rules(rule()))
        assertEquals(ResourcePreviewStatus.CONFIRMED, rows[0].status)
        assertEquals(listOf(second), rows[0].targets)
        assertEquals(ResourcePreviewStatus.IGNORED, rows[1].status)
    }

    @Test
    fun `duplicate versions and occupied episodes cannot autoassign`() {
        val rows = builder.build(listOf(input("a"), input("b")), options, confirmedRules = rules(rule()))
        assertTrue(rows.all { it.status == ResourcePreviewStatus.AMBIGUOUS })
        val protected = ProtectedResourceDecision(input("old").identity, ProtectedResourceDecisionKind.CONFIRMED, first)
        assertEquals(ResourcePreviewStatus.AMBIGUOUS,
            builder.build(listOf(input("new")), options, listOf(protected), rules(rule())).single().status)
    }

    @Test
    fun `cross season ambiguity requires an explicit mapping or explicit order`() {
        val result = builder.build(listOf(input("a")), listOf(ResourceEpisodeOption(first, main), ResourceEpisodeOption(second, main))).single()
        assertEquals(ResourcePreviewStatus.AMBIGUOUS, result.status)
        assertEquals(listOf(first, second), result.targets)
        val order = builder.assignInOrder(listOf(input("a"), input("b")), listOf(second, first))
        assertEquals(second, order[input("a").identity])
        assertFailsWith<IllegalArgumentException> { builder.assignInOrder(listOf(input("a")), listOf(first, second)) }
    }

    @Test
    fun `subtitles and multi episode files cannot receive automatic associations`() {
        val subtitle = input("sub").let { it.copy(entry = it.entry.copy(name = "Show - 01.ass", kind = MediaSourceEntryKind.FILE)) }
        val rows = builder.build(listOf(subtitle, input("batch", "[Group] Show [01-12] [1080p].mkv")), emptyList(), confirmedRules = rules(rule()))
        assertEquals(ResourcePreviewStatus.NOT_VIDEO, rows[0].status)
        assertEquals(ResourcePreviewStatus.MULTIPLE_EPISODES, rows[1].status)
    }

    @Test
    fun `rule json preserves typed mappings and rejects unknown versions`() {
        val value = rules(rule().copy(mappings = listOf(ConfirmedResourceEpisodeMapping(main, first), ConfirmedResourceEpisodeMapping(special, second))))
        assertEquals(value, ConfirmedResourceMatchingRules.decode(value.encode()))
        assertFailsWith<IllegalArgumentException> { ConfirmedResourceMatchingRules(2, emptyList()) }
        assertFailsWith<IllegalArgumentException> { rule().copy(mappings = listOf(ConfirmedResourceEpisodeMapping(main, first), ConfirmedResourceEpisodeMapping(main, second))) }
        assertNotEquals(main, special)
    }

    @Test
    fun `fractional website episode and dotted title retain their original text`() {
        val name = "Show.v2 - 23.5"
        val result = builder.build(listOf(input("web", name)), listOf(ResourceEpisodeOption(first, EpisodeSort("23.5")))).single()
        assertEquals(EpisodeSort("23.5"), result.episodeSort)
        assertEquals(ResourcePreviewStatus.SUGGESTED, result.status)
        assertTrue(result.titleSuggestions.any { "Show.v2" in it })
    }

    @Test
    fun `confirmed rule with an absent target cannot automatically assign`() {
        val result = builder.build(listOf(input("new")), emptyList(), confirmedRules = rules(rule())).single()
        assertEquals(ResourcePreviewStatus.UNRECOGNIZED, result.status)
        assertTrue(result.targets.isEmpty())
    }

    @Test
    fun `provider account scope changes cannot reuse a confirmed directory rule`() {
        val original = input("a").copy(parentReference = MediaResourceRef("source", "folder", "account-one/folder"))
        val confirmed = rules(ConfirmedResourceMatchingRule.forParent("rule", requireNotNull(original.parentReference), rule().mappings))
        assertEquals(ResourcePreviewStatus.AUTO_ASSIGNABLE, builder.build(listOf(original), options, confirmedRules = confirmed).single().status)
        val changed = original.copy(parentReference = MediaResourceRef("source", "folder", "account-two/folder"))
        assertEquals(ResourcePreviewStatus.SUGGESTED, builder.build(listOf(changed), options, confirmedRules = confirmed).single().status)
    }
}
