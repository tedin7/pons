package io.github.pons.display

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Read-only view of the screen Xvfb keeps in a file (started with `-fbdir /tmp`).
 * The file is an XWD image: a big-endian header, a colormap, then raw pixels.
 */
class XvfbFramebuffer private constructor(
    val width: Int,
    val height: Int,
    val bytesPerLine: Int,
    /** BGRX pixels, [bytesPerLine] bytes per row. */
    val pixels: ByteBuffer,
) {
    companion object {
        private const val XWD_HEADER_FIELDS = 25
        private const val XWD_COLOR_SIZE = 12

        /** Returns null while Xvfb has not created a 32-bit framebuffer yet. */
        fun open(file: File): XvfbFramebuffer? {
            if (!file.exists() || file.length() < XWD_HEADER_FIELDS * 4) return null
            RandomAccessFile(file, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                map.order(ByteOrder.BIG_ENDIAN)
                val header = IntArray(XWD_HEADER_FIELDS) { map.getInt(it * 4) }
                val headerSize = header[0]
                val width = header[4]
                val height = header[5]
                val bitsPerPixel = header[11]
                val bytesPerLine = header[12]
                val colors = header[19]
                if (bitsPerPixel != 32 || width <= 0 || height <= 0) return null
                val offset = headerSize + colors * XWD_COLOR_SIZE
                if (offset.toLong() + bytesPerLine.toLong() * height > map.capacity()) return null
                map.position(offset)
                val pixels = map.slice().order(ByteOrder.LITTLE_ENDIAN)
                return XvfbFramebuffer(width, height, bytesPerLine, pixels)
            }
        }
    }
}
