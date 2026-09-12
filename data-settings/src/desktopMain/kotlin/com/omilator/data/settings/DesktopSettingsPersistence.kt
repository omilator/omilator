package com.omilator.data.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

class DesktopSettingsPersistence(private val configDir: String) {
    val settingsFile: File
        get() {
            Files.createDirectories(Paths.get(configDir))
            return File(configDir, "settings.json")
        }

    suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (file.exists()) file.readText() else null
    }

    suspend fun write(path: String, content: String) = withContext(Dispatchers.IO) {
        // Atomic replace: a direct writeText truncates in place, and a crash
        // mid-write leaves corrupt JSON that silently resets every setting.
        val target = Paths.get(path)
        val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.writeString(temp, content)
        try {
            Files.move(
                temp,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun settingsStore(): SettingsStore = SettingsStore(
        readText = { path -> read(path) },
        writeText = { path, content -> write(path, content) },
    )
}

fun defaultConfigDir(): String {
    val home = System.getProperty("user.home")
    val dir = File(home, "Library/Application Support/Omilator")
    if (!dir.exists()) dir.mkdirs()
    return dir.absolutePath
}
