package me.him188.ani.app.ui.resource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.him188.ani.app.domain.mediasource.local.LocalRelocationException
import me.him188.ani.app.domain.mediasource.local.LocalResourceRelocationState
import me.him188.ani.app.ui.lang.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun ResourceRelocationDialog(state: LocalResourceRelocationState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Lang.resource_relocate)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("resource-relocation-preview")) {
                if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.plan?.let { plan ->
                    Text(stringResource(if (plan.directory) Lang.resource_relocate_directory_hint else Lang.resource_relocate_file_hint))
                    Text(stringResource(Lang.resource_relocate_old_location), style = MaterialTheme.typography.labelMedium)
                    Text(resourceLocationLabel(plan.oldLocation), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(Lang.resource_relocate_new_location), style = MaterialTheme.typography.labelMedium)
                    Text(resourceLocationLabel(plan.newLocation), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(plan.items, key = { it.resourceId }) { item ->
                            Column {
                                Text(item.relativePath ?: item.oldName)
                                Text(item.newName, style = MaterialTheme.typography.bodySmall)
                                if (!plan.directory && item.oldSize != null && item.newSize != null && item.oldSize != item.newSize) {
                                    Text(stringResource(Lang.resource_relocate_different_size), color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                    }
                }
                state.error?.let { error ->
                    val message = when ((error as? LocalRelocationException)?.reason) {
                        LocalRelocationException.Reason.NOT_VIDEO -> Lang.resource_relocate_not_video
                        LocalRelocationException.Reason.NOT_DIRECTORY -> Lang.resource_relocate_not_directory
                        LocalRelocationException.Reason.UNSUPPORTED_PATH -> Lang.resource_relocate_opaque
                        LocalRelocationException.Reason.OVERLAPPING_ROOTS -> Lang.resource_relocate_overlap
                        LocalRelocationException.Reason.INCOMPLETE_DIRECTORY -> Lang.resource_relocate_incomplete
                        LocalRelocationException.Reason.DESTINATION_CONFLICT -> Lang.resource_relocate_conflict
                        LocalRelocationException.Reason.CHANGED_SINCE_PREVIEW -> Lang.resource_relocate_changed
                        null -> null
                    }
                    Text(if (message == null) error.message.orEmpty() else stringResource(message), color = MaterialTheme.colorScheme.error)
                    (error as? LocalRelocationException)?.resourceName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(onConfirm, enabled = state.plan != null && !state.loading && !state.saving && state.error == null,
                modifier = Modifier.testTag("resource-relocation-confirm")) {
                Text(stringResource(Lang.resource_relocate_confirm))
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !state.saving) { Text(stringResource(Lang.resource_cancel)) } },
    )
}
