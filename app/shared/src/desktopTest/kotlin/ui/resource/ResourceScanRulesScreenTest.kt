package me.him188.ani.app.ui.resource

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.createTestSubjectCollection
import me.him188.ani.app.data.persistent.database.dao.LibraryScanRootEntity
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MediaResourceRef
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.platform.annotations.TestOnly
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(TestOnly::class, ExperimentalTestApi::class)
class ResourceScanRulesScreenTest {
    private val locale = Locale.getDefault()
    @BeforeTest fun setup() { Locale.setDefault(Locale.ENGLISH) }
    @AfterTest fun teardown() { Locale.setDefault(locale) }

    @Test fun `large catalog leaves scope confirmation before episode preview`() = runAniComposeUiTest {
        val subject = createTestSubjectCollection(1, (1..220).map { id -> EpisodeCollectionInfo(
            EpisodeInfo.Empty.copy(episodeId = id, sort = EpisodeSort(id)), UnifiedCollectionType.NOT_COLLECTED,
        ) }, UnifiedCollectionType.NOT_COLLECTED)
        val state = ResourceScanRuleState(subject = subject)
        setContent { ProvideCompositionLocalsForPreview {
            Surface(Modifier.width(360.dp).height(560.dp).verticalScroll(rememberScrollState())) {
                ResourceScanRuleOptions(state, {}, {})
            }
        } }
        onNodeWithTag("scan-confirm-scope").assertIsDisplayed()
    }

    @Test fun `title scope and acknowledgement are separate controls on narrow screens`() = runAniComposeUiTest {
        val subject = createTestSubjectCollection(1, listOf(EpisodeCollectionInfo(
            EpisodeInfo.Empty.copy(episodeId = 11, sort = EpisodeSort(13), ep = EpisodeSort(1)), UnifiedCollectionType.NOT_COLLECTED,
        )), UnifiedCollectionType.NOT_COLLECTED)
        var latest = ResourceScanRuleState(root = LibraryScanRootEntity("root", "disk", Json.encodeToString(MediaResourceRef("disk", "folder")), "Show"), subject = subject)
        setContent {
            ProvideCompositionLocalsForPreview {
                var state by remember { mutableStateOf(latest) }
                Surface(Modifier.width(360.dp)) {
                    ResourceScanRuleOptions(state, { edit -> state = edit(state).copy(scopeConfirmed = false); latest = state },
                        { value -> state = state.copy(scopeConfirmed = value); latest = state })
                }
            }
        }
        onNodeWithTag("scan-all-titles").performClick()
        runOnIdle { assertFalse(latest.canSave) }
        onNodeWithTag("scan-confirm-scope").performClick()
        runOnIdle { assertTrue(latest.canSave) }
        onNodeWithText("Filename number → episode").assertIsDisplayed()
    }

    @Test fun `cancel and retry target the displayed scan root`() = runAniComposeUiTest {
        var cancelled = false
        val root = LibraryScanRootEntity("root", "disk", "{}", "Show", activeScanToken = "token")
        setContent { ProvideCompositionLocalsForPreview { Surface { ResourceScanRootStatus(root, {}, { cancelled = it == root.id }) } } }
        onNodeWithText("Cancel scan").performClick()
        runOnIdle { assertTrue(cancelled) }
    }
}
