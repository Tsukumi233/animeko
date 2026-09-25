package me.him188.ani.app.ui.resource

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import me.him188.ani.app.domain.mediasource.library.ResourcePreviewInput
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(TestOnly::class, ExperimentalTestApi::class)
class ResourceAssociationFileRowTest {
    @Test fun `duplicate torrent filenames retain exact paths when selecting and skipping`() = runAniComposeUiTest {
        val entry = MediaSourceEntry(MediaResourceRef("bt", "release"), "Season", MediaSourceEntryKind.TORRENT)
        var chosen: String? = null
        var skipped: String? = null
        setContent { ProvideCompositionLocalsForPreview { Surface(Modifier.width(390.dp)) { Column {
            listOf("TV/01.mkv", "SP/01.mkv").forEach { path ->
                ResourceAssociationFileRow(ResourcePreviewInput(entry, path), "My BT source", "Choose episode", false, true,
                    { chosen = path }, { skipped = path }, Modifier.testTag(path))
            }
        } } } }
        onAllNodesWithText("01.mkv").assertCountEquals(2)
        onNodeWithText("TV/01.mkv").assertIsDisplayed()
        onNodeWithText("SP/01.mkv").assertIsDisplayed()
        onNode(hasText("Choose episode") and hasAnyAncestor(hasTestTag("SP/01.mkv"))).performClick()
        onNode(isToggleable() and hasAnyAncestor(hasTestTag("TV/01.mkv"))).performClick()
        runOnIdle { assertEquals("SP/01.mkv", chosen); assertEquals("TV/01.mkv", skipped) }
    }

    @Test fun `same filename from different sources retains readable folder context`() = runAniComposeUiTest {
        setContent { ProvideCompositionLocalsForPreview { Surface(Modifier.width(390.dp)) { Column {
            listOf("Local collection", "NAS collection").forEach { source ->
                val input = ResourcePreviewInput(MediaSourceEntry(MediaResourceRef(source, "opaque", "private-locator"), "01.mkv", MediaSourceEntryKind.VIDEO), folderName = "Season 2")
                ResourceAssociationFileRow(input, source, "Choose episode", false, true, {}, {})
            }
        } } } }
        onNodeWithText("Local collection").assertIsDisplayed()
        onNodeWithText("NAS collection").assertIsDisplayed()
        onAllNodesWithText("Season 2").assertCountEquals(2)
        onAllNodesWithText("private-locator").assertCountEquals(0)
    }
}
