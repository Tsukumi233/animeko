package me.him188.ani.app.ui.resource

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.him188.ani.app.domain.mediasource.library.ResourceFileIdentity
import me.him188.ani.app.domain.mediasource.library.ResourcePreviewInput
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.navigation.LocalNavigator
import me.him188.ani.app.navigation.MainScreenPage
import me.him188.ani.app.navigation.NavRoutes
import me.him188.ani.app.ui.subject.details.sections.SectionHeaderResourceButton
import me.him188.ani.app.ui.framework.AniComposeUiTest
import me.him188.ani.app.ui.framework.assertScreenshot
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.source.MediaSourceEntry
import me.him188.ani.datasources.api.source.MediaSourceEntryKind
import me.him188.ani.datasources.api.source.MediaSourceSearchScope
import me.him188.ani.utils.platform.annotations.TestOnly
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(TestOnly::class, ExperimentalTestApi::class)
class ResourceBrowserScreenTest {
    private val originalLocale = Locale.getDefault()
    @BeforeTest fun setLocale() = Locale.setDefault(Locale.ENGLISH)
    @AfterTest fun restoreLocale() = Locale.setDefault(originalLocale)

    @Test fun `compact subject resource button opens browsing with the correct subject`() = runAniComposeUiTest {
        val navigator = AniNavigator().apply { setBackStack(mutableStateListOf(NavRoutes.Main(MainScreenPage.Exploration))) }
        setContent {
            ProvideCompositionLocalsForPreview {
                CompositionLocalProvider(LocalNavigator provides navigator) {
                    Surface { SectionHeaderResourceButton(123, showLabel = false) }
                }
            }
        }
        onNodeWithContentDescription("Associate resources").performClick()
        runOnIdle { assertEquals(NavRoutes.ResourceLibrary(123), navigator.backStack.last()) }
    }

    private fun input(name: String, kind: MediaSourceEntryKind) = ResourcePreviewInput(
        MediaSourceEntry(MediaResourceRef("disk", name), name, kind),
    )
    private val video = input("01.mkv", MediaSourceEntryKind.VIDEO)
    private val state = ResourceBrowserState(
        sourceId = "disk", sourceName = "My videos",
        rows = listOf(input("Season 2", MediaSourceEntryKind.DIRECTORY), video, input("02.mkv", MediaSourceEntryKind.VIDEO)),
        searchScope = MediaSourceSearchScope.CURRENT_CONTAINER,
    )

    @Test fun `narrow browser selects a file and submits complete search text`() = runAniComposeUiTest {
        var query = ""
        var selection = emptySet<ResourceFileIdentity>()
        setContent {
            ProvideCompositionLocalsForPreview {
                var checked by remember { mutableStateOf(emptySet<ResourceFileIdentity>()) }
                Surface(Modifier.width(390.dp).height(640.dp)) {
                    ResourceBrowserContent(state, checked, {}, { query = it }, {}, {}, {}, {
                        checked = checked + it.identity
                        selection = checked
                    }, {}, true)
                }
            }
        }
        onNodeWithText("01.mkv").performClick()
        runOnIdle { assertEquals(setOf(video.identity), selection) }
        onNodeWithTag("resource-search").performTextInput("full keyword SP")
        onNodeWithText("Search", substring = false).performClick()
        runOnIdle { assertEquals("full keyword SP", query) }
        screenshot("resource-browser-narrow")
    }

    @Test fun `wide browser preserves directory and file actions`() = runAniComposeUiTest {
        var entered: String? = null
        render(960.dp, onEnter = { entered = it.entry.name })
        onNodeWithText("Season 2").performClick()
        runOnIdle { assertEquals("Season 2", entered) }
        onNodeWithText("02.mkv").assertIsDisplayed()
        screenshot("resource-browser-wide")
    }

    @Test fun `search-only source explains how to start instead of showing empty results`() = runAniComposeUiTest {
        render(390.dp, state.copy(rows = emptyList(), needsSearch = true, searchScope = MediaSourceSearchScope.SOURCE))
        onNodeWithText("Enter a keyword to search this source.").assertIsDisplayed()
    }

    private fun AniComposeUiTest.render(width: Dp, content: ResourceBrowserState = state, onEnter: (ResourcePreviewInput) -> Unit = {}) {
        setContent {
            ProvideCompositionLocalsForPreview {
                Surface(Modifier.width(width).height(640.dp)) {
                    ResourceBrowserContent(content, emptySet(), {}, {}, {}, {}, onEnter, {}, {}, true)
                }
            }
        }
    }

    private fun AniComposeUiTest.screenshot(name: String) {
        mainClock.advanceTimeBy(500)
        waitForIdle()
        val node = onNodeWithTag("resource-browser")
        if (System.getenv("ANI_UPDATE_RESOURCE_SCREENSHOTS") == "true") {
            val target = File("build/screenshots/$name.png")
            target.parentFile.mkdirs()
            Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).use { image ->
                target.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        } else node.assertScreenshot("/screenshots/$name.png")
    }
}
