/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.datasources.dmhy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DmhyManualBrowserTest {
    @Test
    fun `manual search preserves keyword and unknown episode without category filter`() = runTest {
        val keyword = "  exact keyword 特别篇 SP  "
        val client = HttpClient(MockEngine {
            assertEquals(keyword, it.url.parameters["keyword"])
            assertNull(it.url.parameters["sort_id"])
            val row = if (it.url.encodedPath.endsWith("/2")) "" else """
                <tr><td><span>2026/09/26 12:00</span></td><td><a href="/topics/list/sort_id/2">Anime</a></td>
                <td><a href="/topics/view/123">Unknown Special SP</a></td>
                <td><a href="magnet:?xt=urn:btih:0123456789012345678901234567890123456789">torrent</a></td>
                <td>123 MB</td><td><a href="/topics/list/user_id/4">user</a></td></tr>
            """
            respond("<html><table class=\"tablesorter\"><tbody>$row</tbody></table></html>")
        })
        try {
            val source = DmhyMediaSource(client.asScopedHttpClient())
            val result = source.search(keyword)
            assertEquals("Unknown Special SP", result.entries.single().name)
            assertEquals("2", result.nextPageToken)
            assertNull(source.search(keyword, pageToken = result.nextPageToken).nextPageToken)
        } finally {
            client.close()
        }
    }
}
