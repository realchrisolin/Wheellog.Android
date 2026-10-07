package com.cooper.wheellog.utils

import java.io.ByteArrayOutputStream

/**
 * Holds lines until [chunkBytes] are pending or [intervalMs] has passed since the last take.
 * [take] returns whatever is left, for a flush or a close.
 */
class LineBuffer(
    private val chunkBytes: Int = 8192,
    private val intervalMs: Long = 1000L,
    private val elapsedMs: () -> Long,
) {
    private val pending = ByteArrayOutputStream(chunkBytes)
    private var lastFlushMs = elapsedMs()

    fun append(line: ByteArray): ByteArray? {
        pending.write(line)
        if (pending.size() < chunkBytes && elapsedMs() - lastFlushMs < intervalMs) {
            return null
        }
        return take()
    }

    fun take(): ByteArray? {
        if (pending.size() == 0) {
            lastFlushMs = elapsedMs()
            return null
        }
        val chunk = pending.toByteArray()
        pending.reset()
        lastFlushMs = elapsedMs()
        return chunk
    }
}
