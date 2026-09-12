package com.omilator.core.libretro.jvm

import com.omilator.core.libretro.api.CoreOption
import com.omilator.core.libretro.api.CoreOptionValue
import com.omilator.core.libretro.api.PixelFormat
import com.omilator.core.libretro.jvm.LibretroLayouts.cString
import com.omilator.core.libretro.jvm.LibretroLayouts.gameGeometry
import com.omilator.core.libretro.jvm.LibretroLayouts.systemAvInfo
import com.omilator.core.libretro.jvm.LibretroLayouts.systemInfo
import com.omilator.core.libretro.jvm.LibretroLayouts.systemTiming
import com.omilator.core.libretro.jvm.gl.HwRenderBridge
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.invoke.VarHandle

internal class LibretroFfm(
    private val arena: Arena,
    private val systemDirectory: String,
) {
    private val linker = Linker.nativeLinker()

    var onVideo: ((MemorySegment, Int, Int, Long) -> Unit)? = null
    var onAudioBatch: ((MemorySegment, Long) -> Long)? = null
    var onInputState: ((Int, Int, Int, Int) -> Short)? = null

    var pixelFormat: Int = PixelFormatC.ORGB1555
        private set

    /** Options declared by the core via SET_CORE_OPTIONS. */
    val coreOptions: MutableList<CoreOption> = mutableListOf()

    /** User-selected values for each option (key -> chosen value). */
    var optionSelections: MutableMap<String, String> = mutableMapOf()

    /** Button descriptions from SET_INPUT_DESCRIPTORS (human-readable names). */
    val inputDescriptors: MutableMap<String, String> = mutableMapOf()

    private var apiVersion: MethodHandle? = null
    private var getSystemInfo: MethodHandle? = null
    private var getSystemAvInfo: MethodHandle? = null
    private var setEnvironment: MethodHandle? = null
    private var setVideoRefresh: MethodHandle? = null
    private var setAudioSample: MethodHandle? = null
    private var setAudioSampleBatch: MethodHandle? = null
    private var setInputPoll: MethodHandle? = null
    private var setInputState: MethodHandle? = null
    private var initHandle: MethodHandle? = null
    private var deinitHandle: MethodHandle? = null
    private var loadGameHandle: MethodHandle? = null
    private var unloadGameHandle: MethodHandle? = null
    private var runHandle: MethodHandle? = null
    private var resetHandle: MethodHandle? = null
    private var serializeSizeHandle: MethodHandle? = null
    private var serializeHandle: MethodHandle? = null
    private var unserializeHandle: MethodHandle? = null
    private var cheatResetHandle: MethodHandle? = null
    private var cheatSetHandle: MethodHandle? = null

    private val systemDirSeg = arena.allocateUtf8String(systemDirectory)
    val hwRender: HwRenderBridge = HwRenderBridge(arena)

    /** FFM upcall fallbacks (used only if libomilator_log.dylib is missing). */
    private val logFallbackStub: MemorySegment by lazy {
        val stub = upcallBound(
            "onLog",
            MethodType.methodType(Void::class.javaPrimitiveType, Int::class.javaPrimitiveType, MemorySegment::class.java),
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )
        stub
    }
    private val rumbleFallbackStub: MemorySegment by lazy {
        upcallBound(
            "onRumble",
            MethodType.methodType(Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Short::class.javaPrimitiveType),
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_SHORT),
        )
    }

    fun loadCore(path: String) {
        val sym = SymbolLookup.libraryLookup(path, arena)

        apiVersion = sym.down("retro_api_version", FunctionDescriptor.of(ValueLayout.JAVA_INT))
        getSystemInfo = sym.down(
            "retro_get_system_info",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS.withTargetLayout(systemInfo)),
        )
        getSystemAvInfo = sym.down(
            "retro_get_system_av_info",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS.withTargetLayout(systemAvInfo)),
        )
        setEnvironment = sym.down("retro_set_environment", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
        setVideoRefresh = sym.down("retro_set_video_refresh", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
        setAudioSample = sym.down("retro_set_audio_sample", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
        setAudioSampleBatch = sym.down("retro_set_audio_sample_batch", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
        setInputPoll = sym.down("retro_set_input_poll", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
        setInputState = sym.down("retro_set_input_state", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
        initHandle = sym.down("retro_init", FunctionDescriptor.ofVoid())
        deinitHandle = sym.down("retro_deinit", FunctionDescriptor.ofVoid())
        loadGameHandle = sym.down(
            "retro_load_game",
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS.withTargetLayout(LibretroLayouts.gameInfo)),
        )
        unloadGameHandle = sym.down("retro_unload_game", FunctionDescriptor.ofVoid())
        runHandle = sym.down("retro_run", FunctionDescriptor.ofVoid())
        resetHandle = sym.down("retro_reset", FunctionDescriptor.ofVoid())
        serializeSizeHandle = sym.down("retro_serialize_size", FunctionDescriptor.of(ValueLayout.JAVA_LONG))
        serializeHandle = sym.down(
            "retro_serialize",
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
        )
        unserializeHandle = sym.down(
            "retro_unserialize",
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
        )
        cheatResetHandle = sym.down("retro_cheat_reset", FunctionDescriptor.ofVoid())
        cheatSetHandle = sym.down(
            "retro_cheat_set",
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS),
        )
    }

    fun installEnvironmentCallback() {
        val env = upcallBound(
            "onEnvironment",
            MethodType.methodType(Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, MemorySegment::class.java),
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )
        setEnvironment!!.invoke(env)
    }

    fun installMediaCallbacks() {
        val video = upcallBound(
            "onVideo",
            MethodType.methodType(Void::class.javaPrimitiveType, MemorySegment::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
        )
        setVideoRefresh!!.invoke(video)

        val audioBatch = upcallBound(
            "onAudioBatch",
            MethodType.methodType(Long::class.javaPrimitiveType, MemorySegment::class.java, Long::class.javaPrimitiveType),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
        )
        setAudioSampleBatch!!.invoke(audioBatch)

        val audioSample = upcallBound(
            "onAudioSample",
            MethodType.methodType(Void::class.javaPrimitiveType, Short::class.javaPrimitiveType, Short::class.javaPrimitiveType),
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT),
        )
        setAudioSample!!.invoke(audioSample)

        val inputPoll = upcallBound(
            "onInputPoll",
            MethodType.methodType(Void::class.javaPrimitiveType),
            FunctionDescriptor.ofVoid(),
        )
        setInputPoll!!.invoke(inputPoll)

        val inputState = upcallBound(
            "onInputState",
            MethodType.methodType(Short::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        )
        setInputState!!.invoke(inputState)
    }

    @Deprecated("Use installEnvironmentCallback + installMediaCallbacks in the canonical order", ReplaceWith("installEnvironmentCallback()"))
    fun installCallbacks() {
        installEnvironmentCallback()
        installMediaCallbacks()
    }

    fun callInit() { initHandle!!.invoke() }
    fun callDeinit() { deinitHandle!!.invoke() }
    fun callRun() { runHandle!!.invoke() }
    fun callReset() { resetHandle!!.invoke() }
    fun callUnloadGame() { unloadGameHandle!!.invoke() }

    fun callSerializeSize(): Long = serializeSizeHandle!!.invoke() as Long

    fun callSerialize(): ByteArray {
        val size = callSerializeSize()
        require(size > 0L) { "Core returned zero serialize size" }
        val buf = arena.allocate(size)
        val ok = serializeHandle!!.invoke(buf, size) as Boolean
        check(ok) { "retro_serialize returned false" }
        val bytes = ByteArray(size.toInt())
        MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, bytes, 0, size.toInt())
        return bytes
    }

    fun callUnserialize(bytes: ByteArray): Boolean {
        val size = bytes.size.toLong()
        val buf = arena.allocate(size)
        MemorySegment.copy(bytes, 0, buf, ValueLayout.JAVA_BYTE, 0, bytes.size)
        return unserializeHandle!!.invoke(buf, size) as Boolean
    }

    fun callCheatReset() { cheatResetHandle?.invoke() }

    fun callCheatSet(index: Int, enabled: Boolean, code: String) {
        val codeSeg = arena.allocateUtf8String(code)
        cheatSetHandle?.invoke(index, enabled, codeSeg)
    }

    fun callApiVersion(): Int = apiVersion!!.invoke() as Int

    fun callSystemInfo(): Triple<String, String, String> {
        val seg = arena.allocate(systemInfo)
        getSystemInfo!!.invoke(seg)
        val name = readCString(seg, systemInfo.varHandle(path("library_name")))
        val version = readCString(seg, systemInfo.varHandle(path("library_version")))
        val ext = readCString(seg, systemInfo.varHandle(path("valid_extensions")))
        return Triple(name, version, ext)
    }

    fun callSystemAvInfo(): AvInfo {
        val seg = arena.allocate(systemAvInfo)
        getSystemAvInfo!!.invoke(seg)
        return AvInfo(
            baseWidth = systemAvInfo.varHandle(*ppath("geometry", "base_width")).get(seg) as Int,
            baseHeight = systemAvInfo.varHandle(*ppath("geometry", "base_height")).get(seg) as Int,
            maxWidth = systemAvInfo.varHandle(*ppath("geometry", "max_width")).get(seg) as Int,
            maxHeight = systemAvInfo.varHandle(*ppath("geometry", "max_height")).get(seg) as Int,
            aspectRatio = systemAvInfo.varHandle(*ppath("geometry", "aspect_ratio")).get(seg) as Float,
            fps = systemAvInfo.varHandle(*ppath("timing", "fps")).get(seg) as Double,
            sampleRate = systemAvInfo.varHandle(*ppath("timing", "sample_rate")).get(seg) as Double,
        )
    }

    fun callLoadGame(romPath: String): Boolean {
        val pathSeg = arena.allocateUtf8String(romPath)
        val info = arena.allocate(LibretroLayouts.gameInfo)
        info.set(ValueLayout.ADDRESS, 0, pathSeg)
        info.set(
            ValueLayout.JAVA_LONG,
            LibretroLayouts.gameInfo.byteOffset(path("size")),
            0L,
        )
        val result = loadGameHandle!!.invoke(info)
        return result as Boolean
    }

    @Suppress("unused")
    fun onEnvironment(cmd: Int, data: MemorySegment): Boolean {
        val handled = when (cmd) {
            RetroEnv.SET_PIXEL_FORMAT -> {
                pixelFormat = data.reinterpret(4L).get(ValueLayout.JAVA_INT, 0)
                true
            }
            RetroEnv.GET_SYSTEM_DIRECTORY,
            RetroEnv.GET_SAVE_DIRECTORY -> {
                data.reinterpret(8L).set(ValueLayout.ADDRESS, 0, systemDirSeg)
                true
            }
            RetroEnv.GET_LIBRETRO_PATH,
            RetroEnv.GET_INPUT_BITMASKS,
            RetroEnv.GET_AUDIO_VIDEO_ENABLE -> true
            RetroEnv.GET_LOG_INTERFACE -> {
                // retro_log_callback { retro_log_printf_t log; }
                // The function pointer goes at offset 0 of data — NOT a pointer
                // to a separate struct. PPSSPP dereferences data[0] as the fn ptr.
                val fnPtr = LogBridge.logCallbackAddress() ?: logFallbackStub
                data.reinterpret(8L).set(ValueLayout.ADDRESS, 0, fnPtr)
                true
            }
            RetroEnv.GET_RUMBLE_INTERFACE -> {
                // retro_rumble_interface { retro_set_rumble_state_t set_rumble_state; }
                val fnPtr = LogBridge.rumbleCallbackAddress() ?: rumbleFallbackStub
                data.reinterpret(8L).set(ValueLayout.ADDRESS, 0, fnPtr)
                true
            }
            RetroEnv.SET_CONTROLLER_INFO,
            RetroEnv.GET_TARGET_REFRESH_RATE,
            RetroEnv.GET_PREFERRED_HW_RENDER -> false
            RetroEnv.SET_HW_RENDER -> hwRender.handleRequest(data)

            // ---- Core options ----
            RetroEnv.SET_INPUT_DESCRIPTORS -> {
                parseInputDescriptors(data)
                true
            }
            RetroEnv.SET_DISK_CONTROL_INTERFACE -> true // accept but don't parse (niche)
            RetroEnv.SET_CORE_OPTIONS -> {
                parseCoreOptions(data)
                true
            }
            RetroEnv.SET_CORE_OPTIONS_INTL -> true // accept but don't parse (intl variant, niche)
            RetroEnv.SET_VARIABLES -> {
                // older API, data = retro_variable[] (key/desc pairs)
                true // accept but don't parse (legacy)
            }
            RetroEnv.GET_VARIABLE -> {
                handleGetVariable(data)
            }
            RetroEnv.GET_VARIABLE_UPDATE -> handleVariableUpdate(data)

            else -> false
        }
        if (!handled && cmd !in silentEnvCmds) {
            println("[Omilator] env cmd $cmd unhandled (declining)")
         }
         return handled
      }

    @Suppress("unused")
    fun onLog(level: Int, fmt: MemorySegment) {
        // Fallback path only — when libomilator_log.dylib is unavailable.
        // Reading fmt crashes on macOS arm64 (BUS_ADRALN) due to FFM
        // variadic upcall limitation. The C bridge (omilator_log_bridge.c)
        // handles this safely when present.
    }

    @Suppress("unused")
    fun onRumble(port: Int, effect: Int, strength: Short): Boolean {
        return true
    }

    private val silentEnvCmds = setOf(
        RetroEnv.GET_VARIABLE,
        RetroEnv.SET_VARIABLES,
        RetroEnv.SET_INPUT_DESCRIPTORS,
        RetroEnv.SET_CONTROLLER_INFO,
        RetroEnv.GET_VARIABLE_UPDATE,
        RetroEnv.GET_CORE_OPTIONS_VERSION,
        RetroEnv.SET_CORE_OPTIONS,
        RetroEnv.SET_CORE_OPTIONS_INTL,
        RetroEnv.SET_CORE_OPTIONS_DISPLAY,
        RetroEnv.SET_FRAME_TIME_CALLBACK,
        RetroEnv.SET_AUDIO_CALLBACK,
        RetroEnv.GET_PERF_INTERFACE,
        RetroEnv.SET_SYSTEM_AV_INFO,
        RetroEnv.SET_SUPPORT_NO_GAME,
    )

    @Suppress("unused")
    fun onVideo(data: MemorySegment, width: Int, height: Int, pitch: Long) {
        if (hwRender.isActive) {
            // HW render: data is NULL — we manually read from the FBO after retro_run
            // (see callRunHwFrame). Skip dispatch here.
            return
        }
        if (data.address() == 0L || width <= 0 || height <= 0) {
            onVideo?.invoke(data, width, height, pitch)
            return
        }
        val size = height.toLong() * pitch
        val sized = data.reinterpret(size)
        onVideo?.invoke(sized, width, height, pitch)
    }

    /**
     * Per-frame HW path: make FBO current, run core, read pixels, dispatch as a
     * software Framebuffer so the existing conversion pipeline handles it.
     */
    fun callRunHwFrame(width: Int, height: Int) {
        hwRender.ensureFramebufferSize(width, height)
        hwRender.makeCurrent()
        callRun()
        hwRender.unbind()
        val pixels = hwRender.readPixels() ?: return
        // GL_RGBA -> matches our XRGB8888 converter (RGBA byte order, ignore alpha)
        onVideo?.invoke(
            MemorySegment.ofArray(pixels),
            width, height,
            (width * 4).toLong(),
        )
    }

    @Suppress("unused")
    fun onAudioBatch(data: MemorySegment, frames: Long): Long {
        if (data.address() == 0L || frames <= 0L) return frames
        val bytes = frames * 2L * 2L // stereo 16-bit
        val sized = data.reinterpret(bytes)
        return onAudioBatch?.invoke(sized, frames) ?: frames
    }

    @Suppress("unused")
    fun onAudioSample(left: Short, right: Short) {
        // Phase 1: ignore single-sample audio (cores almost always use batch).
    }

    @Suppress("unused")
    fun onInputPoll() {
        // Phase 1: no-op. Input is queried on demand via onInputState.
    }

    @Suppress("unused")
    fun onInputState(port: Int, device: Int, index: Int, id: Int): Short {
        return onInputState?.invoke(port, device, index, id) ?: 0
    }

    private fun upcallBound(methodName: String, mt: MethodType, fd: FunctionDescriptor): MemorySegment {
        val mh = MethodHandles.lookup().findVirtual(LibretroFfm::class.java, methodName, mt).bindTo(this)
        return linker.upcallStub(mh, fd, arena)
    }

    private fun readCString(structSeg: MemorySegment, handle: VarHandle): String {
        val ptr = handle.get(structSeg) as MemorySegment
        if (ptr.address() == 0L) return ""
        return ptr.reinterpret(8192L).getUtf8String(0)
    }

    // ---- Core option parsing ----

    private fun parseCoreOptions(data: MemorySegment) {
        coreOptions.clear()
        if (data.address() == 0L) return

        // retro_core_option_definition (64-bit):
        //   char* key          @ 0
        //   char* desc         @ 8
        //   char* info         @ 16
        //   retro_core_option_value values[128] @ 24   (each {char* value; char* label;} = 16)
        //   char* default_value @ 24 + 128*16 = 2072
        // stride = 2080
        val valuesOffset = 24L
        val valueCount = 128
        val valSize = 16L
        val defaultOffset = valuesOffset + valueCount * valSize // 2072
        val defSize = defaultOffset + 8L // 2080

        var offset = 0L

        while (true) {
            val keySeg = data.get(ValueLayout.ADDRESS, offset)
            if (keySeg.address() == 0L) break // NULL key = end of array

            val key = keySeg.reinterpret(256L).getUtf8String(0)
            val descSeg = data.get(ValueLayout.ADDRESS, offset + 8)
            val desc = if (descSeg.address() != 0L) descSeg.reinterpret(256L).getUtf8String(0) else key
            val infoSeg = data.get(ValueLayout.ADDRESS, offset + 16)
            val info = if (infoSeg.address() != 0L) infoSeg.reinterpret(1024L).getUtf8String(0) else null

            val values = mutableListOf<CoreOptionValue>()
            for (i in 0 until valueCount) {
                val valOffset = offset + valuesOffset + i * valSize
                val valueSeg = data.get(ValueLayout.ADDRESS, valOffset)
                if (valueSeg.address() == 0L) break
                val value = valueSeg.reinterpret(256L).getUtf8String(0)
                val labelSeg = data.get(ValueLayout.ADDRESS, valOffset + 8)
                val label = if (labelSeg.address() != 0L) labelSeg.reinterpret(256L).getUtf8String(0) else value
                values.add(CoreOptionValue(value, label))
            }

            val defaultSeg = data.get(ValueLayout.ADDRESS, offset + defaultOffset)
            val defaultVal = if (defaultSeg.address() != 0L) {
                defaultSeg.reinterpret(256L).getUtf8String(0)
            } else {
                values.firstOrNull()?.value ?: ""
            }

            coreOptions.add(CoreOption(key, desc, info, defaultVal, values))

            if (key !in optionSelections) {
                optionSelections[key] = defaultVal
            }

            offset += defSize
        }

        println("[Omilator] Parsed ${coreOptions.size} core options")
    }

    private fun handleGetVariable(data: MemorySegment): Boolean {
        if (data.address() == 0L) return false
        // retro_variable: { const char* key; const char* value; }
        // Read the key
        val keySeg = data.get(ValueLayout.ADDRESS, 0)
        if (keySeg.address() == 0L) return false
        val key = keySeg.reinterpret(256L).getUtf8String(0)

        // Look up stored value
        val value = optionSelections[key] ?: return false

        // Write value pointer to offset 8
        val valueSeg = arena.allocateUtf8String(value)
        data.reinterpret(16L).set(ValueLayout.ADDRESS, 8, valueSeg)
        return true
    }

    fun setOptionValue(key: String, value: String) {
        if (optionSelections[key] != value) {
            optionSelections[key] = value
            variablesDirty = true
        }
    }

    /** Set when a UI option change lands; cleared once the core has observed it. */
    @Volatile
    private var variablesDirty = false

    private fun handleVariableUpdate(data: MemorySegment): Boolean {
        if (data.address() == 0L) return false
        data.reinterpret(1L).set(ValueLayout.JAVA_BYTE, 0, if (variablesDirty) 1.toByte() else 0.toByte())
        variablesDirty = false
        return true
    }

    private fun parseInputDescriptors(data: MemorySegment) {
        inputDescriptors.clear()
        if (data.address() == 0L) return
        // retro_input_descriptor { port(u32), device(u32), index(u32), id(u32), desc(char*) }
        // Layout: port(4) device(4) index(4) id(4) desc(8) = 24 bytes.
        // The array is terminated by a NULL description pointer — the 0xFFFFFFFF
        // port check used before walked past the terminator into unrelated memory.
        val structSize = 24L
        var offset = 0L
        while (true) {
            val port = data.get(ValueLayout.JAVA_INT, offset)
            val device = data.get(ValueLayout.JAVA_INT, offset + 4)
            val id = data.get(ValueLayout.JAVA_INT, offset + 12)
            val descSeg = data.get(ValueLayout.ADDRESS, offset + 16)
            if (descSeg.address() == 0L) break // terminator
            val desc = descSeg.reinterpret(128L).getUtf8String(0)
            if (port == 0 && device == 1 && desc.isNotBlank()) {
                inputDescriptors["btn_$id"] = desc
            }
            offset += structSize
        }
        if (inputDescriptors.isNotEmpty()) {
            println("[Omilator] Parsed ${inputDescriptors.size} input descriptors")
        }
    }

    private fun SymbolLookup.down(name: String, fd: FunctionDescriptor): MethodHandle {
        val addr = find(name).orElseThrow { UnsatisfiedLinkError("$name not found in core") }
        return linker.downcallHandle(addr, fd)
    }

    private fun path(name: String) = MemoryLayout.PathElement.groupElement(name)
    private fun ppath(vararg names: String): Array<MemoryLayout.PathElement> =
        names.map { MemoryLayout.PathElement.groupElement(it) }.toTypedArray()
}

internal data class AvInfo(
    val baseWidth: Int,
    val baseHeight: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val aspectRatio: Float,
    val fps: Double,
    val sampleRate: Double,
)
