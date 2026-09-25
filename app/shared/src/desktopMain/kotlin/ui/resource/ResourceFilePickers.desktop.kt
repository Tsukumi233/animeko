package me.him188.ani.app.ui.resource

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import me.him188.ani.app.domain.media.DroppedFileMedia

@Composable
internal actual fun rememberResourceFilePickers(
    onFiles: (List<String>) -> Unit,
    onDirectory: (String) -> Unit,
    onError: (Throwable) -> Unit,
): ResourceFilePickers {
    val files = rememberFilePickerLauncher(type = FileKitType.File(DroppedFileMedia.VIDEO_EXTENSIONS.toList()), mode = FileKitMode.Multiple()) { selected ->
        selected?.takeIf { it.isNotEmpty() }?.let { onFiles(it.map { file -> file.file.toURI().toString() }) }
    }
    val directory = rememberDirectoryPickerLauncher { selected -> selected?.let { onDirectory(it.file.toURI().toString()) } }
    return ResourceFilePickers(
        files = { try { files.launch() } catch (e: Exception) { onError(e) } },
        directory = { try { directory.launch() } catch (e: Exception) { onError(e) } },
    )
}
