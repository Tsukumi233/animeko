package me.him188.ani.app.ui.resource

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.him188.ani.app.domain.mediasource.library.ResourcePreviewInput
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.resource_ignore
import me.him188.ani.app.ui.lang.resource_source_unavailable
import org.jetbrains.compose.resources.stringResource

/** Keeps source and exact file identity visible while the user edits a mapping or skip decision. */
@Composable
internal fun ResourceAssociationFileRow(
    input: ResourcePreviewInput,
    sourceName: String?,
    targetLabel: String,
    ignored: Boolean,
    enabled: Boolean,
    onChooseEpisode: () -> Unit,
    onIgnore: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    episodeMenu: @Composable () -> Unit = {},
) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text(input.fileName)
        Text(sourceName?.takeIf { it.isNotBlank() } ?: stringResource(Lang.resource_source_unavailable),
            style = MaterialTheme.typography.bodySmall)
        (input.selectedFilePath ?: input.folderName)?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Box {
            TextButton(onChooseEpisode, enabled = enabled && !ignored) { Text(targetLabel) }
            episodeMenu()
        }
        Row {
            Checkbox(ignored, onIgnore, enabled = enabled)
            Text(stringResource(Lang.resource_ignore))
        }
    }
}
