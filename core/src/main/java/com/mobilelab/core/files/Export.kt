package com.mobilelab.core.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.mobilelab.core.log.LabLog
import java.io.File

/** Share sheet / document-picker helpers. */
object ShareUtil {

    fun shareFile(context: Context, file: File, mime: String = "*/*") {
        val authority = context.packageName + ".fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 " + file.name))
    }

    fun copyToUri(context: Context, file: File, uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        true
    }.onFailure { LabLog.e("ShareUtil", "copy to uri failed: " + uri, it) }
        .getOrDefault(false)
}

/**
 * Composable wrapper around ACTION_CREATE_DOCUMENT. The caller only provides
 * the file; the system picker decides the destination.
 */
class FileExporter internal constructor(
    private val saveBlock: (File, String) -> Unit,
    private val shareBlock: (File, String) -> Unit,
) {
    fun saveAs(file: File, mime: String = "*/*") {
        saveBlock(file, mime)
    }

    fun share(file: File, mime: String = "*/*") {
        shareBlock(file, mime)
    }
}

@Composable
fun rememberFileExporter(): FileExporter {
    val context: Context = LocalContext.current
    var pending: Pair<File, String>? by remember { mutableStateOf<Pair<File, String>?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val target: Pair<File, String>? = pending
        pending = null
        if (uri != null && target != null) {
            ShareUtil.copyToUri(context, target.first, uri)
        }
    }

    val exporter: FileExporter = remember(context) {
        FileExporter(
            saveBlock = { file: File, _: String ->
                pending = Pair(file, file.name)
                createDocument.launch(file.name)
            },
            shareBlock = { file: File, mime: String ->
                ShareUtil.shareFile(context, file, mime)
            },
        )
    }
    return exporter
}
