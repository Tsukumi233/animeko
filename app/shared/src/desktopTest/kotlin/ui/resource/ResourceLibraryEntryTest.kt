package me.him188.ani.app.ui.resource

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.utils.platform.annotations.TestOnly
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(TestOnly::class, ExperimentalTestApi::class)
class ResourceLibraryEntryTest {
    private val locale = Locale.getDefault()
    @BeforeTest fun setup() { Locale.setDefault(Locale.ENGLISH) }
    @AfterTest fun teardown() { Locale.setDefault(locale) }

    @Test fun `same episode and filename distinguish sources and select the corresponding resource`() = runAniComposeUiTest {
        var chosen = ""
        setContent { ProvideCompositionLocalsForPreview {
            Surface(Modifier.width(390.dp)) {
                Column {
                    ResourceLibraryEntry("01.mkv", "My folder", MediaSourceKind.LocalFile,
                        { chosen = "local" }, episodeLabel = "01 First")
                    ResourceLibraryEntry("01.mkv", "My NAS", MediaSourceKind.FileService,
                        { chosen = "webdav" }, episodeLabel = "01 First")
                }
            }
        } }
        onAllNodesWithText("01.mkv").assertCountEquals(2)
        onNodeWithText("Local files · My folder · 01 First").assertIsDisplayed()
        onNodeWithText("Network files · My NAS · 01 First").performClick()
        runOnIdle { assertEquals("webdav", chosen) }
    }

    @Test fun `removed sources and missing originals remain understandable without exposing identifiers`() = runAniComposeUiTest {
        setContent { ProvideCompositionLocalsForPreview { Surface {
            ResourceLibraryEntry("01.mkv", null, null, {}, filePath = "Season 2/01.mkv", missing = true)
        } } }
        onNodeWithText("Source unavailable").assertIsDisplayed()
        onNodeWithText("Season 2/01.mkv").assertIsDisplayed()
    }
}
