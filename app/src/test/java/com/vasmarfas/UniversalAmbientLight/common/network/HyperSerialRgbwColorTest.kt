package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HyperSerialRgbwColorTest {
    @Test
    fun `white becomes white channel only`() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, -1),
            HyperSerialRgbwColor.convert(arrayOf(ColorRgb(255, 255, 255)))
        )
    }

    @Test
    fun `primary colors retain RGB channels`() {
        assertArrayEquals(
            byteArrayOf(-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0),
            HyperSerialRgbwColor.convert(
                arrayOf(ColorRgb(255, 0, 0), ColorRgb(0, 255, 0), ColorRgb(0, 0, 255))
            )
        )
    }

    @Test
    fun `mixed color extracts common white without losing components`() {
        assertArrayEquals(
            byteArrayOf(90, 40, 0, 10),
            HyperSerialRgbwColor.convert(arrayOf(ColorRgb(100, 50, 10)))
        )
    }

    @Test
    fun `conversion does not mutate input colors`() {
        val led = ColorRgb(100, 50, 10)
        HyperSerialRgbwColor.convert(arrayOf(led))
        assertEquals(ColorRgb(100, 50, 10), led)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty LED frame is rejected`() {
        HyperSerialRgbwColor.convert(emptyArray())
    }

    @Test
    fun `converted colors encode as AWA RGBW payload`() {
        val channels = HyperSerialRgbwColor.convert(arrayOf(ColorRgb(1, 2, 3)))
        val packet = AdalightPacketEncoder.createAwaPacket(
            AdalightPacketEncoder.AwaFrame.Rgbw(channels)
        )
        assertArrayEquals(byteArrayOf(0, 1, 2, 1), packet.copyOfRange(6, 10))
    }
}
