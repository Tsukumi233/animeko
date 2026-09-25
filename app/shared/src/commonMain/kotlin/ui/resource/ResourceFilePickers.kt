package me.him188.ani.app.ui.resource

import androidx.compose.runtime.Composable

internal class ResourceFilePickers(val files: () -> Unit, val directory: () -> Unit)

@Composable
internal expect fun rememberResourceFilePickers(
    onFiles: (List<String>) -> Unit,
    onDirectory: (String) -> Unit,
    onError: (Throwable) -> Unit,
): ResourceFilePickers
