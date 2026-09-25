package me.him188.ani.app.domain.mediasource.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.selector.testFramework.SimpleMediaSelectorTestSuite
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.platform.annotations.SerializationOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(SerializationOnly::class)
internal fun libraryPlaybackTestMedia(
    mediaId: String = "video",
    mediaSourceId: String = "chosen-source",
    download: ResourceLocation = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:release"),
) = DefaultMedia(mediaId, mediaSourceId, "https://example.com/release", download, "video", 0,
    MediaProperties(subtitleLanguageIds = emptyList(), resolution = "", alliance = ""))

class LibraryResourcePlaybackRequestTest {
    private val media = libraryPlaybackTestMedia()

    @Test
    fun `metadata refresh carries latest manual selection instead of reclaiming original file`() = runTest {
        val first = SimpleMediaSelectorTestSuite(this).selector
        val refreshed = SimpleMediaSelectorTestSuite(this).selector
        val request = LibraryResourcePlaybackRequest(11, { media }, { true })
        request.selectForEpisode(11, first)
        val other = libraryPlaybackTestMedia(mediaId = "user-picked-other")
        first.select(other)
        request.selectForEpisode(11, refreshed)
        assertEquals<Media>(other, refreshed.selected.value!!)
    }

    @Test
    fun `explicit request selects once and leaves later manual selection alone`() = runTest {
        val request = LibraryResourcePlaybackRequest(11, { media }, { true })
        val selections = mutableListOf<Media>()
        repeat(2) { assertTrue(request.selectForEpisode(11) { selections.add(it) }) }
        assertEquals(listOf<Media>(media), selections)
        assertFalse(request.selectForEpisode(12) { error("Another episode must use ordinary selection") })
        assertNull(request.error.value)
    }

    @Test
    fun `missing or unavailable resource prevents default selection without touching current selection`() = runTest {
        for (exists in listOf(false, true)) {
            val request = LibraryResourcePlaybackRequest(11, { if (exists) media else null }, { false })
            assertTrue(request.selectForEpisode(11) { error("Must preserve current selection") })
            assertEquals(if (exists) LibraryResourcePlaybackError.SOURCE_UNAVAILABLE else LibraryResourcePlaybackError.MISSING_BINDING,
                request.error.value)
        }
    }

    @Test
    fun `corrupt reference is visible and cannot trigger auto selection`() = runTest {
        val request = LibraryResourcePlaybackRequest(11, { error("invalid payload") }, { true })
        assertTrue(request.selectForEpisode(11) { error("Must not select") })
        assertEquals(LibraryResourcePlaybackError.INVALID_REFERENCE, request.error.value)
    }

    @Test
    fun `cancelled preparation can retry without marking request complete`() = runTest {
        var cancel = true
        val request = LibraryResourcePlaybackRequest(11, { if (cancel) throw CancellationException(); media }, { true })
        assertFailsWith<CancellationException> { request.selectForEpisode(11) { true } }
        assertNull(request.error.value)
        cancel = false
        var selected = false
        assertTrue(request.selectForEpisode(11) { selected = true; true })
        assertTrue(selected)
    }
}
