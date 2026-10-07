package com.cooper.wheellog.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LineBufferTest {
    @Test
    fun `stays buffered inside the size and time limits`() {
        var now = 0L
        val buffer = LineBuffer(chunkBytes = 8, intervalMs = 1000L) { now }

        assertThat(buffer.append("ab\r\n".toByteArray())).isNull()
        now = 999
        assertThat(buffer.append("c".toByteArray())).isNull()
    }

    @Test
    fun `flushes when the interval elapses`() {
        var now = 0L
        val buffer = LineBuffer(chunkBytes = 100, intervalMs = 1000L) { now }
        buffer.append("ab".toByteArray())
        now = 1000

        assertThat(buffer.append("cd".toByteArray())).isEqualTo("abcd".toByteArray())

        now = 1001
        assertThat(buffer.append("e".toByteArray())).isNull()
    }

    @Test
    fun `flushes when the chunk fills`() {
        var now = 0L
        val buffer = LineBuffer(chunkBytes = 4, intervalMs = 60_000L) { now }

        assertThat(buffer.append("ab".toByteArray())).isNull()
        assertThat(buffer.append("cd".toByteArray())).isEqualTo("abcd".toByteArray())
    }

    @Test
    fun `take returns the tail and then nothing`() {
        var now = 0L
        val buffer = LineBuffer(chunkBytes = 100, intervalMs = 60_000L) { now }
        buffer.append("row\r\n".toByteArray())

        assertThat(buffer.take()).isEqualTo("row\r\n".toByteArray())
        assertThat(buffer.take()).isNull()
    }
}
