package io.github.pinguplay.display

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.system.Os
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the Linux screen, letterboxed. Games using our Mesa publish finished frames
 * through [PresentBuffer] (no X server copy, no tearing); while those keep coming we
 * show them, otherwise we show the Xvfb desktop.
 * Linux usually renders below panel resolution; the shader upscales it with a
 * Catmull-Rom filter plus a light unsharp mask so text and map borders stay crisp.
 */
class FramebufferRenderer(
    private val source: File,
    presentFile: File,
    private val sharpness: Float,
) : GLSurfaceView.Renderer {

    /** What is on screen now, in X screen pixels, for touch mapping. */
    data class ShownArea(val x: Int, val y: Int, val width: Int, val height: Int)

    @Volatile var shown: ShownArea? = null
        private set

    @Volatile private var presentSequence = 0L
    @Volatile private var presentSeenAt = 0L

    private var framebuffer: XvfbFramebuffer? = null
    private val present = PresentBuffer(presentFile)

    /** Read from the UI thread on every vsync to decide whether to draw at all. */
    private val presentProbe = PresentBuffer(presentFile)

    /** True when a game published a frame we have not drawn yet. */
    fun hasNewGameFrame(): Boolean = presentProbe.sequence().let { it != 0L && it != presentSequence }

    /** True while a game is presenting (its frames drive redraws). */
    fun gameIsPresenting(): Boolean = System.nanoTime() - presentSeenAt < PRESENT_TIMEOUT_NS

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
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        val area = uploadPresentFrame() ?: uploadDesktop() ?: return
        shown = area
        draw(area.width, area.height)
    }

    /** Uploads a new game frame; keeps showing the last one while frames keep coming. */
    private fun uploadPresentFrame(): ShownArea? {
        val now = System.nanoTime()
        val frame = present.acquire(presentSequence)
        if (frame != null) {
            try {
                upload(frame.width, frame.height, frame.pixels)
            } finally {
                present.release()
            }
            presentSequence = frame.sequence
            presentSeenAt = now
            return ShownArea(frame.x, frame.y, frame.width, frame.height)
        }
        val last = shown
        val fresh = now - presentSeenAt < PRESENT_TIMEOUT_NS
        return if (fresh && last != null && presentSequence != 0L) last else null
    }

    private fun uploadDesktop(): ShownArea? {
        if (frames++ % REOPEN_CHECK_FRAMES == 0) dropIfReplaced()
        val fb = framebuffer ?: XvfbFramebuffer.open(source)?.also {
            framebuffer = it
            sourceInode = inodeOf(source)
        } ?: return null
        if (fb.bytesPerLine != fb.width * 4) return null
        upload(fb.width, fb.height, fb.pixels)
        return ShownArea(0, 0, fb.width, fb.height)
    }

    private fun upload(width: Int, height: Int, pixels: ByteBuffer) {
        pixels.position(0)
        if (textureSize != width to height) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            textureSize = width to height
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        }
    }

    private fun draw(width: Int, height: Int) {
        applyLetterbox(width, height)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uTexSize"),
            width.toFloat(), height.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSharp"), sharpness)
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
        /** A game that stops presenting for this long hands the screen back to the desktop. */
        private const val PRESENT_TIMEOUT_NS = 500_000_000L

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
        // Catmull-Rom in 9 bilinear taps, then unsharp masking against a 4-tap blur.
        private const val FRAGMENT_SHADER = """
            precision highp float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uTexSize;
            uniform float uSharp;

            vec3 fetch(vec2 uv) { return texture2D(uTex, uv).bgr; }

            vec3 catmullRom(vec2 uv) {
                vec2 pos = uv * uTexSize;
                vec2 t1 = floor(pos - 0.5) + 0.5;
                vec2 f = pos - t1;
                vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
                vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
                vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
                vec2 w3 = f * f * (-0.5 + 0.5 * f);
                vec2 w12 = w1 + w2;
                vec2 p0 = (t1 - 1.0) / uTexSize;
                vec2 p3 = (t1 + 2.0) / uTexSize;
                vec2 p12 = (t1 + w2 / w12) / uTexSize;
                vec3 c = fetch(vec2(p0.x, p0.y)) * w0.x * w0.y
                       + fetch(vec2(p12.x, p0.y)) * w12.x * w0.y
                       + fetch(vec2(p3.x, p0.y)) * w3.x * w0.y
                       + fetch(vec2(p0.x, p12.y)) * w0.x * w12.y
                       + fetch(vec2(p12.x, p12.y)) * w12.x * w12.y
                       + fetch(vec2(p3.x, p12.y)) * w3.x * w12.y
                       + fetch(vec2(p0.x, p3.y)) * w0.x * w3.y
                       + fetch(vec2(p12.x, p3.y)) * w12.x * w3.y
                       + fetch(vec2(p3.x, p3.y)) * w3.x * w3.y;
                return c;
            }

            void main() {
                vec3 c = catmullRom(vUv);
                vec2 px = 1.0 / uTexSize;
                vec3 blur = (fetch(vUv + vec2(px.x, 0.0)) + fetch(vUv - vec2(px.x, 0.0))
                           + fetch(vUv + vec2(0.0, px.y)) + fetch(vUv - vec2(0.0, px.y))) * 0.25;
                gl_FragColor = vec4(clamp(c + uSharp * (c - blur), 0.0, 1.0), 1.0);
            }
        """
    }
}
