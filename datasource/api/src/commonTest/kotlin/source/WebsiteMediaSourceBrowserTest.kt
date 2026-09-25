/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.datasources.api.source

import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.platform.annotations.SerializationOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

@OptIn(SerializationOnly::class)
class WebsiteMediaSourceBrowserTest {
    private val media = DefaultMedia("test.original", "test", "https://site/subject", ResourceLocation.MagnetLink("magnet:?xt=urn:btih:abc"),
        "special", 123L, MediaProperties(subtitleLanguageIds = emptyList(), resolution = "", alliance = ""))
    private val request = MediaFetchRequest("subject", "episode", subjectNames = listOf("unrelated"),
        episodeSort = EpisodeSort(9), episodeName = "")

    private inner class Site : MediaSource {
        override val mediaSourceId = "test"
        override val kind = MediaSourceKind.WEB
        override val info = MediaSourceInfo("test")
        override suspend fun checkConnection() = ConnectionStatus.SUCCESS
        override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> = error("Automatic search is disabled")
        var keyword: String? = null
        var selected: BrowseEpisode? = null
        var channels = listOf(
            BrowseChannel("same", episodes = listOf(BrowseEpisode("SP", "https://site/a"))),
            BrowseChannel("same", episodes = listOf(BrowseEpisode("SP", "https://site/b"))),
        )
        override suspend fun searchSubjects(keyword: String): List<BrowseSubject> {
            this.keyword = keyword
            return listOf(BrowseSubject("other name", "https://site/subject"))
        }
        override suspend fun browseSubject(subject: BrowseSubject) = channels
        override fun createMedia(subject: BrowseSubject, channelName: String?, episode: BrowseEpisode, episodeSort: EpisodeSort?): Media {
            selected = episode
            assertEquals(request.episodeSort, episodeSort)
            return media
        }
    }

    @Test
    fun `manual keyword and SP selection bypass automatic search`() = runTest {
        val site = Site()
        val browser = WebsiteMediaSourceBrowser(site)
        val subject = browser.search("  long exact 搜索 SP  ").entries.single()
        assertEquals("  long exact 搜索 SP  ", site.keyword)
        val playlists = browser.browse(subject.reference).entries
        assertNotEquals(playlists[0].reference.resourceId, playlists[1].reference.resourceId)
        val selected = browser.browse(playlists[1].reference).entries.single()
        assertEquals(media, browser.createMedia(selected.reference, request))
        assertEquals("https://site/b", site.selected?.url)
        assertFailsWith<IllegalArgumentException> { browser.browse(subject.reference.copy(sourceId = "other")) }
    }

    @Test
    fun `playlist identity survives appended episodes and video locator survives page changes`() = runTest {
        val site = Site()
        val browser = WebsiteMediaSourceBrowser(site)
        val subject = browser.search("x").entries.single().reference
        val before = browser.browse(subject).entries.first().reference
        val video = browser.browse(before).entries.single().reference
        site.channels = site.channels.map { it.copy(episodes = it.episodes + BrowseEpisode("2", "https://site/new")) }
        assertEquals(before.resourceId, browser.browse(subject).entries.first().reference.resourceId)
        assertEquals(2, browser.browse(before).entries.size)
        site.channels = emptyList()
        browser.createMedia(video, request)
        assertEquals("https://site/a", site.selected?.url)
    }

    @Test
    fun `torrent reference preserves original identity and rejects foreign source`() {
        val ref = TorrentMediaSourceReferences.entry(media).reference
        assertEquals(media, TorrentMediaSourceReferences.decode(ref))
        assertEquals(media.mediaId, ref.resourceId)
        assertFailsWith<IllegalArgumentException> { TorrentMediaSourceReferences.decode(ref, "other") }
        assertFailsWith<IllegalArgumentException> { TorrentMediaSourceReferences.decode(ref.copy(resourceId = "other")) }
    }
}
