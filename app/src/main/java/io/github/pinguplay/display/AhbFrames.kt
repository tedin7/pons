package io.github.pinguplay.display

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.opengl.GLES20
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileDescriptor

/**
 * Zero-upload frames. Mesa inside Linux connects to the abstract socket
 * [SOCKET_NAME], asks for buffers of the game's size ("PPAHB1 <width> <height>"),
 * and receives [COUNT] dma-buf descriptors of Android graphics buffers plus
 * "OK <stride in pixels>". It writes finished frames into them; we sample them
 * as GL textures, so no frame is uploaded with glTexSubImage2D.
 * Which buffer holds the newest frame still comes from [PresentBuffer]'s header.
 */
object AhbFrames {

    private const val TAG = "Pinguplay"
    const val SOCKET_NAME = "pinguplay-ahb"
    const val COUNT = 3

    /** One allocation; replaced when the game changes size. */
    class Set(val width: Int, val height: Int, val stride: Int, val buffers: LongArray) {
        val textures = IntArray(COUNT)
        val images = LongArray(COUNT)
    }

    @Volatile var current: Set? = null
        private set

    /** Sets replaced while the GL thread still owned their textures. */
    private val retired = ArrayDeque<Set>()

    private var server: Thread? = null

    init {
        runCatching { System.loadLibrary("pinguplay_ahb") }
            .onFailure { Log.w(TAG, "Zero-upload frames unavailable", it) }
    }

    @Synchronized
    fun startServer() {
        if (server != null) return
        server = Thread(::serve, "pinguplay-ahb").apply { isDaemon = true; start() }
    }

    /** GL thread: texture for buffer [index] of [set], created on first use. */
    fun texture(set: Set, index: Int): Int {
        if (set.textures[index] == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            set.images[index] = nativeBindTexture(set.buffers[index])
            set.textures[index] = ids[0]
        }
        return set.textures[index]
    }

    /** GL thread: frees textures and buffers of sets no longer in use. */
    fun releaseRetired() {
        while (true) {
            val set = synchronized(retired) { retired.removeFirstOrNull() } ?: return
            for (i in 0 until COUNT) {
                if (set.textures[i] != 0) GLES20.glDeleteTextures(1, intArrayOf(set.textures[i]), 0)
                nativeRelease(set.buffers[i], set.images[i])
            }
        }
    }

    private fun serve() {
        val socket = runCatching { LocalServerSocket(SOCKET_NAME) }.getOrElse {
            Log.w(TAG, "Zero-upload socket unavailable", it)
            return
        }
        while (true) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            runCatching { answer(client) }.onFailure { Log.w(TAG, "Zero-upload request failed", it) }
            runCatching { client.close() }
        }
    }

    private fun answer(client: LocalSocket) {
        val request = client.inputStream.bufferedReader().readLine() ?: return
        val parts = request.trim().split(' ')
        if (parts.size != 3 || parts[0] != "PPAHB1") return
        val width = parts[1].toInt()
        val height = parts[2].toInt()
        require(width in 1..8192 && height in 1..8192) { "Bad size $width x $height" }

        val strideOut = IntArray(1)
        val buffers = LongArray(COUNT) { nativeAllocate(width, height, strideOut) }
        if (buffers.any { it == 0L }) {
            buffers.filter { it != 0L }.forEach { nativeRelease(it, 0) }
            client.outputStream.write("NO\n".toByteArray())
            return
        }
        val descriptors = buffers.map { nativeFd(it) }
        if (descriptors.any { it < 0 }) {
            buffers.forEach { nativeRelease(it, 0) }
            client.outputStream.write("NO\n".toByteArray())
            return
        }
        val dups = descriptors.map { ParcelFileDescriptor.fromFd(it) }
        client.setFileDescriptorsForSend(dups.map { it.fileDescriptor }.toTypedArray<FileDescriptor>())
        client.outputStream.write("OK ${strideOut[0]}\n".toByteArray())
        client.outputStream.flush()
        dups.forEach { it.close() }

        current?.let { synchronized(retired) { retired.addLast(it) } }
        current = Set(width, height, strideOut[0], buffers)
        Log.i(TAG, "Zero-upload frames: ${COUNT}x $width x $height, stride ${strideOut[0]}")
    }

    @JvmStatic private external fun nativeAllocate(width: Int, height: Int, strideOut: IntArray): Long
    @JvmStatic private external fun nativeFd(buffer: Long): Int
    @JvmStatic private external fun nativeBindTexture(buffer: Long): Long
    @JvmStatic private external fun nativeRelease(buffer: Long, image: Long)
}
