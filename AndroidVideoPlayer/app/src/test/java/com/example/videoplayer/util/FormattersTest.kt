package com.example.videoplayer.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `bytes formats sizes`() {
        assertEquals("0 B", Formatters.bytes(0))
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("2.0 KB", Formatters.bytes(2048))
        assertEquals("5.0 MB", Formatters.bytes(5L * 1024 * 1024))
        assertEquals("3.00 GB", Formatters.bytes(3L * 1024 * 1024 * 1024))
        assertEquals("0 B", Formatters.bytes(-1))
    }

    @Test
    fun `bitRate converts to kbps and mbps`() {
        assertEquals("未知", Formatters.bitRate(0))
        assertEquals("192 kbps", Formatters.bitRate(192_000))
        assertEquals("8.0 Mbps", Formatters.bitRate(8_000_000))
    }

    @Test
    fun `frameRate keeps integers plain and decimals to two places`() {
        assertEquals("未知", Formatters.frameRate(0f))
        assertEquals("30 fps", Formatters.frameRate(30f))
        assertEquals("29.97 fps", Formatters.frameRate(29.97f))
    }

    @Test
    fun `duration uses mm ss under an hour and h mm ss above`() {
        assertEquals("00:00", Formatters.duration(0))
        assertEquals("01:05", Formatters.duration(65_000))
        assertEquals("1:02:05", Formatters.duration(3_725_000))
        assertEquals("00:00", Formatters.duration(-5000))
    }

    @Test
    fun `dateTime returns dash for missing timestamp`() {
        assertEquals("—", Formatters.dateTime(0))
        assertEquals("—", Formatters.dateTime(-1))
    }
}
