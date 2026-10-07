package com.omilator.core.libretro.impl

import com.omilator.core.libretro.api.AudioSink
import com.omilator.core.libretro.api.AvInfo
import com.omilator.core.libretro.api.CoreController
import com.omilator.core.libretro.api.CoreNotLoadedException
import com.omilator.core.libretro.api.CoreOption
import com.omilator.core.libretro.api.CoreOptionValue
import com.omilator.core.libretro.api.Framebuffer
import com.omilator.core.libretro.api.Geometry
import com.omilator.core.libretro.api.InputSource
import com.omilator.core.libretro.api.PixelFormat
import com.omilator.core.libretro.api.RetroSystemAvInfo
import com.omilator.core.libretro.api.SystemInfo
import com.omilator.core.libretro.api.Timing
import com.omilator.core.libretro.api.VideoSink

internal class JniCoreController(private val systemDirectory: String) : CoreController {

    private var loaded: Boolean = false
    private var nativeHandle: Long = 0

    private companion object {
        const val EXPERIMENTAL = 0x10000
        const val SET_PIXEL_FORMAT = 10
        const val SET_INPUT_DESCRIPTORS = 11
        const val GET_VARIABLE = 15
        const val SET_VARIABLES = 16
        const val GET_VARIABLE_UPDATE = 17
        const val SET_SYSTEM_AV_INFO = 32
        const val GET_AUDIO_VIDEO_ENABLE = 47 or EXPERIMENTAL
        const val GET_INPUT_BITMASKS = 51 or EXPERIMENTAL
        const val GET_CORE_OPTIONS_VERSION = 52
        const val SET_CORE_OPTIONS = 53
        const val SET_CORE_OPTIONS_INTL = 54
    }

    private var videoSink: VideoSink? = null
    private var audioSink: AudioSink? = null
    private var inputSource: InputSource? = null
    private var pixelFormat: Int = 0
    private var frameCount: Int = 0

    /** Options declared by the core via SET_CORE_OPTIONS / SET_VARIABLES. */
    private val coreOptions = mutableListOf<CoreOption>()

    /** User-selected values for each option (key -> chosen value). */
    private val optionSelections = mutableMapOf<String, String>()

    /** Button descriptions from SET_INPUT_DESCRIPTORS. */
    private val inputDescriptors = mutableMapOf<String, String>()

    /** Set when a UI option change lands; cleared once the core observed it. */
    private var variablesDirty = false

    /** Mid-run SET_SYSTEM_AV_INFO forwarder (see onEnvironment cmd 32). */
    private var systemAvInfoListener: ((AvInfo) -> Unit)? = null

    override fun setSystemAvInfoListener(listener: ((AvInfo) -> Unit)?) {
        systemAvInfoListener = listener
    }

    override val isLoaded: Boolean get() = loaded
    override val memorySize: UInt = 0u

    override suspend fun loadCore(path: String): SystemInfo {
        check(nativeHandle == 0L) { "a core is already loaded" }
        System.loadLibrary("omilator_jni")
        // Pointer-valued environment outputs are answered from native-side
        // stable storage, so the directories must be installed there before
        // retro_init() runs inside loadCoreNative.
        nativeHandle = createNativeState()
        val h = nativeHandle
        setEnvPathsNative(h, systemDirectory, systemDirectory, path)
        if (!loadCoreNative(h, path)) {
            destroyNativeState(h)
            nativeHandle = 0
            throw RuntimeException("Failed to load core: $path")
        }
        loaded = true
        val ext = systemInfoExtensionsNative(h)
        return SystemInfo(
            libraryName = systemInfoNameNative(h),
            libraryVersion = systemInfoVersionNative(h),
            validExtensions = ext.split("|").filter { it.isNotBlank() },
            needFullpath = systemInfoNeedFullpathNative(h),
            blockExtract = systemInfoBlockExtractNative(h),
        )
    }

    override suspend fun loadGame(romPath: String): AvInfo {
        check(loaded) { throw CoreNotLoadedException("Core not loaded") }
        if (!loadGameNative(nativeHandle, romPath)) {
            throw RuntimeException("retro_load_game failed for $romPath")
        }
        // Real values from retro_get_system_av_info — the sample rate feeds
        // AudioTrack directly, so a hardcoded GBA figure pitched every other
        // system wrong.
        val av = systemAvInfoNative(nativeHandle)
        val fps = if (av[5] > 0.0) av[5] else 60.0
        val rate = if (av[6] > 0.0) av[6] else 48000.0
        return AvInfo(
            geometry = Geometry(
                baseWidth = av[0].toUInt(),
                baseHeight = av[1].toUInt(),
                maxWidth = av[2].toUInt(),
                maxHeight = av[3].toUInt(),
                aspectRatio = if (av[4] > 0.0f) av[4].toFloat() else 1.5f,
            ),
            timing = Timing(fps = fps.toFloat(), sampleRate = rate),
        )
    }

    override fun runFrame() {
        runFrameNative(nativeHandle)
    }

    override fun reset() = resetNative(nativeHandle)
    override fun unloadGame() { unloadGameNative(nativeHandle) }
    override fun unloadCore() {
        val h = nativeHandle
        if (h == 0L) return
        deinitNative(h)
        destroyNativeState(h)
        nativeHandle = 0
        loaded = false
    }

    override fun attach(video: VideoSink, audio: AudioSink, input: InputSource) {
        videoSink = video
        audioSink = audio
        inputSource = input
    }

    override fun detach() {
        videoSink = null
        audioSink = null
        inputSource = null
    }

    override fun saveState(path: String): Boolean {
        val size = serializeSizeNative(nativeHandle)
        if (size <= 0L) return false
        val bytes = ByteArray(size.toInt())
        if (!saveStateNative(nativeHandle, bytes, size)) return false
        java.io.File(path).writeBytes(bytes)
        return true
    }

    override fun loadState(path: String): Boolean {
        val file = java.io.File(path)
        if (!file.exists()) return false
        val bytes = file.readBytes()
        return loadStateNative(nativeHandle, bytes, bytes.size.toLong())
    }

    override fun readMemory(region: UInt, offset: UInt, size: UInt): ByteArray = ByteArray(size.toInt())
    override fun cheatReset() {}
    override fun saveStateToMemory(): ByteArray = ByteArray(0)
    override fun loadStateFromMemory(bytes: ByteArray): Boolean = false

    override fun cheatSet(index: Int, enabled: Boolean, code: String) {}

    override fun writeMemory(region: UInt, offset: UInt, data: ByteArray) {}

    override fun readSaveRam(): ByteArray = readSaveRamNative(nativeHandle)
    override fun writeSaveRam(data: ByteArray) = writeSaveRamNative(nativeHandle, data)

    override fun getCoreOptions(): List<CoreOption> = coreOptions.toList()
    override fun setOptionValue(key: String, value: String) {
        if (optionSelections[key] != value) {
            optionSelections[key] = value
            variablesDirty = true
        }
    }
    override fun getOptionSelections(): Map<String, String> = optionSelections.toMap()

    // Trampolines invoked by libretro_jni.cpp. The C++ side resolves this
    // controller instance through the thread-local active state and calls
    // these methods through JNI.
    @Suppress("unused")
    fun onEnvironment(cmd: Int, dataPtr: Long): Boolean {
        // Success only after the command's required output is satisfied.
        // Directory/path/log commands (9/19/27/31) are answered in native
        // code before this trampoline is reached. GET_INPUT_BITMASKS must
        // carry the EXPERIMENTAL bit and mask polling is NOT implemented,
        // so it is honestly refused.
        return when (cmd) {
            SET_PIXEL_FORMAT -> {
                if (dataPtr != 0L) {
                    pixelFormat = readNativeInt(dataPtr)
                    true
                } else false
            }
            GET_CORE_OPTIONS_VERSION -> {
                // Advertise v1 — the interface the SET_CORE_OPTIONS parser
                // below actually implements.
                writeNativeInt(dataPtr, 1)
                true
            }
            SET_CORE_OPTIONS -> {
                parseCoreOptions(dataPtr)
                true
            }
            SET_CORE_OPTIONS_INTL -> {
                // retro_core_options_intl is a wrapper: { definitions *us;
                // definitions *local; }. The US pointer at offset 0 leads to
                // an array laid out exactly like SET_CORE_OPTIONS.
                if (dataPtr != 0L) {
                    val us = readNativeLong(dataPtr)
                    if (us != 0L) parseCoreOptions(us)
                }
                true
            }
            SET_VARIABLES -> {
                parseLegacyVariables(dataPtr)
                true
            }
            GET_VARIABLE -> handleGetVariable(dataPtr)
            GET_VARIABLE_UPDATE -> {
                writeNativeByte(dataPtr, if (variablesDirty) 1.toByte() else 0.toByte())
                variablesDirty = false
                true
            }
            SET_INPUT_DESCRIPTORS -> {
                parseInputDescriptors(dataPtr)
                true
            }
            SET_SYSTEM_AV_INFO -> {
                // Forward so mid-run display-mode changes reach run-loop
                // pacing + audio reconfigure: the command was previously
                // declined, which made MobilePlayerScreen's listener
                // registration dead wiring on both mobile platforms
                // (pass C finding 3).
                if (dataPtr != 0L) {
                    val info = RetroSystemAvInfo.parse(
                        dataPtr,
                        ::readNativeInt,
                        ::readNativeFloat,
                        ::readNativeDouble,
                    )
                    systemAvInfoListener?.invoke(info)
                }
                // Accepted even with a NULL payload (probe semantics —
                // mirrors desktop): nothing to parse, nothing to notify.
                true
            }
            GET_AUDIO_VIDEO_ENABLE -> {
                // Bit0 video, bit1 audio — both enabled.
                writeNativeInt(dataPtr, 0x1 or 0x2)
                true
            }
            GET_INPUT_BITMASKS -> false
            else -> false
        }
    }

    @Suppress("unused")
    fun onVideo(dataPtr: Long, width: Int, height: Int, pitch: Long) {
        if (dataPtr == 0L || width <= 0 || height <= 0) return
        val size = (height.toLong() * pitch).toInt()
        val bytes = ByteArray(size)
        copyNativeBytes(dataPtr, bytes, size)
        val format = when (pixelFormat) {
            1 -> PixelFormat.XRGB8888
            2 -> PixelFormat.RGB565
            else -> PixelFormat.ORGB1555
        }
        frameCount++
        videoSink?.onFrame(
            Framebuffer(
                data = bytes,
                width = width.toUInt(),
                height = height.toUInt(),
                pitch = pitch.toUInt(),
                format = format,
            ),
        )
    }

    @Suppress("unused")
    fun onAudioBatch(dataPtr: Long, frames: Long): Long {
        if (dataPtr == 0L || frames <= 0L) return frames
        val sampleCount = (frames * 2).toInt()
        val samples = ShortArray(sampleCount)
        copyNativeShorts(dataPtr, samples, sampleCount)
        audioSink?.onSamples(samples)
        return frames
    }

    @Suppress("unused")
    fun onAudioSample(left: Short, right: Short) {
        audioSink?.onSamples(shortArrayOf(left, right))
    }

    @Suppress("unused")
    fun onInputState(port: Int, device: Int, index: Int, id: Int): Short {
        if (port != 0 || device != 1) return 0
        val source = inputSource ?: return 0
        return source.poll(port, intToInputDevice(device), index, id).toShort()
    }

    private fun intToInputDevice(device: Int) = when (device) {
        1 -> com.omilator.core.libretro.api.InputDevice.JOYPAD
        2 -> com.omilator.core.libretro.api.InputDevice.MOUSE
        3 -> com.omilator.core.libretro.api.InputDevice.KEYBOARD
        5 -> com.omilator.core.libretro.api.InputDevice.ANALOG
        else -> com.omilator.core.libretro.api.InputDevice.NONE
    }

    // ---- Core option parsing (layout parity with desktop FfmCoreController) ----

    private fun readCString(ptr: Long): String {
        if (ptr == 0L) return ""
        return readNativeCString(ptr) ?: ""
    }

    private fun parseCoreOptions(defsPtr: Long) {
        coreOptions.clear()
        if (defsPtr == 0L) return

        // retro_core_option_definition (64-bit):
        //   char* key          @ 0
        //   char* desc         @ 8
        //   char* info         @ 16
        //   retro_core_option_value values[128] @ 24   (each 16 bytes)
        //   char* default_value @ 2072
        // stride = 2080
        val valuesOffset = 24L
        val valueCount = 128
        val valSize = 16L
        val defaultOffset = valuesOffset + valueCount * valSize
        val defSize = defaultOffset + 8L

        var offset = 0L
        while (true) {
            val key = readCString(readNativeLong(defsPtr + offset))
            if (key.isEmpty()) break

            val desc = readCString(readNativeLong(defsPtr + offset + 8)).ifEmpty { key }
            val infoPtr = readNativeLong(defsPtr + offset + 16)
            val info = if (infoPtr != 0L) readCString(infoPtr) else null

            val values = mutableListOf<CoreOptionValue>()
            for (i in 0 until valueCount) {
                val valOffset = offset + valuesOffset + i * valSize
                val valuePtr = readNativeLong(defsPtr + valOffset)
                if (valuePtr == 0L) break
                val value = readCString(valuePtr)
                val labelPtr = readNativeLong(defsPtr + valOffset + 8)
                val label = if (labelPtr != 0L) readCString(labelPtr) else value
                values.add(CoreOptionValue(value, label))
            }

            val defaultPtr = readNativeLong(defsPtr + offset + defaultOffset)
            val defaultVal = if (defaultPtr != 0L) {
                readCString(defaultPtr)
            } else {
                values.firstOrNull()?.value ?: ""
            }

            coreOptions.add(CoreOption(key, desc, info, defaultVal, values))
            if (key !in optionSelections) {
                optionSelections[key] = defaultVal
            }
            offset += defSize
        }
    }

    // Legacy RETRO_ENVIRONMENT_SET_VARIABLES: retro_variable[]
    // { const char* key; const char* value; }, NULL key terminated. The
    // legacy value packs "Description; Default|choice|..." into one string.
    private fun parseLegacyVariables(dataPtr: Long) {
        if (dataPtr == 0L) return
        var offset = 0L
        while (true) {
            val keyPtr = readNativeLong(dataPtr + offset)
            if (keyPtr == 0L) break
            val key = readCString(keyPtr)
            val valuePtr = readNativeLong(dataPtr + offset + 8)
            val raw = if (valuePtr != 0L) readCString(valuePtr) else ""
            val desc = raw.substringBefore("; ").ifEmpty { key }
            val listPart = raw.substringAfter("; ", "")
            val choices = listPart.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            val default = choices.firstOrNull() ?: ""
            val values = choices.map { CoreOptionValue(it, it) }
            coreOptions.add(CoreOption(key, desc, null, default, values))
            if (key !in optionSelections && default.isNotBlank()) {
                optionSelections[key] = default
            }
            offset += 16L
        }
    }

    private fun handleGetVariable(dataPtr: Long): Boolean {
        // data == NULL is a support probe: the facility exists, so the
        // answer is success with nothing written.
        if (dataPtr == 0L) return true
        // retro_variable: { const char* key; const char* value; } — the
        // value pointer goes to offset 8, into native stable storage.
        val key = readCString(readNativeLong(dataPtr))
        val value = if (key.isEmpty()) null else optionSelections[key]
        if (value == null) {
            clearNativePtr(dataPtr + 8)
        } else {
            installNativeString(nativeHandle, dataPtr + 8, value)
        }
        return true
    }

    private fun parseInputDescriptors(dataPtr: Long) {
        inputDescriptors.clear()
        if (dataPtr == 0L) return
        // retro_input_descriptor { port, device, index, id (u32 each),
        // desc (char*) } = 24 bytes, terminated by a NULL description.
        val structSize = 24L
        var offset = 0L
        while (true) {
            val descPtr = readNativeLong(dataPtr + offset + 16)
            if (descPtr == 0L) break
            val port = readNativeInt(dataPtr + offset)
            val device = readNativeInt(dataPtr + offset + 4)
            val id = readNativeInt(dataPtr + offset + 12)
            val desc = readCString(descPtr)
            if (port == 0 && device == 1 && desc.isNotBlank()) {
                inputDescriptors["btn_$id"] = desc
            }
            offset += structSize
        }
    }

    // JNI declarations
    private external fun createNativeState(): Long
    private external fun destroyNativeState(handle: Long)
    private external fun loadCoreNative(handle: Long, path: String): Boolean
    private external fun setEnvPathsNative(handle: Long, systemDir: String, saveDir: String, corePath: String)
    private external fun loadGameNative(handle: Long, path: String): Boolean
    private external fun systemAvInfoNative(handle: Long): DoubleArray
    private external fun runFrameNative(handle: Long)
    private external fun resetNative(handle: Long)
    private external fun unloadGameNative(handle: Long)
    private external fun deinitNative(handle: Long)
    private external fun apiVersionNative(handle: Long): Int
    private external fun systemInfoNameNative(handle: Long): String
    private external fun systemInfoVersionNative(handle: Long): String
    private external fun systemInfoExtensionsNative(handle: Long): String
    private external fun systemInfoNeedFullpathNative(handle: Long): Boolean
    private external fun systemInfoBlockExtractNative(handle: Long): Boolean
    private external fun serializeSizeNative(handle: Long): Long
    private external fun saveStateNative(handle: Long, bytes: ByteArray, size: Long): Boolean
    private external fun loadStateNative(handle: Long, bytes: ByteArray, size: Long): Boolean
    private external fun readSaveRamNative(handle: Long): ByteArray
    private external fun writeSaveRamNative(handle: Long, data: ByteArray)

    private external fun readNativeInt(ptr: Long): Int
    private external fun writeNativeInt(ptr: Long, value: Int)
    private external fun writeNativeByte(ptr: Long, value: Byte)
    private external fun readNativeLong(ptr: Long): Long
    private external fun readNativeFloat(ptr: Long): Float
    private external fun readNativeDouble(ptr: Long): Double
    private external fun readNativeCString(ptr: Long): String?
    private external fun installNativeString(handle: Long, ptr: Long, value: String)
    private external fun clearNativePtr(ptr: Long)
    private external fun copyNativeBytes(ptr: Long, dest: ByteArray, size: Int)
    private external fun copyNativeShorts(ptr: Long, dest: ShortArray, size: Int)
}
