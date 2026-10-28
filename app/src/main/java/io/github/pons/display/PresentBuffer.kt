package io.github.pons.display

import android.system.Os
import java.io.File
import java.io.RandomAccessFile
import java.lang.invoke.VarHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Reader side of Pons direct present (tools/mesa/patches/wsi_pons_fb.h).
 * Our Mesa writes finished game frames into a triple-buffered file; we mark the
 * buffer we upload as "reading" so the writer never touches it mid-upload.
 */
class PresentBuffer(private val file: File) {

    /**
     * A complete frame, valid until [release]. [pixels] is null for zero-upload
     * frames (header version 2): the frame is in Android buffer [index] of [AhbFrames].
     */
    class Frame(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val sequence: Long,
        val index: Int,
        val pixels: ByteBuffer?,
    )

    private var map: MappedByteBuffer? = null
    private var inode = -1L
    private var checks = 0

    /** Returns the newest frame if it is newer than [lastSequence], else null. */
    fun acquire(lastSequence: Long): Frame? {
        val m = mapping() ?: return null
        val sequence = m.getLong(OFF_SEQUENCE)
        if (sequence == lastSequence || sequence == 0L) return null
        repeat(3) {
            val front = m.getInt(OFF_FRONT)
            if (front < 0 || front >= m.getInt(OFF_BUFFERS)) return null
            m.putInt(OFF_READING, front)
            VarHandle.fullFence()
            // The writer may have published again before it saw our mark: retry.
            if (m.getInt(OFF_FRONT) != front) return@repeat
            val width = m.getInt(OFF_WIDTH)
            val height = m.getInt(OFF_HEIGHT)
            val stride = m.getInt(OFF_STRIDE)
            if (m.getInt(OFF_VERSION) == VERSION_AHB) {
                return Frame(width, height, m.getInt(OFF_X), m.getInt(OFF_Y), sequence, front, null)
            }
            if (stride != width * 4) return null
            val start = HEADER_SIZE + front * stride * height
            val pixels = m.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                .position(start).limit(start + stride * height) as ByteBuffer
            return Frame(width, height, m.getInt(OFF_X), m.getInt(OFF_Y), sequence, front, pixels.slice())
        }
        return null
    }

    /** Number of frames published so far (0 when no game presents); cheap, no copy. */
    fun sequence(): Long = mapping()?.getLong(OFF_SEQUENCE) ?: 0L

    /** Lets the writer reuse the buffer handed out by [acquire]. */
    fun release() {
        map?.let {
            VarHandle.fullFence()
            it.putInt(OFF_READING, NONE)
        }
    }

    private fun mapping(): MappedByteBuffer? {
        // Mesa replaces the file (rename) when the game resizes its window.
        if (checks++ % REOPEN_CHECK_CALLS == 0 && map != null && inodeOf() != inode) map = null
        map?.let { return it }
        if (!file.exists() || file.length() < HEADER_SIZE) return null
        val m = RandomAccessFile(file, "rw").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, raf.length())
        }.order(ByteOrder.LITTLE_ENDIAN) as MappedByteBuffer
        val version = m.getInt(OFF_VERSION)
        if (m.getInt(OFF_MAGIC) != MAGIC || (version != VERSION && version != VERSION_AHB)) return null
        val expected = if (version == VERSION_AHB) HEADER_SIZE.toLong() else HEADER_SIZE.toLong() +
            m.getInt(OFF_BUFFERS).toLong() * m.getInt(OFF_STRIDE) * m.getInt(OFF_HEIGHT)
        if (m.capacity() < expected) return null
        inode = inodeOf()
        map = m
        return m
    }

    private fun inodeOf(): Long = runCatching { Os.stat(file.path).st_ino }.getOrDefault(-2L)

    companion object {
        private const val MAGIC = 0x53525050
        private const val VERSION = 1
        /** Frames live in Android graphics buffers shared through [AhbFrames]. */
        private const val VERSION_AHB = 2
        private const val NONE = -1
        private const val HEADER_SIZE = 64
        private const val OFF_MAGIC = 0
        private const val OFF_VERSION = 4
        private const val OFF_WIDTH = 8
        private const val OFF_HEIGHT = 12
        private const val OFF_STRIDE = 16
        private const val OFF_BUFFERS = 20
        private const val OFF_FRONT = 24
        private const val OFF_READING = 28
        private const val OFF_SEQUENCE = 32
        private const val OFF_X = 40
        private const val OFF_Y = 44
        private const val REOPEN_CHECK_CALLS = 30
    }
}
