@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.omilator.data.settings

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.posix.fopen
import platform.posix.fclose
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.rename

/**
 * Persistence shim that adapts Foundation file I/O to SettingsStore's
 * (readText/writeText) lambdas. Stores settings as a JSON file inside the
 * app sandbox so they survive app restarts and backups.
 *
 * On iOS, settings live at `<Documents>/settings.json` so the user can
 * inspect or reset them via Files > Omilator if needed.
 *
 * File reads/writes use POSIX fopen/fread/fwrite — same pattern as
 * NativeCoreController's ROM loader. Foundation NSData factories had
 * inconsistent Kotlin/Native exposure so we sidestep them.
 */
class IosSettingsPersistence(private val settingsPath: String) {

    suspend fun read(path: String): String? = withContext(Dispatchers.Default) {
        val fm = NSFileManager.defaultManager()
        if (!fm.fileExistsAtPath(path)) return@withContext null
        memScoped {
            val fp = fopen(path, "rb") ?: return@withContext null
            try {
                fseek(fp, 0, 2) // SEEK_END
                val size = ftell(fp).toInt()
                fseek(fp, 0, 0) // SEEK_SET
                if (size <= 0) return@withContext ""
                val buf = allocArray<ByteVar>(size)
                val read = fread(buf, 1u, size.toULong(), fp)
                if (read == 0UL) return@withContext null
                buf.readBytes(read.toInt()).decodeToString()
            } finally {
                fclose(fp)
            }
        }
    }

    suspend fun write(path: String, content: String) = withContext(Dispatchers.Default) {
        val fm = NSFileManager.defaultManager()
        // Ensure parent dir exists (Documents should always exist but be
        // defensive — first launch can race).
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            fm.createDirectoryAtPath(
                parent, withIntermediateDirectories = true, attributes = null, error = null,
            )
        }
        val bytes = content.encodeToByteArray()
        // Write to a sibling temp file, then rename over the target — a
        // direct "wb" truncates in place, and a crash mid-write leaves
        // corrupt JSON that silently resets every setting.
        val tmpPath = "$path.tmp"
        memScoped {
            val fp = fopen(tmpPath, "wb") ?: throw RuntimeException("cannot open $tmpPath for writing")
            try {
                val buf = allocArray<ByteVar>(bytes.size)
                for (i in bytes.indices) buf[i] = bytes[i]
                val written = fwrite(buf, 1u, bytes.size.toULong(), fp)
                check(written == bytes.size.toULong()) { "short settings write: $written/${bytes.size}" }
            } finally {
                fclose(fp)
            }
        }
        check(rename(tmpPath, path) == 0) { "settings replace failed for $path" }
    }

    fun settingsStore(): SettingsStore = SettingsStore(
        readText = { path -> read(path) },
        writeText = { path, content -> write(path, content) },
    )
}

/**
 * Default settings file path inside the iOS app sandbox.
 * Caller passes the Documents directory (from NSSearchPathForDirectoriesInDomains).
 */
fun defaultIosSettingsPath(documentsDir: String): String = "$documentsDir/settings.json"


