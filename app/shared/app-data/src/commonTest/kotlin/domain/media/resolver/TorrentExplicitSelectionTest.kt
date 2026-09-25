package me.him188.ani.app.domain.media.resolver

import me.him188.ani.datasources.api.EpisodeSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TorrentExplicitSelectionTest {
    private fun select(files: List<String>, path: String) = TorrentMediaResolver.selectVideoFileEntry(
        files, { this }, listOf("episode 1"), EpisodeSort(1), EpisodeSort(1), selectedFilePath = path,
    )

    @Test
    fun `explicit full path distinguishes identical filenames and takes precedence over episode parsing`() {
        val files = listOf("Season 1/01.mkv", "Season 2/01.mkv", "episode 1.mkv")
        assertEquals("Season 2/01.mkv", select(files, "Season 2\\01.mkv"))
    }

    @Test
    fun `missing explicit path never guesses another video`() {
        assertNull(select(listOf("01.mkv", "02.mkv"), "removed/01.mkv"))
    }

    @Test
    fun `ambiguous exact paths fail rather than picking first`() {
        assertNull(select(listOf("season/01.mkv", "season\\01.mkv"), "season/01.mkv"))
    }
}
