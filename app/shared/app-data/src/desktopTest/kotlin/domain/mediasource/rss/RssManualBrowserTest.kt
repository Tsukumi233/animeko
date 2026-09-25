/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.mediasource.rss

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.TorrentMediaSourceReferences
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RssManualBrowserTest {
    @Test
    fun `manual search preserves full keyword and unrecognized SP release across pages`() = runTest {
        val keyword = "  full keyword 特别篇 SP  "
        val http = HttpClient(MockEngine {
            assertEquals(keyword, it.url.parameters["q"])
            val items = if (it.url.parameters["page"] == "0") """
                <item><title>[Group] Unrelated SP</title><guid>special</guid>
                <link>https://site.test/release</link>
                <enclosure url="https://site.test/file.torrent" length="123" type="application/x-bittorrent"/></item>
            """ else ""
            respond("<rss version=\"2.0\"><channel><title>test</title>$items</channel></rss>")
        })
        try {
            val args = RssMediaSourceArguments.Default.copy(searchConfig = RssSearchConfig(
                searchUrl = "https://site.test/search?q={keyword}&page={page}",
                filterByEpisodeSort = true, filterBySubjectName = true,
            ))
            val source = RssMediaSource("rss-test", MediaSourceConfig(serializedArguments = Json.encodeToJsonElement(
                RssMediaSourceArguments.serializer(), args)), client = http.asScopedHttpClient())
            val first = source.search(keyword)
            val entry = first.entries.single()
            assertEquals("[Group] Unrelated SP", entry.name)
            assertEquals("1", first.nextPageToken)
            val request = MediaFetchRequest("other", "9", subjectNames = listOf("other"), episodeSort = EpisodeSort(9), episodeName = "")
            assertEquals(TorrentMediaSourceReferences.decode(entry.reference), source.createMedia(entry.reference, request))
            assertNull(source.search(keyword, pageToken = first.nextPageToken).nextPageToken)
        } finally {
            http.close()
        }
    }
}
