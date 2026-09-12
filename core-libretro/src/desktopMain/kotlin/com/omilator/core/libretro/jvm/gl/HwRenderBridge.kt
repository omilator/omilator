package com.omilator.core.libretro.jvm.gl

import com.omilator.core.libretro.jvm.PixelFormatC
import com.omilator.core.libretro.jvm.gl.GlContext
import org.lwjgl.opengl.GL
import org.lwjgl.system.FunctionProvider
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Bridges libretro SET_HW_RENDER with our hidden GL context + FBO.
 *
 * Layout of retro_hw_render_callback on a 64-bit ABI (from libretro.h):
 *   0:  context_type (enum, 4 bytes + 4 padding)
 *   8:  context_reset (fn ptr, core provides)
 *  16:  get_current_framebuffer (fn ptr, frontend fills)
 *  24:  get_proc_address (fn ptr, frontend fills)
 *  32:  depth (bool, core provides)
 *  33:  stencil (bool, core provides)
 *  34:  bottom_left_origin (bool, core provides — read, not written)
 *  36:  major_version (uint)
 *  40:  minor_version (uint)
 *  44:  debug_context (bool)
 *  48:  context_destroy (fn ptr, core provides)
 * Total size 56, but 64 is read so the whole struct is addressable.
 */
internal class HwRenderBridge(private val arena: Arena) {

    private var gl: GlContext? = null
    private var contextResetHandle: MethodHandle? = null
    private var contextDestroyHandle: MethodHandle? = null
    /** Core-declared: true means the core renders with a bottom-left origin. */
    var bottomLeftOrigin: Boolean = false
        private set
    private val functionProvider: FunctionProvider = GL.getFunctionProvider()
        ?: error("LWJGL function provider unavailable — GL.createCapabilities() not called")

    /**
     * Returns true if the requested context type is one we support (OPENGL core).
     * Sets up the GL context + writes the frontend-provided callback pointers
     * into the struct, then calls the core's context_reset.
     */
    fun handleRequest(data: MemorySegment): Boolean {
        if (data.address() == 0L) return false
        val sized = data.reinterpret(64L)
        val ctxType = sized.get(ValueLayout.JAVA_INT, 0)
        val major = sized.get(ValueLayout.JAVA_INT, 36)
        val minor = sized.get(ValueLayout.JAVA_INT, 40)
        // Honest acceptance: this frontend creates exactly one desktop
        // OpenGL 3.2 CORE context. GLES needs EGL (not desktop GL), legacy
        // OPENGL lacks the 3.0 framebuffer ops the readback uses, and macOS
        // cannot provide GL 4.x - accepting those anyway handed cores a
        // context that did not match what they asked for.
        val supported = ctxType == HW_CONTEXT_OPENGL_CORE && major <= 3
        if (!supported) {
            println("[Omilator] SET_HW_RENDER: cannot provide context_type=$ctxType v$major.$minor — declining")
            return false
        }
        bottomLeftOrigin = sized.get(ValueLayout.JAVA_BYTE, 34) != 0.toByte()
        println("[Omilator] SET_HW_RENDER: OpenGL ctx_type=$ctxType v$major.$minor — providing")

        // Step 1: create GL context
        println("[HwRender] step 1: creating GlContext")
        if (gl == null) {
            try {
                gl = GlContext.create()
                println("[HwRender] GlContext created OK")
            } catch (t: Throwable) {
                println("[HwRender] GlContext.create FAILED: ${t::class.simpleName}: ${t.message}")
                return false
            }
        }

        // Step 2: read core's function pointers
        println("[HwRender] step 2: reading core fn ptrs")
        val resetSeg = sized.get(ValueLayout.ADDRESS, 8)
        val destroySeg = sized.get(ValueLayout.ADDRESS, 48)
        val linker = Linker.nativeLinker()
        if (resetSeg.address() != 0L) {
            contextResetHandle = linker.downcallHandle(resetSeg, FunctionDescriptor.ofVoid())
            println("[HwRender] context_reset at ${resetSeg.address()}")
        }
        if (destroySeg.address() != 0L) {
            contextDestroyHandle = linker.downcallHandle(destroySeg, FunctionDescriptor.ofVoid())
            println("[HwRender] context_destroy at ${destroySeg.address()}")
        }

        // Step 3: provide frontend callbacks
        println("[HwRender] step 3: providing get_current_framebuffer + get_proc_address")
        val getFbStub = upcallGetFramebuffer()
        val getProcStub = upcallGetProcAddress()
        sized.set(ValueLayout.ADDRESS, 16, getFbStub)
        sized.set(ValueLayout.ADDRESS, 24, getProcStub)

        // Step 4: call core's context_reset — it sets up its own GL resources
        println("[HwRender] step 4: calling context_reset (core will set up GL)")
        try {
            contextResetHandle?.invoke()
            println("[HwRender] context_reset returned OK")
        } catch (t: Throwable) {
            println("[HwRender] context_reset threw: ${t::class.simpleName}: ${t.message}")
            return false
        }
        return true
    }

    fun ensureFramebufferSize(width: Int, height: Int) {
        gl?.resize(width, height)
    }

    fun makeCurrent() { gl?.makeCurrent() }
    fun unbind() { gl?.unbind() }

    fun readPixels(): ByteArray? {
        val w = framebufferWidth()
        val h = framebufferHeight()
        return readPixels(w, h)
    }

    fun readPixels(w: Int, h: Int): ByteArray? {
        if (w <= 0 || h <= 0) return null
        val raw = gl?.readPixelsRGBA(w, h) ?: return null
        if (bottomLeftOrigin) return raw
        val stride = w * 4
        val flipped = ByteArray(raw.size)
        for (y in 0 until h) {
            val src = y * stride
            val dst = (h - 1 - y) * stride
            raw.copyInto(flipped, dst, src, src + stride)
        }
        return flipped
    }
    fun framebufferWidth(): Int = gl?.width() ?: 0
    fun framebufferHeight(): Int = gl?.height() ?: 0
    val isActive: Boolean get() = gl != null

    fun destroy() {
        try { contextDestroyHandle?.invoke() } catch (_: Throwable) {}
        gl?.destroy()
        gl = null
    }

    // ---- Upcall stubs we hand to the core ----

    private fun upcallGetFramebuffer(): MemorySegment {
        val mh = MethodHandles.lookup().findVirtual(
            HwRenderBridge::class.java, "onGetCurrentFramebuffer",
            MethodType.methodType(Long::class.javaPrimitiveType),
        ).bindTo(this)
        val fd = FunctionDescriptor.of(ValueLayout.JAVA_LONG)
        return Linker.nativeLinker().upcallStub(mh, fd, arena)
    }

    private fun upcallGetProcAddress(): MemorySegment {
        val mh = MethodHandles.lookup().findVirtual(
            HwRenderBridge::class.java, "onGetProcAddress",
            MethodType.methodType(Long::class.javaPrimitiveType, MemorySegment::class.java),
        ).bindTo(this)
        val fd = FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
        return Linker.nativeLinker().upcallStub(mh, fd, arena)
    }

    @Suppress("unused")
    private fun onGetCurrentFramebuffer(): Long {
        return (gl?.framebufferId() ?: 0).toLong()
    }

    @Suppress("unused")
    private fun onGetProcAddress(sym: MemorySegment): Long {
        if (sym.address() == 0L) return 0L
        val name = sym.reinterpret(256L).getUtf8String(0)
        val addr = functionProvider.getFunctionAddress(name)
        if (addr == 0L) {
            // Suppress noisy log for known-unimportant lookups
            if (!name.startsWith("glDebugMessage") && name !in silentGlLookups) {
                println("[Omilator] get_proc_address: unresolved '$name'")
            }
        }
        return addr
    }

    private val silentGlLookups = setOf(
        "glFrameTerminatorGREMEDY",
        "glSpecializeShaderARB",
        "glClientWaitSync",
    )

    companion object {
        private const val HW_CONTEXT_NONE = 0
        private const val HW_CONTEXT_OPENGL = 1
        private const val HW_CONTEXT_OPENGLES2 = 2
        private const val HW_CONTEXT_OPENGL_CORE = 3
        private const val HW_CONTEXT_OPENGLES3 = 4
        private const val HW_CONTEXT_OPENGLES_VERSION = 5
        private const val HW_CONTEXT_VULKAN = 6
        private const val HW_CONTEXT_D3D10 = 7
        private const val HW_CONTEXT_D3D11 = 8
        private const val HW_CONTEXT_D3D12 = 9
        private const val HW_CONTEXT_DUMMY = 13
    }
}
