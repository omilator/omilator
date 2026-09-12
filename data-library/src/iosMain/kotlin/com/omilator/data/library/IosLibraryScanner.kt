@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.omilator.data.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSFileManager
import platform.Foundation.NSDirectoryEnumerationSkipsHiddenFiles

class IosLibraryScanner : LibraryScanner {
    override suspend fun scan(directory: String): List<Game> = withContext(Dispatchers.Default) {
        // Scan the requested directory (persisted library directories and
        // picked subdirectories included), falling back to Documents for a
        // bare "Documents" reference. Recursive, because ROMs arrive via the
        // Files app in whatever folder structure the user synced.
        val root = if (directory.isBlank() || directory == "Documents") {
            documentsDirectory() ?: return@withContext emptyList()
        } else {
            directory
        }
        val fm = NSFileManager.defaultManager
        val enumerator = fm.enumeratorAtPath(root)
            ?: return@withContext emptyList()
        val games = mutableListOf<Game>()
        while (true) {
            val rel = enumerator.nextObject() as? String ?: break
            val ext = rel.substringAfterLast('.', "")
            val system = GameSystem.detectByExtension(ext) ?: continue
            val fullPath = "$root/$rel"
            val attrs = fm.attributesOfItemAtPath(fullPath, null)
            val size = (attrs?.get("NSFileSize") as? Long) ?: 0L
            games.add(
                Game(
                    id = fullPath,
                    title = cleanRomTitle(rel.substringAfterLast('/').substringBeforeLast('.')),
                    system = system,
                    filePath = fullPath,
                    fileSizeBytes = size,
                ),
            )
        }
        games.sortedBy { it.title.lowercase() }
    }

    private fun documentsDirectory(): String? {
        val paths = NSSearchPathForDirectoriesInDomains(
            platform.Foundation.NSDocumentDirectory,
            platform.Foundation.NSUserDomainMask,
            true,
        )
        return paths.firstOrNull() as? String
    }
}

