package me.him188.ani.app.ui.resource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.lang.*
import me.him188.ani.datasources.api.source.MediaSourceKind
import org.jetbrains.compose.resources.stringResource

/** A file version keeps its readable source alongside episode information, including identical file names. */
@Composable
internal fun ResourceLibraryEntry(
    name: String,
    sourceName: String?,
    sourceKind: MediaSourceKind?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    episodeLabel: String? = null,
    statusLabel: String? = null,
    filePath: String? = null,
    missing: Boolean = false,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val kind = when (sourceKind) {
        MediaSourceKind.WEB -> stringResource(Lang.settings_media_source_web)
        MediaSourceKind.BitTorrent -> stringResource(Lang.settings_media_source_bt)
        MediaSourceKind.LocalFile -> stringResource(Lang.settings_media_source_local_file)
        MediaSourceKind.FileService -> stringResource(Lang.settings_media_source_file_service)
        MediaSourceKind.CloudDrive -> stringResource(Lang.settings_media_source_cloud_drive)
        MediaSourceKind.LocalCache, null -> null
    }
    val sourceLabel = sourceName?.takeIf { it.isNotBlank() } ?: stringResource(Lang.resource_source_unavailable)
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            Column {
                Text(listOfNotNull(kind, sourceLabel, episodeLabel).distinct().joinToString(" · "))
                statusLabel?.let { Text(it) }
                filePath?.let { Text(it) }
                if (missing) Text(stringResource(Lang.resource_missing))
            }
        },
        trailingContent = trailingContent,
        modifier = modifier.clickable(onClick = onClick),
    )
}
