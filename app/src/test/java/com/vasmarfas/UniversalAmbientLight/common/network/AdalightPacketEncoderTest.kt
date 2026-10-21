package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AdalightPacketEncoderTest {
    @Test(expected = IllegalArgumentException::class)
    fun `AWA rejects empty LED frames`() {
        AdalightPacketEncoder.createAwaPacket(emptyArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ADA rejects empty LED frames`() {
        AdalightPacketEncoder.createAdaPacket(emptyArray())
    }

    @Test
    fun `AWA encodes maximum LED count in header`() {
        val packet = AdalightPacketEncoder.createAwaPacket(Array(65536) { ColorRgb(0, 0, 0) })
        assertArrayEquals(byteArrayOf(-1, -1, 0x55), packet.copyOfRange(3, 6))
    }

    @Test
    fun `AWA escapes extended checksum matching header marker`() {
        val packet = AdalightPacketEncoder.createAwaPacket(arrayOf(ColorRgb(62, 0, 0)))
        assertEquals(0xaa, packet.last().toInt() and 0xff)
    }

    @Test
    fun `ADA packet uses count minus one and XOR header`() {
        val packet = AdalightPacketEncoder.createAdaPacket(arrayOf(ColorRgb(1, 2, 3)))
        assertArrayEquals(
            byteArrayOf(0x41, 0x64, 0x61, 0, 0, 0x55, 1, 2, 3),
            packet
        )
    }

    @Test
    fun `AWA packet matches HyperHDR checksum for one LED`() {
        val packet = AdalightPacketEncoder.createAwaPacket(arrayOf(ColorRgb(1, 2, 3)))
        // Контрольные байты: сумма 6, накопленная сумма 10, расширенная сумма 5.
        assertArrayEquals(
            byteArrayOf(0x41, 0x77, 0x61, 0, 0, 0x55, 1, 2, 3, 6, 10, 5),
            packet
        )
    }

    @Test
    fun `AWA calibration adds four bytes to the checksummed payload`() {
        val packet = AdalightPacketEncoder.createAwaPacket(
            arrayOf(ColorRgb(1, 2, 3)),
            AdalightPacketEncoder.WhiteCalibration(128, 160, 170, 180)
        )
        assertArrayEquals(
            byteArrayOf(0x41, 0x77, 0x41, 0, 0, 0x55, 1, 2, 3, -128, -96, -86, -76, -122, 16, -113),
            packet
        )
    }

    @Test
    fun `AWA packet matches HyperHDR checksum for two black LEDs`() {
        val packet = AdalightPacketEncoder.createAwaPacket(
            arrayOf(ColorRgb(0, 0, 0), ColorRgb(0, 0, 0))
        )
        assertArrayEquals(
            byteArrayOf(0x41, 0x77, 0x61, 0, 1, 0x54, 0, 0, 0, 0, 0, 0, 0, 0, 15),
            packet
        )
    }

    @Test
    fun `AWA RGBW packet matches HyperHDR AWa header and four channel checksum`() {
        val packet = AdalightPacketEncoder.createAwaRgbwPacket(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(
            byteArrayOf(0x41, 0x57, 0x61, 0, 0, 0x55, 1, 2, 3, 4, 10, 20, 12),
            packet
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `AWA RGBW rejects incomplete LED payload`() {
        AdalightPacketEncoder.createAwaRgbwPacket(byteArrayOf(1, 2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `AWA RGBW rejects empty LED payload`() {
        AdalightPacketEncoder.createAwaRgbwPacket(byteArrayOf())
    }

    @Test
    fun `typed RGBW frame matches raw RGBW encoder`() {
        val channels = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(
            AdalightPacketEncoder.createAwaRgbwPacket(channels),
            AdalightPacketEncoder.createAwaPacket(AdalightPacketEncoder.AwaFrame.Rgbw(channels))
        )
    }

    @Test
    fun `typed RGB frame matches existing Awa encoder`() {
        val leds = arrayOf(ColorRgb(1, 2, 3))
        assertArrayEquals(
            AdalightPacketEncoder.createAwaPacket(leds),
            AdalightPacketEncoder.createAwaPacket(AdalightPacketEncoder.AwaFrame.Rgb(leds))
        )
    }

    @Test
    fun `smoothing blackout keeps RGBW framing`() {
        val previous = AdalightPacketEncoder.AwaFrame.Rgbw(byteArrayOf(12, 34, 56, 78))
        val next = AdalightPacketEncoder.frameForSmoothedRgb(arrayOf(ColorRgb(0, 0, 0)), previous)
        assertArrayEquals(
            AdalightPacketEncoder.createAwaRgbwPacket(byteArrayOf(0, 0, 0, 0)),
            AdalightPacketEncoder.createAwaPacket(next)
        )
    }

    @Test
    fun `smoothing color update restores RGB framing`() {
        val previous = AdalightPacketEncoder.AwaFrame.Rgbw(byteArrayOf(12, 34, 56, 78))
        val next = AdalightPacketEncoder.frameForSmoothedRgb(arrayOf(ColorRgb(1, 2, 3)), previous)
        assertArrayEquals(
            AdalightPacketEncoder.createAwaPacket(arrayOf(ColorRgb(1, 2, 3))),
            AdalightPacketEncoder.createAwaPacket(next)
        )
    }

    @Test
    fun `RGBW keepalive preserves packet bytes`() {
        val frame = AdalightPacketEncoder.AwaFrame.Rgbw(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(
            AdalightPacketEncoder.createAwaPacket(frame),
            AdalightPacketEncoder.createAwaPacket(frame)
        )
    }
}
