package io.github.pons.session

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Plays Linux sound on Android. PulseAudio inside Linux writes everything it plays
 * into a FIFO (module-pipe-sink, 48 kHz stereo s16le); we read it and feed an
 * AudioTrack. There is no Android audio backend for Debian's PulseAudio, so this
 * pipe is the bridge.
 */
class AudioBridge(private val fifo: File) {

    @Volatile private var running = true
    private val thread = Thread(::pump, "pons-audio")

    fun start() = thread.start()

    fun stop() {
        running = false
        thread.interrupt()
    }

    private fun pump() {
        val deadline = System.currentTimeMillis() + FIFO_WAIT_MS
        while (running && !fifo.exists()) {
            if (System.currentTimeMillis() > deadline) return
            // stop() interrupts this wait; an uncaught InterruptedException here
            // used to crash the whole app and orphan the Linux processes.
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                return
            }
        }
        val minBuffer = AudioTrack.getMinBufferSize(RATE, CHANNELS, ENCODING)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(RATE)
                .setChannelMask(CHANNELS)
                .setEncoding(ENCODING)
                .build())
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            FileInputStream(fifo).use { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                while (running) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) track.write(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "Audio bridge stopped", e)
        } finally {
            track.release()
        }
    }

    companion object {
        private const val TAG = "Pons"
        const val RATE = 48_000
        private const val CHANNELS = AudioFormat.CHANNEL_OUT_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        /** 20 ms of 48 kHz stereo 16-bit audio. */
        private const val CHUNK_BYTES = RATE / 50 * 4
        private const val FIFO_WAIT_MS = 15_000L
    }
}
