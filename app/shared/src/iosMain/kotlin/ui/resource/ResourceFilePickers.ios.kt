package me.him188.ani.app.ui.resource

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberResourceFilePickers(
    onFiles: (List<String>) -> Unit,
    onDirectory: (String) -> Unit,
    onError: (Throwable) -> Unit,
): ResourceFilePickers {
    val unsupported = { onError(UnsupportedOperationException("Local resource picking is unavailable on this platform")) }
    return ResourceFilePickers(unsupported, unsupported)
}
