package com.omilator.core.libretro.jvm.gl

import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil

/**
 * Owns a hidden GLFW window + OpenGL core context, plus a render-target FBO
 * the libretro core renders into via SET_HW_RENDER.
 *
 * Lifecycle: init() -> resize(w,h) per AV info -> makeCurrent() before
 * core runs -> readPixels() after retro_run() -> done.
 */
internal class GlContext private constructor(
    private val window: Long,
) {
    private var fbo: Int = 0
    private var colorTex: Int = 0
    private var width: Int = 0
    private var height: Int = 0

    init {
        GLFW.glfwMakeContextCurrent(window)
        GL.createCapabilities()
    }

    fun resize(w: Int, h: Int) {
        if (w == width && h == height && fbo != 0) return
        width = w
        height = h
        if (fbo == 0) {
            fbo = GL30.glGenFramebuffers()
            colorTex = GL30.glGenTextures()
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL30.glBindTexture(GL30.GL_TEXTURE_2D, colorTex)
        GL30.glTexImage2D(
            GL30.GL_TEXTURE_2D, 0, GL30.GL_RGBA8,
            w, h, 0, GL30.GL_RGBA, GL30.GL_UNSIGNED_BYTE, MemoryUtil.NULL,
        )
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_FILTER, GL30.GL_LINEAR)
        GL30.glTexParameteri(GL30.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAG_FILTER, GL30.GL_LINEAR)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL30.GL_TEXTURE_2D, colorTex, 0,
        )
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        check(status == GL30.GL_FRAMEBUFFER_COMPLETE) {
            "FBO incomplete: 0x${status.toString(16)}"
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
    }

    fun makeCurrent() {
        // Bind the FBO so the core's GL commands render into our target.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
    }

    fun unbind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
    }

    /** FBO ID exposed to libretro via get_current_framebuffer. */
    fun framebufferId(): Int = fbo

    /**
     * Read the color attachment back as ARGB bytes (matches libretro
     * XRGB8888 memory layout: on little-endian, byte order is B,G,R,A
     * which equals what GL_BGRA + UNSIGNED_BYTE produces).
     *
     * GL's origin is bottom-left; libretro expects top-left. Rows are
     * returned in GL order — caller is responsible for flipping if needed.
     */
    fun readPixelsRGBA(): ByteArray = readPixelsRGBA(width, height)

    /**
     * Sub-rect readback: the FBO is sized to the core's maximum geometry,
     * but the presented frame is the (smaller) viewport the core reported
     * for THIS frame - reading the whole buffer every frame returned stale
     * maximum-size images for variable-resolution cores.
     */
    fun readPixelsRGBA(w: Int, h: Int): ByteArray {
        val buffer = MemoryUtil.memAlloc(w * h * 4)
        return try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
            GL11.glReadPixels(0, 0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buffer)
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            bytes
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    fun width(): Int = width
    fun height(): Int = height

    fun destroy() {
        if (colorTex != 0) GL30.glDeleteTextures(colorTex)
        if (fbo != 0) GL30.glDeleteFramebuffers(fbo)
        GLFW.glfwDestroyWindow(window)
    }

    companion object {
        init {
            // Disable LWJGL's strict thread check for GLFW. macOS Cocoa wants
            // GLFW calls on the first thread of the process; that would require
            // -XstartOnFirstThread, which breaks Compose Desktop's window init.
            // With the check disabled, we can call GLFW from any thread.
            // May fail at glfwCreateWindow time on macOS — if so, PSP / GameCube
            // HW render needs a separate process or CGL backend.
            org.lwjgl.system.Configuration.GLFW_CHECK_THREAD0.set(false)
        }

        fun create(): GlContext {
            GLFWErrorCallback.createPrint(System.err).set()
            check(GLFW.glfwInit()) { "GLFW init failed" }
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
            // macOS wants a 3.2+ core profile for modern GL.
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2)
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
            val window = GLFW.glfwCreateWindow(1, 1, "OmilatorGL", MemoryUtil.NULL, MemoryUtil.NULL)
            check(window != MemoryUtil.NULL) { "GLFW window creation failed" }
            return GlContext(window)
        }
    }
}
