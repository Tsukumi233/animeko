/*
 * Copyright (C) 2026 OpenAni and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package me.him188.ani.app.domain.mediasource.pikpak

import io.github.nihildigit.pikpak.InMemorySessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.him188.ani.torrent.pikpak.PikPakAccountChangedException
import me.him188.ani.torrent.pikpak.PikPakAccountProvider
import me.him188.ani.torrent.pikpak.PikPakCredentials
import me.him188.ani.torrent.pikpak.PikPakDriveAccess
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

    @Test fun `account root lists the same files as null and rejects a switched account`() = runTest {
        val credentials = MutableStateFlow<PikPakCredentials?>(PikPakCredentials("first@example.com", "password"))
        var loginCount = 0
        val http = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/v1/shield/captcha/init") -> """{"captcha_token":"CAP"}"""
                request.url.encodedPath.endsWith("/v1/auth/signin") -> {
                    loginCount++
                    """{"access_token":"AT","refresh_token":"RT","sub":"account-$loginCount","expires_in":3600}"""
                }
                else -> {
                    assertEquals("/drive/v1/files", request.url.encodedPath)
                    assertTrue(request.url.parameters["parent_id"].isNullOrEmpty())
                    """{"files":[{"id":"file-id","kind":"drive#file","name":"Show 01.mkv"}]}"""
                }
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val accounts = PikPakAccountProvider(http, credentials, InMemorySessionStore())
            val source = PikPakMediaSource("source", accounts, PikPakDriveAccess(accounts))
            val root = source.rootEntry()
            assertEquals(MediaSourceEntryKind.DIRECTORY, root.kind)
            assertEquals(source.browse().entries.map { it.reference }, source.browse(root.reference).entries.map { it.reference })
            assertEquals(root.reference, source.browse(root.reference).entries.single().parent)
            assertFailsWith<IllegalArgumentException> { source.createMedia(root.reference, request) }
            credentials.value = PikPakCredentials("other@example.com", "password")
            assertFailsWith<PikPakAccountChangedException> { source.browse(root.reference) }
            assertNotEquals(root.reference, source.rootEntry().reference)
        } finally { http.close() }
    }

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
