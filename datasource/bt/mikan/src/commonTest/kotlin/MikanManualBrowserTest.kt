/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.datasources.mikan

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.TorrentMediaSourceReferences
import me.him188.ani.test.readTestResourceAsString
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MikanManualBrowserTest {
    @Test
    fun `exact manual keyword bypasses Bangumi index and ten character truncation`() = runTest {
        val keyword = "  关于我转生变成史莱姆这档事 特别篇 SP  "
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            assertEquals("/RSS/Search", it.url.encodedPath)
            assertEquals(keyword, it.url.parameters["searchstr"])
            respond(readTestResourceAsString("/mikan-subject-rss-无职转生.txt"))
        })
        try {
            val index = object : MikanIndexCacheProvider {
                override suspend fun getMikanSubjectId(bangumiSubjectId: String): String? = error("No Bangumi mapping")
                override suspend fun setMikanSubjectId(bangumiSubjectId: String, mikanSubjectId: String) = error("No mapping write")
            }
            val source = object : AbstractMikanMediaSource("mikan-test", "https://site.test", index, http.asScopedHttpClient()) {
                override val info = MediaSourceInfo("test")
            }
            val result = source.search(keyword)
            assertEquals(318, result.entries.size)
            assertNull(result.nextPageToken)
            assertEquals(1, requests)
            assertTrue(result.entries.all { TorrentMediaSourceReferences.decode(it.reference).mediaId == it.reference.resourceId })
        } finally {
            http.close()
        }
    }
}
