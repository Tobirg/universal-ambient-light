package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class WledDdpHeaderTest {
    @Test
    fun `RGBW first packet uses 1440 bytes without push`() {
        val packet = ByteArray(1450)
        WledDdpHeader.write(packet, 0, 1440, rgbw = true, last = false)
        assertArrayEquals(byteArrayOf(0x40, 0, 0x1B, 1, 0, 0, 0, 0, 5, -96), packet.copyOfRange(0, 10))
    }

    @Test
    fun `RGBW second packet starts at 1440 and pushes final pixel`() {
        val packet = ByteArray(14)
        WledDdpHeader.write(packet, 1440, 4, rgbw = true, last = true)
        assertArrayEquals(byteArrayOf(0x41, 0, 0x1B, 1, 0, 0, 5, -96, 0, 4), packet.copyOfRange(0, 10))
    }

    @Test
    fun `RGB header retains three channel format`() {
        val packet = ByteArray(13)
        WledDdpHeader.write(packet, 0, 3, rgbw = false, last = true)
        assertArrayEquals(byteArrayOf(0x41, 0, 0x0B, 1, 0, 0, 0, 0, 0, 3), packet.copyOfRange(0, 10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `header rejects truncated packet`() {
        WledDdpHeader.write(ByteArray(13), 0, 4, rgbw = true, last = true)
    }
}
