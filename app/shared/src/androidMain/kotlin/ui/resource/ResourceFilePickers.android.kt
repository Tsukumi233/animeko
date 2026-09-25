package me.him188.ani.app.ui.resource

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberResourceFilePickers(
    onFiles: (List<String>) -> Unit,
    onDirectory: (String) -> Unit,
    onError: (Throwable) -> Unit,
): ResourceFilePickers {
    val context = LocalContext.current
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) try {
            uris.forEach { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onFiles(uris.map { it.toString() })
        } catch (e: Exception) { onError(e) }
    }
    val directory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            onDirectory(uri.toString())
        } catch (e: Exception) { onError(e) }
    }
    return ResourceFilePickers(
        files = { try { files.launch(arrayOf("video/*")) } catch (e: Exception) { onError(e) } },
        directory = { try { directory.launch(null) } catch (e: Exception) { onError(e) } },
    )
}
