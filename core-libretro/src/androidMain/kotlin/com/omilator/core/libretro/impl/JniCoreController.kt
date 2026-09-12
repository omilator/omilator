package com.omilator.core.libretro.impl

import com.omilator.core.libretro.api.AudioSink
import com.omilator.core.libretro.api.AvInfo
import com.omilator.core.libretro.api.CoreController
import com.omilator.core.libretro.api.CoreNotLoadedException
import com.omilator.core.libretro.api.Framebuffer
import com.omilator.core.libretro.api.Geometry
import com.omilator.core.libretro.api.InputSource
import com.omilator.core.libretro.api.PixelFormat
import com.omilator.core.libretro.api.SystemInfo
import com.omilator.core.libretro.api.Timing
import com.omilator.core.libretro.api.VideoSink

internal class JniCoreController(private val systemDirectory: String) : CoreController {

    private var loaded: Boolean = false
    private var corePath: String = ""

    private companion object {
        const val EXPERIMENTAL = 0x10000
        const val GET_SYSTEM_DIRECTORY = 9
        const val SET_PIXEL_FORMAT = 10
        const val GET_LIBRETRO_PATH = 19
        const val GET_SAVE_DIRECTORY = 31
        const val GET_INPUT_BITMASKS = 51 or EXPERIMENTAL
    }
    private var videoSink: VideoSink? = null
    private var audioSink: AudioSink? = null
    private var inputSource: InputSource? = null
    private var pixelFormat: Int = 0
    private var frameCount: Int = 0

    override val isLoaded: Boolean get() = loaded
    override val memorySize: UInt = 0u

    override suspend fun loadCore(path: String): SystemInfo {
        System.loadLibrary("omilator_jni")
        corePath = path
        val ok = loadCoreNative(path)
        if (!ok) throw RuntimeException("Failed to load core: $path")
        loaded = true
        val name = systemInfoNameNative()
        return SystemInfo(
            libraryName = name,
            libraryVersion = "unknown",
            validExtensions = emptyList(),
            needFullpath = false,
            blockExtract = false,
        )
    }

    override suspend fun loadGame(romPath: String): AvInfo {
        check(loaded) { throw CoreNotLoadedException("Core not loaded") }
        if (!loadGameNative(romPath)) {
            throw RuntimeException("retro_load_game failed for $romPath")
        }
        // Real values from retro_get_system_av_info — the sample rate feeds
        // AudioTrack directly, so a hardcoded GBA figure pitched every other
        // system wrong.
        val av = systemAvInfoNative()
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
        runFrameNative()
    }

    override fun reset() = resetNative()
    override fun unloadGame() { unloadGameNative() }
    override fun unloadCore() {
        deinitNative()
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
        val size = serializeSizeNative()
        if (size <= 0L) return false
        val bytes = ByteArray(size.toInt())
        if (!saveStateNative(bytes, size)) return false
        java.io.File(path).writeBytes(bytes)
        return true
    }

    override fun loadState(path: String): Boolean {
        val file = java.io.File(path)
        if (!file.exists()) return false
        val bytes = file.readBytes()
        return loadStateNative(bytes, bytes.size.toLong())
    }

    override fun readMemory(region: UInt, offset: UInt, size: UInt): ByteArray = ByteArray(size.toInt())
    override fun cheatReset() {}
    override fun saveStateToMemory(): ByteArray = ByteArray(0)
    override fun loadStateFromMemory(bytes: ByteArray): Boolean = false

    override fun cheatSet(index: Int, enabled: Boolean, code: String) {}

    override fun writeMemory(region: UInt, offset: UInt, data: ByteArray) {}

    // Trampolines invoked by libretro_jni.cpp. The C++ side looks up this
    // controller instance via a global ref and calls these methods.
    @Suppress("unused")
    fun onEnvironment(cmd: Int, dataPtr: Long): Boolean {
        // Success only after the command's required output is satisfied:
        // raw 0/9/19/31 true-without-writes handed cores garbage pointers,
        // and 51 must carry the EXPERIMENTAL bit (mask polling is NOT
        // implemented, so it is honestly refused).
        return when (cmd) {
            SET_PIXEL_FORMAT -> {
                if (dataPtr != 0L) {
                    pixelFormat = readNativeInt(dataPtr)
                    true
                } else false
            }
            GET_SYSTEM_DIRECTORY, GET_SAVE_DIRECTORY -> {
                if (dataPtr != 0L) {
                    writeNativeString(dataPtr, systemDirectory)
                    true
                } else false
            }
            GET_LIBRETRO_PATH -> {
                if (dataPtr != 0L) {
                    writeNativeString(dataPtr, corePath)
                    true
                } else false
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

    // JNI declarations
    private external fun loadCoreNative(path: String): Boolean
    private external fun loadGameNative(path: String): Boolean
    private external fun systemAvInfoNative(): DoubleArray
    private external fun writeNativeString(ptr: Long, s: String)
    private external fun runFrameNative()
    private external fun resetNative()
    private external fun unloadGameNative()
    private external fun deinitNative()
    private external fun apiVersionNative(): Int
    private external fun systemInfoNameNative(): String
    private external fun serializeSizeNative(): Long
    private external fun saveStateNative(bytes: ByteArray, size: Long): Boolean
    private external fun loadStateNative(bytes: ByteArray, size: Long): Boolean

    private external fun readNativeInt(ptr: Long): Int
    private external fun copyNativeBytes(ptr: Long, dest: ByteArray, size: Int)
    private external fun copyNativeShorts(ptr: Long, dest: ShortArray, size: Int)
}
