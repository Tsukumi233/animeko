package me.him188.ani.app.ui.resource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.him188.ani.app.domain.mediasource.fileservice.FileServiceArguments
import me.him188.ani.app.domain.mediasource.fileservice.FileServiceProtocol
import me.him188.ani.app.domain.mediasource.instance.MediaSourceSave
import me.him188.ani.app.ui.lang.*
import me.him188.ani.datasources.api.source.MediaSourceTier
import org.jetbrains.compose.resources.stringResource

/** The editor keeps saved passwords outside the UI state. */
data class ResourceConnectionEditor(
    val save: MediaSourceSave,
    val arguments: FileServiceArguments,
    val username: String,
    val domain: String,
)

@Composable
internal fun ResourceConnectionDialog(
    protocol: FileServiceProtocol,
    onDismiss: () -> Unit,
    onSave: (FileServiceArguments, String, String, String) -> Unit,
    editor: ResourceConnectionEditor? = null,
    saving: Boolean = false,
    hasError: Boolean = false,
    onRemove: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(editor?.arguments?.name ?: protocol.name) }
    var endpoint by remember { mutableStateOf(editor?.arguments?.endpoint.orEmpty()) }
    var share by remember { mutableStateOf(editor?.arguments?.share.orEmpty()) }
    var root by remember { mutableStateOf(editor?.arguments?.root.orEmpty()) }
    var port by remember { mutableStateOf((editor?.arguments?.port ?: 445).toString()) }
    var username by remember { mutableStateOf(editor?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf(editor?.domain.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    if (removing && onRemove != null) {
        AlertDialog(onDismissRequest = { if (!saving) removing = false },
            title = { Text(stringResource(Lang.resource_remove_connection)) },
            text = { Text(stringResource(Lang.resource_remove_connection_description, name)) },
            confirmButton = { TextButton(onRemove, enabled = !saving) { Text(stringResource(Lang.resource_remove_connection)) } },
            dismissButton = { TextButton({ removing = false }, enabled = !saving) { Text(stringResource(Lang.resource_cancel)) } })
        return
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(if (editor == null) Lang.resource_add_connection else Lang.resource_edit_connection)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text(stringResource(Lang.resource_name)) }, singleLine = true, enabled = !saving) }
                item { OutlinedTextField(endpoint, { endpoint = it }, label = { Text(stringResource(if (protocol == FileServiceProtocol.SMB) Lang.resource_host else Lang.resource_url)) }, singleLine = true, enabled = !saving, modifier = Modifier.testTag("connection-endpoint")) }
                if (protocol == FileServiceProtocol.SMB) {
                    item { OutlinedTextField(share, { share = it }, label = { Text(stringResource(Lang.resource_share)) }, singleLine = true, enabled = !saving, modifier = Modifier.testTag("connection-share")) }
                    item { OutlinedTextField(port, { port = it }, label = { Text(stringResource(Lang.resource_port)) }, singleLine = true, enabled = !saving, modifier = Modifier.testTag("connection-port")) }
                    item { OutlinedTextField(root, { root = it }, label = { Text(stringResource(Lang.resource_root_path)) }, singleLine = true, enabled = !saving, modifier = Modifier.testTag("connection-root")) }
                }
                item { OutlinedTextField(username, { username = it }, label = { Text(stringResource(Lang.resource_username)) }, singleLine = true, enabled = !saving) }
                item { OutlinedTextField(password, { password = it }, label = { Text(stringResource(Lang.resource_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !saving, modifier = Modifier.testTag("connection-password")) }
                if (editor != null) item { Text(stringResource(Lang.resource_keep_password)) }
                if (protocol == FileServiceProtocol.SMB) item { OutlinedTextField(domain, { domain = it }, label = { Text(stringResource(Lang.resource_domain)) }, singleLine = true, enabled = !saving) }
                if (invalid || hasError) item { Text(stringResource(if (invalid) Lang.resource_invalid_connection else Lang.resource_load_failed), color = MaterialTheme.colorScheme.error) }
                if (onRemove != null) item {
                    TextButton({ removing = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Lang.resource_remove_connection)) }
                }
            }
        }, confirmButton = { TextButton({
            val args = try {
                FileServiceArguments(name.ifBlank { protocol.name }, protocol, endpoint.trim(), share.trim(),
                    root.trim(), if (protocol == FileServiceProtocol.SMB) requireNotNull(port.toIntOrNull()) else 445,
                    editor?.arguments?.tier ?: MediaSourceTier.Fallback)
            } catch (_: IllegalArgumentException) { invalid = true; return@TextButton }
            onSave(args, username.trim(), password, domain.trim())
        }, enabled = !saving, modifier = Modifier.testTag("connection-save")) { Text(stringResource(if (editor == null) Lang.resource_add else Lang.resource_save)) } },
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text(stringResource(Lang.resource_cancel)) } })
}
