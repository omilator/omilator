package com.omilator.ui.player

import com.omilator.data.library.GameSystem
import com.omilator.data.settings.DesktopPaths
import java.io.File

/**
 * Explicit, narrowly scoped compatible-core policy. The preferred core
 * stays first; the fallback lists only cores documented compatible with
 * that exact system (Libretro's mGBA docs list Game Boy and Game Boy
 * Color). It is NOT an unknown-extension safety net: a system must already
 * be known (by extension or explicit server metadata) to get here.
 *
 * Without the GB/GBC fallback, automated setup could never satisfy the
 * default resolver: it installs mGBA (GB/GBC/GBA) but not SameBoy, which
 * is what those systems preferred.
 */
private val compatibleCores: Map<GameSystem, List<String>> = buildMap {
    for (system in GameSystem.entries) put(system, listOf(system.preferredCore))
    put(GameSystem.GAME_BOY, listOf(GameSystem.GAME_BOY.preferredCore, "mgba"))
    put(GameSystem.GAME_BOY_COLOR, listOf(GameSystem.GAME_BOY_COLOR.preferredCore, "mgba"))
}

/** Core stems that can run [system], preferred first. */
internal fun compatibleCoreStems(system: GameSystem): List<String> =
    compatibleCores[system] ?: listOf(system.preferredCore)

/** The platform's core library extension. */
internal fun coreLibraryExtension(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") -> "dylib"
        os.contains("win") -> "dll"
        else -> "so"
    }
}

/** Default search roots: developer checkouts first, then the shared
 *  installed-core directory (DesktopPaths.coresDir — the same property the
 *  installer writes to; on Linux the config tree and data tree differ). */
internal fun defaultCoreRoots(): List<File> =
    listOf(File("cores"), File("../cores"), DesktopPaths.coresDir)

/**
 * Find an installed core for [system] under [roots]. The preferred core
 * wins across ALL roots before any compatible fallback is considered — a
 * developer-root fallback must not shadow an installed preferred core.
 * Presence only: whether the file actually loads is the controller's call.
 */
internal fun findInstalledCore(
    system: GameSystem,
    roots: List<File>,
    extension: String,
): File? {
    for (stem in compatibleCoreStems(system)) {
        for (root in roots) {
            val file = File(root, "${stem}_libretro.$extension")
            if (file.isFile && file.length() > 0L) return file
        }
    }
    return null
}

/** Resolve the core for a ROM. Server-provided [systemOverride] takes
 *  precedence over extension detection — downloaded ROMs land in the cache
 *  as extensionless `.rom` files, and their platform is only known from
 *  server metadata. Returns null when the system is unknown (the caller
 *  shows the no-mapping screen); a known system with no installed core
 *  still gets the expected install path so the player surfaces the real
 *  native-load error. */
internal fun resolveCorePath(
    romPath: String,
    systemOverride: GameSystem? = null,
    roots: List<File> = defaultCoreRoots(),
): String? {
    val system = systemOverride
        ?: GameSystem.detectByExtension(File(romPath).extension)
        ?: return null
    val extension = coreLibraryExtension()
    val installed = findInstalledCore(system, roots, extension)
    if (installed != null) return installed.absolutePath
    return File(DesktopPaths.coresDir, "${system.preferredCore}_libretro.$extension").absolutePath
}
