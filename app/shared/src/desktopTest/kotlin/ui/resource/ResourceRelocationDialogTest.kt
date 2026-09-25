package me.him188.ani.app.ui.resource

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.him188.ani.app.data.persistent.database.dao.LibraryRelocationSnapshot
import me.him188.ani.app.domain.mediasource.local.LocalRelocationException
import me.him188.ani.app.domain.mediasource.local.LocalRelocationItem
import me.him188.ani.app.domain.mediasource.local.LocalResourceRelocationPlan
import me.him188.ani.app.domain.mediasource.local.LocalResourceRelocationState
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.utils.platform.annotations.TestOnly
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(TestOnly::class, ExperimentalTestApi::class)
class ResourceRelocationDialogTest {
    private val locale = Locale.getDefault()
    @BeforeTest fun setup() { Locale.setDefault(Locale.ENGLISH) }
    @AfterTest fun teardown() { Locale.setDefault(locale) }

    // The fixture represents an already prepared plan; UI tests do not commit storage transactions.
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    private fun plan(directory: Boolean = false) = LocalResourceRelocationPlan(
        if (directory) "C:/Shows/Original season" else "C:/Shows/Original season/original.mkv",
        if (directory) "D:/Archive/Replacement season" else "D:/Archive/Replacement season/replacement.mkv", directory,
        if (directory) listOf("TV/01.mkv", "SP/01.mkv").mapIndexed { index, path ->
            LocalRelocationItem("resource-$index", "01.mkv", "01.mkv", "C:/Shows/$path", "D:/Archive/$path", path, 100, 100)
        } else listOf(LocalRelocationItem("resource", "original.mkv", "replacement.mkv", "C:/Shows/original.mkv", "D:/Archive/replacement.mkv", null, 100, 200)),
        LibraryRelocationSnapshot("disk", emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        emptyList(), emptyList(), emptyList(), emptyList(),
    )

    @Test fun `file preview shows both complete locations and size difference before confirmation`() = runAniComposeUiTest {
        var confirmations = 0
        setContent { ProvideCompositionLocalsForPreview {
            ResourceRelocationDialog(LocalResourceRelocationState(visible = true, plan = plan()), { confirmations++ }, {})
        } }
        onNodeWithText("C:/Shows/Original season/original.mkv").assertIsDisplayed()
        onNodeWithText("D:/Archive/Replacement season/replacement.mkv").assertIsDisplayed()
        onNodeWithText("original.mkv").assertIsDisplayed()
        onNodeWithText("replacement.mkv").assertIsDisplayed()
        onNodeWithText("This file has a different size. Check that it is the video you intend to use.").assertIsDisplayed()
        runOnIdle { assertEquals(0, confirmations) }
        onNodeWithTag("resource-relocation-confirm").assertIsEnabled().performClick()
        runOnIdle { assertEquals(1, confirmations) }
    }

    @Test fun `directory preview distinguishes identical basenames by complete relative paths`() = runAniComposeUiTest {
        setContent { ProvideCompositionLocalsForPreview {
            ResourceRelocationDialog(LocalResourceRelocationState(visible = true, plan = plan(directory = true)), {}, {})
        } }
        onNodeWithText("TV/01.mkv").assertIsDisplayed()
        onNodeWithText("SP/01.mkv").assertIsDisplayed()
        onNodeWithText("This file has a different size. Check that it is the video you intend to use.").assertDoesNotExist()
        onNodeWithTag("resource-relocation-confirm").assertIsEnabled()
    }

    @Test fun `loading disables confirmation and cancel dismisses without committing`() = runAniComposeUiTest {
        var state by mutableStateOf(LocalResourceRelocationState(visible = true, loading = true, plan = plan()))
        var confirmations = 0
        var dismissals = 0
        setContent { ProvideCompositionLocalsForPreview {
            ResourceRelocationDialog(state, { confirmations++ }, { dismissals++; state = LocalResourceRelocationState() })
        } }
        onNodeWithTag("resource-relocation-confirm").assertIsNotEnabled()
        onNodeWithText("Cancel").assertIsEnabled().performClick()
        onNodeWithTag("resource-relocation-preview").assertDoesNotExist()
        runOnIdle { assertEquals(0, confirmations); assertEquals(1, dismissals) }
    }

    @Test fun `saving disables both confirmation and cancel`() = runAniComposeUiTest {
        setContent { ProvideCompositionLocalsForPreview {
            ResourceRelocationDialog(LocalResourceRelocationState(visible = true, saving = true, plan = plan()),
                { error("Cannot confirm while saving") }, { error("Cannot cancel while saving") })
        } }
        onNodeWithTag("resource-relocation-confirm").assertIsNotEnabled()
        onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test fun `changed preview shows actionable error and permits cancel without confirming`() = runAniComposeUiTest {
        var state by mutableStateOf(LocalResourceRelocationState(visible = true, plan = plan(),
            error = LocalRelocationException(LocalRelocationException.Reason.CHANGED_SINCE_PREVIEW, "replacement.mkv")))
        var dismissals = 0
        setContent { ProvideCompositionLocalsForPreview {
            ResourceRelocationDialog(state, { error("Cannot confirm stale preview") }, { dismissals++; state = LocalResourceRelocationState() })
        } }
        onNodeWithText("Resources or files changed after the preview. Choose the destination again.").assertIsDisplayed()
        onNodeWithTag("resource-relocation-confirm").assertIsNotEnabled()
        onNodeWithText("Cancel").assertIsEnabled().performClick()
        onNodeWithTag("resource-relocation-confirm").assertDoesNotExist()
        runOnIdle { assertEquals(1, dismissals) }
    }
}
