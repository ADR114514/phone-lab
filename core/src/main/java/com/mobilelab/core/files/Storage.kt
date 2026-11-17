package com.mobilelab.core.files

import android.content.Context
import com.mobilelab.core.time.TimeFmt
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Experiment storage layout (no runtime storage permission required):
 *
 *   <external-files>/experiments/<appTag>/<sessionStamp>/
 *
 * Files stay inside the app sandbox and are published either through the
 * system document picker or the share sheet.
 */
object Storage {

    fun appRoot(context: Context, appTag: String): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "experiments/$appTag")
        dir.mkdirs()
        return dir
    }

    fun newSessionDir(context: Context, appTag: String, label: String): File {
        val root = appRoot(context, appTag)
        val stamp = TimeFmt.fileStamp(System.currentTimeMillis())
        var candidate = File(root, "${stamp}_$label")
        var index = 2
        while (candidate.exists()) {
            candidate = File(root, "${stamp}_${label}_$index")
            index++
        }
        candidate.mkdirs()
        return candidate
    }

    fun ensureDir(parent: File, name: String): File {
        val dir = File(parent, name)
        dir.mkdirs()
        return dir
    }

    fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    fun appendText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.appendText(text)
    }

    fun zipDirectory(dir: File, zipFile: File): File {
        zipFile.parentFile?.mkdirs()
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            val rootPath = dir.toPath()
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = rootPath.relativize(file.toPath()).toString().replace('\\', '/')
                zos.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    fun countFiles(dir: File, extension: String = ""): Int =
        if (!dir.exists()) 0 else dir.walkTopDown().count { it.isFile && it.name.endsWith(extension) }
}
