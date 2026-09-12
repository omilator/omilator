@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.omilator.core.libretro.impl

import com.omilator.core.libretro.api.*
import kotlinx.cinterop.*
import kotlin.native.concurrent.ThreadLocal
import platform.Foundation.NSLog
import platform.posix.RTLD_NOW as _RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym
import platform.posix.dlclose
import platform.posix.dlerror as _dlerror
import platform.posix.fopen
import platform.posix.fclose
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fread

@ThreadLocal
internal var nativeControllerInstance: NativeCoreController? = null

@ThreadLocal
internal var nativeHwRender: VulkanHwRender? = null

// HW render callback function pointers. Kept as top-level val's so they
// have stable addresses that survive the lifetime of the process —
// retro_hw_render_callback holds raw C function pointers, not GC-able refs.
//
// Workaround (same as inputPollCb): 0-arg staticCFunction has overload
// ambiguity in K/N. We declare with a dummy Int param — arm64 ABI ignores
// extra callee params so the libretro-defined 0-arg signature still works.
internal val hwGetFramebufferCb = staticCFunction { _: Int ->
    nativeHwRender?.currentFramebuffer() ?: 0uL
}

internal val hwGetProcAddressCb = staticCFunction { sym: CPointer<ByteVar>? ->
    val name = sym?.toKString()
    if (name != null) nativeHwRender?.getProcAddress(name) else null
}

internal val envCb = staticCFunction { cmd: Int, data: CPointer<ByteVar>? ->
    val ctrl = nativeControllerInstance
    val result: Boolean = if (ctrl != null) {
        // Log only HW render requests — everything else is per-frame spam.
        if (cmd == 14 || cmd == 74) NSLog("[envCb] cmd=%d", cmd)
        when (cmd) {
            10 -> { // SET_PIXEL_FORMAT
                if (data != null) {
                    ctrl.pixelFormat = data.reinterpret<IntVar>()[0]
                }
                true
            }
            14 -> { // SET_HW_RENDER — struct retro_hw_render_callback*
                ctrl.handleSetHwRender(data)
            }
            0, 9, 19, 31, 51, 69 -> true
            else -> false
        }
    } else false
    result
}

internal val videoCb = staticCFunction { data: CPointer<ByteVar>?, width: Int, height: Int, pitch: Int ->
    val ctrl = nativeControllerInstance
    if (ctrl != null && data != null && width > 0 && height > 0 && pitch > 0) {
        val size = height * pitch
        val bytes = data.readBytes(size)
        val format = when (ctrl.pixelFormat) {
            1 -> PixelFormat.XRGB8888
            2 -> PixelFormat.RGB565
            else -> PixelFormat.ORGB1555
        }
        ctrl.videoSink?.onFrame(Framebuffer(bytes, width.toUInt(), height.toUInt(), pitch.toUInt(), format))
    }
}

internal val audioBatchCb = staticCFunction { data: CPointer<ByteVar>?, frames: Int ->
    val ctrl = nativeControllerInstance
    if (ctrl != null && data != null && frames > 0) {
        // Interpret data as int16_t* (stereo samples)
        val shortPtr = data.reinterpret<ShortVar>()
        val count = frames * 2
        val samples = ShortArray(count) { i -> shortPtr[i] }
        ctrl.audioSink?.onSamples(samples)
    }
    frames
}

internal val audioSampleCb = staticCFunction { left: Int, right: Int ->
    nativeControllerInstance?.audioSink?.onSamples(shortArrayOf(left.toShort(), right.toShort()))
    Unit
}

// Workaround: staticCFunction with 0 args causes overload ambiguity.
// Declare with a dummy Int param — arm64 ABI ignores extra callee params.
internal val inputPollCb = staticCFunction { _: Int -> }

internal val inputStateCb = staticCFunction { port: Int, device: Int, index: Int, id: Int ->
    if (port == 0 && device == 1) {
        val ctrl = nativeControllerInstance
        val src = ctrl?.inputSource
        if (src != null) {
            src.poll(port, InputDevice.JOYPAD, index, id).toShort()
        } else {
            0.toShort()
        }
    } else {
        0.toShort()
    }
}

internal class NativeCoreController : CoreController {
    private var handle: CPointer<*>? = null
    private var loaded = false
    internal var pixelFormat = 0
    internal var videoSink: VideoSink? = null
    internal var audioSink: AudioSink? = null
    internal var inputSource: InputSource? = null

    override val isLoaded get() = loaded
    override val memorySize: UInt = 0u

    override suspend fun loadCore(path: String): SystemInfo {
        nativeControllerInstance = this
        // Try the caller-supplied path first (runtime-downloaded cores in
        // Documents/cores/). If that fails, look for a bundled copy inside
        // the app's Frameworks/ directory (cores shipped via setup-cores.sh).
        val h = dlopen(path, _RTLD_NOW)
            ?: dlopenBundledCore(path) ?: run {
                val err = _dlerror()?.toKString() ?: "unknown"
                throw RuntimeException("dlopen failed: $path — $err")
            }
        handle = h

        // Set callbacks — cast function pointers to opaque
        val envPtr: COpaquePointer? = envCb
        val videoPtr: COpaquePointer? = videoCb
        val audioBatchPtr: COpaquePointer? = audioBatchCb
        val audioSamplePtr: COpaquePointer? = audioSampleCb
        // inputPoll is optional — core polls on-demand via inputState
        val inputStatePtr: COpaquePointer? = inputStateCb

        // Order matters: environment BEFORE init, others AFTER init
        dlsym(h, "retro_set_environment")?.reinterpret<CFunction<(COpaquePointer?) -> Unit>>()?.invoke(envPtr)

        // retro_init
        dlsym(h, "retro_init")?.reinterpret<CFunction<() -> Unit>>()?.invoke()

        // Set remaining callbacks AFTER init
        val inputPollPtr: COpaquePointer? = inputPollCb
        dlsym(h, "retro_set_video_refresh")?.reinterpret<CFunction<(COpaquePointer?) -> Unit>>()?.invoke(videoPtr)
        dlsym(h, "retro_set_audio_sample_batch")?.reinterpret<CFunction<(COpaquePointer?) -> Unit>>()?.invoke(audioBatchPtr)
        dlsym(h, "retro_set_audio_sample")?.reinterpret<CFunction<(COpaquePointer?) -> Unit>>()?.invoke(audioSamplePtr)
        dlsym(h, "retro_set_input_poll")?.reinterpret<CFunction<(COpaquePointer?) -> Unit>>()?.invoke(inputPollPtr)
        dlsym(h, "retro_set_input_state")?.reinterpret<CFunction<(COpaquePointer?) -> Unit>>()?.invoke(inputStatePtr)

        // retro_get_system_info
        var name = "unknown"
        var version = "0.0"
        var ext = ""
        memScoped {
            val buf = allocArray<ByteVar>(48)
            dlsym(h, "retro_get_system_info")
                ?.reinterpret<CFunction<(CPointer<ByteVar>?) -> Unit>>()
                ?.invoke(buf)
            val ptrs = buf.reinterpret<CPointerVar<ByteVar>>()
            name = ptrs[0]?.toKString() ?: "unknown"
            version = ptrs[1]?.toKString() ?: "0.0"
            ext = ptrs[2]?.toKString() ?: ""
        }

        loaded = true
        return SystemInfo(name, version, ext.split("|").filter { it.isNotBlank() }, false, false)
    }

    // Persist game info struct — must survive beyond memScoped block
    private var gameInfoNative: NativePlacement? = null

    override suspend fun loadGame(romPath: String): AvInfo {
        check(loaded) { throw CoreNotLoadedException("Core not loaded") }

        // Allocate retro_game_info on nativeHeap, ZERO-INITIALIZED.
        // struct retro_game_info { char* path; void* data; size_t size; char* meta; } = 32 bytes
        val info = nativeHeap.allocArray<ByteVar>(32)
        val zeroBytes = ByteArray(32)
        zeroBytes.copyInto(info.readBytes(32))

        // mGBA can't fopen() inside the iOS Simulator sandbox — load ROM
        // into memory and pass data+size instead of relying on path.
        val romBytes = readRomToBytes(romPath)
        val dataSize = romBytes.size

        if (romBytes.isNotEmpty()) {
            // Copy ROM data to nativeHeap — write each byte, not readBytes!
            val dataBuf = nativeHeap.allocArray<ByteVar>(dataSize)
            for (i in 0 until dataSize) {
                dataBuf[i] = romBytes[i]
            }

            // Set path (for metadata/display)
            val pathBytes = romPath.encodeToByteArray() + 0.toByte()
            val pathBuf = nativeHeap.allocArray<ByteVar>(pathBytes.size)
            pathBytes.copyInto(pathBuf.readBytes(pathBytes.size))

            // retro_game_info layout (arm64):
            // offset 0: path (char*), offset 8: data (void*),
            // offset 16: size (Long), offset 24: meta (char*)
            info.reinterpret<CPointerVar<ByteVar>>()[0] = pathBuf
            info.reinterpret<CPointerVar<ByteVar>>()[1] = dataBuf
            info.reinterpret<LongVar>()[2] = dataSize.toLong()
        }

        val ok = dlsym(handle, "retro_load_game")
            ?.reinterpret<CFunction<(CPointer<ByteVar>?) -> Boolean>>()
            ?.invoke(info) ?: false
        require(ok) { "retro_load_game returned false" }

        return AvInfo(
            Geometry(240u, 160u, 240u, 160u, 1.5f),
            Timing(60f, 32768.0),
        )
    }

    override fun runFrame() {
        // CRITICAL: set thread-local on THIS thread (runFrame runs on a
        // different coroutine thread than loadCore). Without this, callbacks
        // find nativeControllerInstance = null → EXC_BAD_ACCESS.
        nativeControllerInstance = this
        try {
            dlsym(handle, "retro_run")?.reinterpret<CFunction<() -> Unit>>()?.invoke()
        } catch (e: Throwable) {
            println("[NativeCoreController] retro_run threw: ${e.message}")
        }
    }

    override fun reset() { dlsym(handle, "retro_reset")?.reinterpret<CFunction<() -> Unit>>()?.invoke() }
    override fun unloadGame() {
        dlsym(handle, "retro_unload_game")?.reinterpret<CFunction<() -> Unit>>()?.invoke()
        gameInfoNative = null
    }
    override fun unloadCore() {
        dlsym(handle, "retro_deinit")?.reinterpret<CFunction<() -> Unit>>()?.invoke()
        handle?.let { dlclose(it) }
        handle = null; loaded = false; nativeControllerInstance = null
    }

    override fun attach(video: VideoSink, audio: AudioSink, input: InputSource) {
        videoSink = video; audioSink = audio; inputSource = input
    }
    override fun detach() { videoSink = null; audioSink = null; inputSource = null }
    override fun saveState(path: String) = false
    override fun loadState(path: String) = false
    override fun readMemory(region: UInt, offset: UInt, size: UInt) = ByteArray(size.toInt())
    override fun writeMemory(region: UInt, offset: UInt, data: ByteArray) {}
    override fun saveStateToMemory() = ByteArray(0)
    override fun loadStateFromMemory(bytes: ByteArray) = false
    override fun cheatReset() {}
    override fun cheatSet(index: Int, enabled: Boolean, code: String) {}

    internal fun handleEnv(cmd: Int, data: COpaquePointer?): Boolean {
        return when (cmd) {
            10 -> { // SET_PIXEL_FORMAT
                data?.reinterpret<IntVar>()?.let { pixelFormat = it.pointed.value }
                true
            }
            14 -> handleSetHwRender(data?.reinterpret<ByteVar>())
            0, 9, 19, 31, 51, 69 -> true
            else -> false
        }
    }

    /**
     * Handle RETRO_ENVIRONMENT_SET_HW_RENDER (cmd 14). The core passes a
     * retro_hw_render_callback struct describing which HW context it needs.
     *
     * struct retro_hw_render_callback layout (arm64, 64 bytes):
     *   offset 0:  context_type (enum, 4 bytes)
     *   offset 4:  <padding>
     *   offset 8:  context_reset (function pointer)
     *   offset 16: get_current_framebuffer (function pointer — frontend fills)
     *   offset 24: get_proc_address (function pointer — frontend fills)
     *   offset 32: depth, stencil, bottom_left_origin (3x bool)
     *   offset 36: version_major (unsigned)
     *   offset 40: version_minor (unsigned)
     *   offset 44: cache_context (bool)
     *   offset 48: context_destroy (function pointer)
     *   offset 56: debug_context (bool)
     *
     * We only support RETRO_HW_CONTEXT_VULKAN (=6) on iOS (via MoltenVK).
     * For other context types we return false so the core can fall back to
     * software rendering.
     *
     * After filling the frontend-side pointers, the core's context_reset
     * is invoked by the libretro core itself (NOT us) when it observes the
     * SET_HW_RENDER return true. Some cores expect context_reset to be
     * called immediately; we delegate to the core's own threading model.
     */
    internal fun handleSetHwRender(data: CPointer<ByteVar>?): Boolean {
        if (data == null) return false
        val typeInt = data.reinterpret<IntVar>()[0]
        if (typeInt != 6 /* RETRO_HW_CONTEXT_VULKAN */) {
            println("[NativeCoreController] SET_HW_RENDER: unsupported context_type=$typeInt (only Vulkan=6 supported on iOS)")
            return false
        }

        // Lazily prepare Vulkan (MTLDevice + dlopen MoltenVK).
        val hw = VulkanHwRender()
        if (!hw.prepare()) {
            println("[NativeCoreController] SET_HW_RENDER: Vulkan prepare failed (MoltenVK not loadable)")
            return false
        }
        nativeHwRender = hw

        // Fill frontend-provided function pointers in the struct.
        // Offset 16 = get_current_framebuffer, offset 24 = get_proc_address.
        val ptrs = data.reinterpret<CPointerVar<*>>()
        ptrs[2] = hwGetFramebufferCb
        ptrs[3] = hwGetProcAddressCb

        println("[NativeCoreController] SET_HW_RENDER: Vulkan context granted, framebuffer + proc_address callbacks installed")
        // Note: the core calls context_reset itself once we return true.
        // MoltenVK's vkCreateMetalSurfaceEXT will fail until we plumb the
        // CAMetalLayer through — see VulkanHwRender TODO.
        return true
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun readRomToBytes(path: String): ByteArray = memScoped {
    // Use POSIX fopen/fread — avoids Foundation API quirks
    val fp = fopen(path, "rb")
        ?: throw RuntimeException("Cannot open ROM: $path")
    try {
        fseek(fp, 0, 2) // SEEK_END
        val size = ftell(fp).toInt()
        fseek(fp, 0, 0) // SEEK_SET
        if (size <= 0) throw RuntimeException("ROM empty: $path")
        val buf = allocArray<ByteVar>(size)
        fread(buf, 1.toULong(), size.toULong(), fp)
        buf.readBytes(size)
    } finally {
        fclose(fp)
    }
}

/**
 * Resolve a core to its bundled copy in the app's Frameworks/ directory,
 * if one ships with the app (see setup-cores.sh). Returns null if no
 * bundled match exists — caller falls back to runtime-downloaded cores.
 *
 * `path` is the Documents/cores path the caller tried first. We extract
 * the dylib basename (e.g. "mgba_libretro.dylib") and look for the same
 * name under NSBundle.mainBundle.bundlePath/Frameworks/.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun dlopenBundledCore(path: String): CPointer<*>? {
    val basename = path.substringAfterLast('/')
    if (basename.isBlank()) return null
    val bundlePath = platform.Foundation.NSBundle.mainBundle.bundlePath
    val bundled = "$bundlePath/Frameworks/$basename"
    return if (platform.Foundation.NSFileManager.defaultManager.fileExistsAtPath(bundled)) {
        println("[NativeCoreController] using bundled core: $bundled")
        dlopen(bundled, _RTLD_NOW)
    } else {
        null
    }
}
