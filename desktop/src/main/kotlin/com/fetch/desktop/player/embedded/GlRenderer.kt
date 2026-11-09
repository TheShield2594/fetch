package com.fetch.desktop.player.embedded

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** The EGL calls that make an offscreen OpenGL context. */
@Suppress("FunctionName")
internal interface LibEgl : Library {
    fun eglGetDisplay(nativeDisplay: Pointer?): Pointer?
    fun eglGetPlatformDisplay(platform: Int, nativeDisplay: Pointer?, attribs: Pointer?): Pointer?
    fun eglInitialize(display: Pointer, major: IntByReference, minor: IntByReference): Int
    fun eglBindAPI(api: Int): Int
    fun eglChooseConfig(display: Pointer, attribs: IntArray, configs: Array<Pointer?>, size: Int, count: IntByReference): Int
    fun eglCreateContext(display: Pointer, config: Pointer, share: Pointer?, attribs: IntArray?): Pointer?
    fun eglCreatePbufferSurface(display: Pointer, config: Pointer, attribs: IntArray): Pointer?
    fun eglMakeCurrent(display: Pointer, draw: Pointer?, read: Pointer?, context: Pointer?): Int
    fun eglGetProcAddress(name: String): Pointer?
    fun eglDestroySurface(display: Pointer, surface: Pointer): Int
    fun eglDestroyContext(display: Pointer, context: Pointer): Int
    fun eglGetError(): Int

    companion object {
        const val OPENGL_API = 0x30A2
        const val SURFACE_TYPE = 0x3033
        const val PBUFFER_BIT = 0x0001
        const val RENDERABLE_TYPE = 0x3040
        const val OPENGL_BIT = 0x0008
        const val RED_SIZE = 0x3024
        const val GREEN_SIZE = 0x3023
        const val BLUE_SIZE = 0x3022
        const val WIDTH = 0x3057
        const val HEIGHT = 0x3056
        const val NONE = 0x3038
        const val PLATFORM_X11 = 0x31D5
        const val PLATFORM_SURFACELESS_MESA = 0x31DD
    }
}

/** For mpv's hardware decoding interop: the X11 display Fetch's window is on. */
@Suppress("FunctionName")
internal interface LibX11 : Library {
    fun XOpenDisplay(name: String?): Pointer?
}

/**
 * Renders mpv with OpenGL on the GPU (mpv's gpu renderer: hardware decoding, scaling, HDR tone
 * mapping) in an offscreen EGL context, then reads the finished frame into the bitmap Compose
 * draws. Only a window-sized frame crosses back to memory, whatever the video's size.
 *
 * Every call must come from the one thread that [create]d it, which keeps the context current.
 */
internal class GlRenderer private constructor(
    private val egl: LibEgl,
    private val display: Pointer,
    private val surface: Pointer,
    private val context: Pointer,
    /** Native X11 display for mpv's VA-API interop, or null. */
    val x11Display: Pointer?
) {
    // The GL functions, looked up through EGL as mpv does.
    private fun gl(name: String): Function =
        Function.getFunction(egl.eglGetProcAddress(name) ?: error("OpenGL has no $name"))

    private val glGenFramebuffers = gl("glGenFramebuffers")
    private val glBindFramebuffer = gl("glBindFramebuffer")
    private val glGenTextures = gl("glGenTextures")
    private val glBindTexture = gl("glBindTexture")
    private val glTexImage2D = gl("glTexImage2D")
    private val glTexParameteri = gl("glTexParameteri")
    private val glFramebufferTexture2D = gl("glFramebufferTexture2D")
    private val glCheckFramebufferStatus = gl("glCheckFramebufferStatus")
    private val glPixelStorei = gl("glPixelStorei")
    private val glReadPixels = gl("glReadPixels")
    private val glDeleteFramebuffers = gl("glDeleteFramebuffers")
    private val glDeleteTextures = gl("glDeleteTextures")
    private val glFinish = gl("glFinish")

    /** The OpenGL driver's name, such as `llvmpipe (LLVM 20.1.2, 256 bits)`. */
    val rendererName: String =
        runCatching { gl("glGetString").invokePointer(arrayOf(GL_RENDERER))?.getString(0) }.getOrNull().orEmpty()

    private var fbo = 0
    private var texture = 0
    private var fboWidth = 0
    private var fboHeight = 0

    /** mpv's `get_proc_address`, kept referenced so JNA doesn't free it. */
    private val procAddress = GetProcAddress { _, name -> egl.eglGetProcAddress(name) }
    private val initParams = Memory(2L * Native.POINTER_SIZE).apply {
        setPointer(0, com.sun.jna.CallbackReference.getFunctionPointer(procAddress))
        setPointer(Native.POINTER_SIZE.toLong(), Pointer.NULL)
    }
    private val apiType = Memory(7).apply { setString(0, "opengl") }

    // Each frame's parameters and GL id scratch, made once and refilled (this thread only).
    private val fboParam = Memory(16)
    private val flip = Memory(4).apply { setInt(0, 0) }
    private val frameParams = Memory(EmbeddedMpv.renderParamsBytes(2))
    private val ids = Memory(4)

    fun interface GetProcAddress : Callback {
        fun invoke(ctx: Pointer?, name: String): Pointer?
    }

    /** The parameters for `mpv_render_context_create`: OpenGL, and the X11 display if any. */
    fun createParams(): List<Pair<Int, Pointer>> = buildList {
        add(LibMpv.RENDER_PARAM_API_TYPE to apiType)
        add(LibMpv.RENDER_PARAM_OPENGL_INIT_PARAMS to initParams)
        x11Display?.let { add(LibMpv.RENDER_PARAM_X11_DISPLAY to it) }
    }

    /**
     * Has mpv draw the current frame at [width]×[height] and copies it into [pixels] (BGRA rows of
     * [rowBytes], top row first). False when OpenGL refused.
     */
    fun render(mpv: LibMpv, renderCtx: Pointer, width: Int, height: Int, pixels: Pointer, rowBytes: Int): Boolean {
        if (!ensureFbo(width, height)) return false
        fboParam.setInt(0, fbo)
        fboParam.setInt(4, width)
        fboParam.setInt(8, height)
        fboParam.setInt(12, GL_RGBA8)
        // mpv draws an FBO top row first already; no flip ([flip] stays 0).
        EmbeddedMpv.fillRenderParams(frameParams, listOf(LibMpv.RENDER_PARAM_OPENGL_FBO to fboParam, LibMpv.RENDER_PARAM_FLIP_Y to flip))
        val rc = mpv.mpv_render_context_render(renderCtx, frameParams)
        if (rc < 0) return false
        glBindFramebuffer.invoke(arrayOf(GL_FRAMEBUFFER, fbo))
        glPixelStorei.invoke(arrayOf(GL_PACK_ALIGNMENT, 4))
        glPixelStorei.invoke(arrayOf(GL_PACK_ROW_LENGTH, rowBytes / 4))
        glReadPixels.invoke(arrayOf(0, 0, width, height, GL_BGRA, GL_UNSIGNED_BYTE, pixels))
        glPixelStorei.invoke(arrayOf(GL_PACK_ROW_LENGTH, 0))
        glBindFramebuffer.invoke(arrayOf(GL_FRAMEBUFFER, 0))
        return true
    }

    private fun ensureFbo(width: Int, height: Int): Boolean {
        if (fbo != 0 && width == fboWidth && height == fboHeight) return true
        deleteFbo()
        glGenTextures.invoke(arrayOf(1, ids))
        texture = ids.getInt(0)
        glBindTexture.invoke(arrayOf(GL_TEXTURE_2D, texture))
        glTexParameteri.invoke(arrayOf(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR))
        glTexParameteri.invoke(arrayOf(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR))
        glTexImage2D.invoke(arrayOf(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, Pointer.NULL))
        // Leave the texture unbound, so mpv starts from the default state.
        glBindTexture.invoke(arrayOf(GL_TEXTURE_2D, 0))
        glGenFramebuffers.invoke(arrayOf(1, ids))
        fbo = ids.getInt(0)
        glBindFramebuffer.invoke(arrayOf(GL_FRAMEBUFFER, fbo))
        glFramebufferTexture2D.invoke(arrayOf(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0))
        val status = glCheckFramebufferStatus.invokeInt(arrayOf(GL_FRAMEBUFFER))
        glBindFramebuffer.invoke(arrayOf(GL_FRAMEBUFFER, 0))
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            deleteFbo()
            return false
        }
        fboWidth = width
        fboHeight = height
        return true
    }

    private fun deleteFbo() {
        if (fbo != 0) {
            ids.setInt(0, fbo)
            glDeleteFramebuffers.invoke(arrayOf(1, ids))
        }
        if (texture != 0) {
            ids.setInt(0, texture)
            glDeleteTextures.invoke(arrayOf(1, ids))
        }
        fbo = 0
        texture = 0
    }

    fun close() {
        runCatching { glFinish.invoke(emptyArray()) }
        deleteFbo()
        egl.eglMakeCurrent(display, null, null, null)
        egl.eglDestroySurface(display, surface)
        egl.eglDestroyContext(display, context)
        // The display is shared by every player in the process and stays initialised:
        // eglTerminate would also kill the context of a player opened while this one closes
        // (Up Next), which then draws only black.
    }

    companion object {
        private const val GL_RENDERER = 0x1F01
        private const val GL_FRAMEBUFFER = 0x8D40
        private const val GL_TEXTURE_2D = 0x0DE1
        private const val GL_RGBA8 = 0x8058
        private const val GL_RGBA = 0x1908
        private const val GL_BGRA = 0x80E1
        private const val GL_UNSIGNED_BYTE = 0x1401
        private const val GL_COLOR_ATTACHMENT0 = 0x8CE0
        private const val GL_TEXTURE_MIN_FILTER = 0x2801
        private const val GL_TEXTURE_MAG_FILTER = 0x2800
        private const val GL_LINEAR = 0x2601
        private const val GL_FRAMEBUFFER_COMPLETE = 0x8CD5
        private const val GL_PACK_ALIGNMENT = 0x0D05
        private const val GL_PACK_ROW_LENGTH = 0x0D02

        private val egl: LibEgl? by lazy { runCatching { Native.load("EGL", LibEgl::class.java) }.getOrNull() }

        /** Fetch's X11 connection, opened once and kept: EGL displays made from it outlive any one player. */
        private val x11Display: Pointer? by lazy {
            if (System.getenv("DISPLAY").isNullOrBlank()) return@lazy null
            runCatching { Native.load("X11", LibX11::class.java).XOpenDisplay(null) }.getOrNull()
        }

        /**
         * An offscreen OpenGL context, current on the calling thread; null when there's no usable
         * EGL/OpenGL (the player then renders in software). Tries the X11 display first, so mpv
         * can share decoded frames with VA-API, then Mesa's surfaceless platform.
         *
         * A software rasteriser (Mesa's llvmpipe, without a GPU) is refused unless [allowSoftware]:
         * it's no faster than mpv's software renderer, and with mpv's default lanczos scaler
         * llvmpipe sometimes draws every frame black.
         */
        @Synchronized
        fun create(allowSoftware: Boolean = false): GlRenderer? {
            val egl = egl ?: return null
            val xDisplay = x11Display
            val candidates = buildList<Pair<Pointer?, () -> Pointer?>> {
                if (xDisplay != null) add(xDisplay to { runCatching { egl.eglGetPlatformDisplay(LibEgl.PLATFORM_X11, xDisplay, null) }.getOrNull() })
                add(null to { runCatching { egl.eglGetPlatformDisplay(LibEgl.PLATFORM_SURFACELESS_MESA, null, null) }.getOrNull() })
                add(null to { egl.eglGetDisplay(null) })
            }
            for ((native, candidate) in candidates) {
                val display = candidate() ?: continue
                val renderer = runCatching { open(egl, display, native) }.getOrNull() ?: continue
                if (!allowSoftware && isSoftware(renderer.rendererName)) {
                    renderer.close()
                    return null
                }
                return renderer
            }
            return null
        }

        private fun open(egl: LibEgl, display: Pointer, xDisplay: Pointer?): GlRenderer? {
            // Initialising an already initialised display is a no-op, so players can share it.
            if (egl.eglInitialize(display, IntByReference(), IntByReference()) == 0) return null
            if (egl.eglBindAPI(LibEgl.OPENGL_API) == 0) return null
            val attribs = intArrayOf(
                LibEgl.SURFACE_TYPE, LibEgl.PBUFFER_BIT,
                LibEgl.RENDERABLE_TYPE, LibEgl.OPENGL_BIT,
                LibEgl.RED_SIZE, 8, LibEgl.GREEN_SIZE, 8, LibEgl.BLUE_SIZE, 8,
                LibEgl.NONE
            )
            val configs = arrayOfNulls<Pointer>(1)
            val count = IntByReference()
            if (egl.eglChooseConfig(display, attribs, configs, 1, count) == 0 || count.value < 1) return null
            val config = configs[0]!!
            val context = egl.eglCreateContext(display, config, null, null)
            val surface = egl.eglCreatePbufferSurface(display, config, intArrayOf(LibEgl.WIDTH, 16, LibEgl.HEIGHT, 16, LibEgl.NONE))
            if (context == null || surface == null || egl.eglMakeCurrent(display, surface, surface, context) == 0) {
                context?.let { egl.eglDestroyContext(display, it) }
                surface?.let { egl.eglDestroySurface(display, it) }
                return null
            }
            return GlRenderer(egl, display, surface, context, xDisplay)
        }

        /** True for OpenGL drivers that draw on the CPU. */
        fun isSoftware(rendererName: String): Boolean {
            val name = rendererName.lowercase()
            return listOf("llvmpipe", "softpipe", "swrast", "swiftshader", "software rasterizer").any { it in name }
        }
    }
}
