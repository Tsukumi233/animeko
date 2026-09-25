/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.mediasource.pikpak

import kotlinx.serialization.json.Json
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.torrent.pikpak.PikPakDriveEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PikPakMediaSourceTest {
    private val request = MediaFetchRequest(
        subjectId = "subject", episodeId = "episode", subjectNames = listOf("番剧"),
        episodeSort = EpisodeSort(3), episodeName = "第三集",
    )

    private fun entry(name: String = "[Group] 番剧 - 03 [1080p][CHS].mkv", directory: Boolean = false) = PikPakDriveEntry(
        "file-id", "parent", name, directory, 5_000_000_000L, null, null, "hash:content",
    )

    @Test
    fun `disable acceleration keeps account usable for drive browsing`() {
        assertNotNull(PikPakConfig.Default.copy(enabled = false, username = "user", password = "password").accountCredentials())
        assertNotNull(PikPakConfig.Default.copy(enabled = false, username = "user", refreshToken = "token").accountCredentials())
        assertNull(PikPakConfig.Default.copy(username = "user", password = "", refreshToken = "").accountCredentials())
    }

    @Test
    fun `references retain identity through rename and remain isolated by account`() {
        val first = entry().toSourceEntry("source", "account-a", null)
        val renamed = entry("renamed.mkv").toSourceEntry("source", "account-a", null)
        val otherAccount = entry().toSourceEntry("source", "account-b", null)
        assertEquals(first.reference.resourceId, renamed.reference.resourceId)
        assertNotEquals(first.reference.resourceId, otherAccount.reference.resourceId)
        assertEquals(MediaSourceEntryKind.VIDEO, first.kind)
        assertEquals(MediaSourceEntryKind.FILE, entry("subtitle.ass").toSourceEntry("source", "account-a", null).kind)
        assertEquals(MediaSourceEntryKind.DIRECTORY, entry(directory = true).toSourceEntry("source", "account-a", null).kind)
    }

    @Test
    fun `confirmed media uses persisted reference and requested episode without network`() {
        val ref = entry().toSourceEntry("source", "account-a", null).reference
        val media = createPikPakMedia("source", ref, request)
        assertEquals(MediaSourceKind.CloudDrive, media.kind)
        assertEquals(listOf(EpisodeSort(3)), media.episodeRange?.knownSorts?.toList())
        assertEquals(ref, (media.download as ResourceLocation.SourceResource).reference)
        assertEquals("", media.properties.alliance)
        assertEquals(media.mediaId, createPikPakMedia("source", ref, request.copy(episodeId = "next", episodeSort = EpisodeSort(4))).mediaId)
        val serialized = Json.encodeToString(ref)
        assertTrue(!serialized.contains("https://") && !serialized.contains("token"))
    }

    @Test
    fun `directory subtitle foreign source and future locator versions cannot become media`() {
        val ref = entry().toSourceEntry("source", "account", null).reference
        assertFailsWith<IllegalArgumentException> { createPikPakMedia("other", ref, request) }
        assertFailsWith<IllegalArgumentException> { createPikPakMedia("source", ref.copy(version = 2), request) }
        assertFailsWith<IllegalArgumentException> { createPikPakMedia("source", ref.copy(resourceId = "wrong-id"), request) }
        assertFailsWith<IllegalArgumentException> { createPikPakMedia("source", ref, request.copy(episodeId = "")) }
        assertFailsWith<IllegalArgumentException> {
            createPikPakMedia("source", entry(directory = true).toSourceEntry("source", "account", null).reference, request)
        }
        assertFailsWith<IllegalArgumentException> {
            createPikPakMedia("source", entry("subtitle.srt").toSourceEntry("source", "account", null).reference, request)
        }
    }
}
