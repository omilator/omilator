package com.omilator.data.launcher

import com.omilator.data.launcher.backends.CemuBackend
import com.omilator.data.launcher.backends.DolphinBackend
import com.omilator.data.launcher.backends.PpssppBackend
import com.omilator.data.launcher.backends.Rpcs3Backend
import com.omilator.data.launcher.backends.XemuBackend

/** Registry of desktop standalone emulators; mobile players use libretro. */
class StandaloneRegistry {
    val all: List<StandaloneBackend> = listOf(
        PpssppBackend(),
        DolphinBackend(),
        Rpcs3Backend(),
        CemuBackend(),
        XemuBackend(),
    )

    fun available(): List<StandaloneBackend> = all.filter { it.isInstalled() }

    fun forSystem(systemId: String): StandaloneBackend? =
        all.firstOrNull { it.systemId.equals(systemId, ignoreCase = true) && it.isInstalled() }
}
