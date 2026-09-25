package me.him188.ani.app.ui.resource

import androidx.compose.material3.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import me.him188.ani.app.domain.mediasource.fileservice.FileServiceArguments
import me.him188.ani.app.domain.mediasource.fileservice.FileServiceMediaSource
import me.him188.ani.app.domain.mediasource.fileservice.FileServiceProtocol
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.datasources.api.source.MediaSourceConfig
import me.him188.ani.datasources.api.source.MediaSourceTier
import me.him188.ani.utils.platform.annotations.TestOnly
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(TestOnly::class, ExperimentalTestApi::class)
class ResourceConnectionDialogTest {
    @Test fun `SMB custom port validates before saving a relative subdirectory`() = runAniComposeUiTest {
        var saved: FileServiceArguments? = null
        setContent {
            ProvideCompositionLocalsForPreview {
                Surface { ResourceConnectionDialog(FileServiceProtocol.SMB, {}, { args, _, _, _ -> saved = args }) }
            }
        }
        onNodeWithTag("connection-endpoint").performTextInput("localhost")
        onNodeWithTag("connection-share").performTextInput("videos")
        onNodeWithTag("connection-port").performTextReplacement("65536")
        onNodeWithTag("connection-root").performTextInput("Anime/Season 2")
        onNodeWithTag("connection-save").performClick()
        runOnIdle { assertNull(saved) }
        onNodeWithTag("connection-port").performTextReplacement("1445")
        onNodeWithTag("connection-save").performClick()
        runOnIdle {
            assertEquals(1445, saved?.port)
            assertEquals("Anime/Season 2", saved?.root)
            assertEquals("videos", saved?.share)
        }
    }

    @Test fun `editing WebDAV preserves tier and submits no stored password`() = runAniComposeUiTest {
        val args = FileServiceArguments("Media", FileServiceProtocol.WEBDAV, "https://example.com/videos/", tier = MediaSourceTier(1u))
        val editor = ResourceConnectionEditor(MediaSourceSave("id", "source", FileServiceMediaSource.FactoryId, true, MediaSourceConfig()), args, "user", "")
        var saved: FileServiceArguments? = null
        var password: String? = null
        setContent {
            ProvideCompositionLocalsForPreview {
                Surface { ResourceConnectionDialog(FileServiceProtocol.WEBDAV, {}, { value, _, pass, _ -> saved = value; password = pass }, editor) }
            }
        }
        onNodeWithTag("connection-endpoint").performTextReplacement("https://example.com/other/")
        onNodeWithTag("connection-save").performClick()
        runOnIdle {
            assertEquals(args.copy(endpoint = "https://example.com/other/"), saved)
            assertEquals("", password)
        }
    }
}
