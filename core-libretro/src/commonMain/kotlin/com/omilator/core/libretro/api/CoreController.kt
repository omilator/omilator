package com.omilator.core.libretro.api

interface CoreController {
    val isLoaded: Boolean

    suspend fun loadCore(path: String): SystemInfo
    suspend fun loadGame(romPath: String): AvInfo

    fun runFrame()

    fun reset()
    fun unloadGame()
    fun unloadCore()

    fun attach(video: VideoSink, audio: AudioSink, input: InputSource)
    fun detach()

    fun saveState(path: String): Boolean
    fun loadState(path: String): Boolean

    val memorySize: UInt
    fun readMemory(region: UInt, offset: UInt, size: UInt): ByteArray
    fun writeMemory(region: UInt, offset: UInt, data: ByteArray)

    /**
     * Battery-backed SRAM. Defaults are empty no-ops so platform controllers
     * can adopt them incrementally; the engine flushes before game/core
     * unload, which is the only durable copy of battery saves.
     */
    fun readSaveRam(): ByteArray = ByteArray(0)
    fun writeSaveRam(data: ByteArray) {}

    /** Cheats — Game Genie / Game Shark / Code Breaker codes. */
    fun cheatReset()
    /** Save/load state to/from memory (for rewind buffer). */
    fun saveStateToMemory(): ByteArray
    fun loadStateFromMemory(bytes: ByteArray): Boolean

    fun cheatSet(index: Int, enabled: Boolean, code: String)

    /** Core-specific options (resolution, accuracy, region, etc.). */
    fun getCoreOptions(): List<CoreOption> = emptyList()
    fun setOptionValue(key: String, value: String) {}
    fun getOptionSelections(): Map<String, String> = emptyMap()
}

class CoreNotLoadedException(message: String) : RuntimeException(message)
class CoreLoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class GameLoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
