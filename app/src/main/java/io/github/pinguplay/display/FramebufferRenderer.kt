package io.github.pinguplay.display

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.system.Os
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Draws the Xvfb screen, letterboxed, uploading it as a texture every frame. */
class FramebufferRenderer(private val source: File) : GLSurfaceView.Renderer {

    @Volatile var framebuffer: XvfbFramebuffer? = null
        private set

    /** Where the Linux screen sits inside the view, for touch mapping. */
    @Volatile var viewport = IntArray(4)
        private set

    private var program = 0
    private var texture = 0
    private var textureSize = 0 to 0
    private var surfaceSize = 0 to 0
    private var sourceInode = -1L
    private var frames = 0

    private val quad: ByteBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder())
        .apply { asFloatBuffer().put(QUAD) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        textureSize = 0 to 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceSize = width to height
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (frames++ % REOPEN_CHECK_FRAMES == 0) dropIfReplaced()
        val fb = framebuffer ?: XvfbFramebuffer.open(source)?.also {
            framebuffer = it
            sourceInode = inodeOf(source)
        } ?: return
        if (fb.bytesPerLine != fb.width * 4) return

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        fb.pixels.position(0)
        if (textureSize != fb.width to fb.height) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fb.width, fb.height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, fb.pixels)
            textureSize = fb.width to fb.height
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, fb.width, fb.height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, fb.pixels)
        }

        applyLetterbox(fb.width, fb.height)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPos")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 4, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Xvfb recreates the file when Linux restarts; our old mapping would freeze. */
    private fun dropIfReplaced() {
        if (framebuffer != null && inodeOf(source) != sourceInode) framebuffer = null
    }

    private fun inodeOf(file: File): Long = runCatching { Os.stat(file.path).st_ino }.getOrDefault(-2L)

    private fun applyLetterbox(fbWidth: Int, fbHeight: Int) {
        val (sw, sh) = surfaceSize
        val scale = minOf(sw.toFloat() / fbWidth, sh.toFloat() / fbHeight)
        val w = (fbWidth * scale).toInt()
        val h = (fbHeight * scale).toInt()
        val x = (sw - w) / 2
        val y = (sh - h) / 2
        GLES20.glViewport(x, y, w, h)
        viewport = intArrayOf(x, y, w, h)
    }

    private fun linkProgram(vertex: String, fragment: String): Int {
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        return program
    }

    private fun compile(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader error: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    companion object {
        private const val REOPEN_CHECK_FRAMES = 30

        /** x, y, u, v for a full-viewport strip; v is flipped because X rows go top-down. */
        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )
        private const val VERTEX_SHADER = """
            attribute vec4 aPos;
            varying vec2 vUv;
            void main() { vUv = aPos.zw; gl_Position = vec4(aPos.xy, 0.0, 1.0); }
        """
        // Xvfb stores BGRX; the texture is uploaded as RGBA, so swap red and blue here.
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() { gl_FragColor = vec4(texture2D(uTex, vUv).bgr, 1.0); }
        """
    }
}
