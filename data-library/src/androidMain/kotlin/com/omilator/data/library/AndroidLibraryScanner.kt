package com.omilator.data.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scans either a plain filesystem directory or a SAF tree Uri. The directory
 * picker persists content:// URIs; treating them as File paths scanned zero
 * ROMs no matter which permissions were granted.
 */
class AndroidLibraryScanner(
    private val context: Context,
) : LibraryScanner {

    override suspend fun scan(directory: String): List<Game> = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(directory) }.getOrNull()
        if (uri?.scheme == "content") {
            val root = DocumentFile.fromTreeUri(context, uri)
                ?: return@withContext emptyList()
            scanDocumentTree(root)
        } else {
            scanFileTree(File(directory))
        }
    }

    private fun scanFileTree(root: File): List<Game> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                gameFor(file.name, file.nameWithoutExtension, file.absolutePath, file.length())
            }
            .sortedBy { it.title.lowercase() }
            .toList()
    }

    private fun scanDocumentTree(root: DocumentFile): List<Game> =
        root.listFiles().flatMap { doc ->
            when {
                doc.isDirectory -> scanDocumentTree(doc)
                doc.isFile -> gameFor(
                    name = doc.name ?: "",
                    title = (doc.name ?: "").substringBeforeLast('.'),
                    path = doc.uri.toString(),
                    size = doc.length(),
                )?.let(::listOf) ?: emptyList()
                else -> emptyList()
            }
        }.sortedBy { it.title.lowercase() }

    private fun gameFor(name: String, title: String, path: String, size: Long): Game? {
        val ext = name.substringAfterLast('.', "")
        val system = GameSystem.detectByExtension(ext) ?: return null
        return Game(
            id = path,
            title = title,
            system = system,
            filePath = path,
            fileSizeBytes = size,
        )
    }
}
