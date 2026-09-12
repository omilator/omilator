package com.omilator.core.libretro

import com.omilator.core.libretro.api.CoreController
import com.omilator.core.libretro.impl.JniCoreController

actual fun createCoreController(systemDirectory: String): CoreController = JniCoreController(systemDirectory)

internal actual val platformName: String = "Android"
