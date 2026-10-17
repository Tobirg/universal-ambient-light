package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AwaRgbwTest {
    @Test
    fun `ICE RGBW uses the AWa v11 marker and four bytes per LED`() {
        val packet = AwaRgbw().packet(arrayOf(ColorRgb(255, 255, 255)), RgbwCalibration())
        assertEquals(13, packet.size) // 6 заголовок + 4 RGBW + 3 контрольная сумма
        assertArrayEquals("AWa".toByteArray(), packet.copyOfRange(0, 3))
        assertEquals(0, packet[3].toInt() and 255)
        assertEquals(0, packet[4].toInt() and 255)
        assertEquals(0x55, packet[5].toInt() and 255)
        assertArrayEquals(byteArrayOf(0, 0, 0, 142.toByte()), packet.copyOfRange(6, 10))
    }

    @Test
    fun `saturated colors never generate white and gain zero disables extraction`() {
        val saturated = AwaRgbw().packet(arrayOf(ColorRgb(255, 120, 0)), RgbwCalibration())
        assertArrayEquals(byteArrayOf(255.toByte(), 120, 0, 0), saturated.copyOfRange(6, 10))

        val noWhite = AwaRgbw().packet(arrayOf(ColorRgb(210, 180, 170)),
            RgbwCalibration(whiteLedIntensity = 0))
        assertArrayEquals(byteArrayOf(210.toByte(), 180.toByte(), 170.toByte(), 0),
            noWhite.copyOfRange(6, 10))
    }

    @Test
    fun `ICE white point calibration compensates RGB residuals`() {
        val packet = AwaRgbw().packet(arrayOf(ColorRgb(210, 180, 170)),
            RgbwCalibration(whiteLedIntensity = 180, whiteRed = 255, whiteGreen = 200, whiteBlue = 170))
        assertEquals(160, packet[6].toInt() and 255)
        assertEquals(140, packet[7].toInt() and 255)
        assertEquals(136, packet[8].toInt() and 255)
        assertEquals(28, packet[9].toInt() and 255)
    }

    @Test
    fun `checksum includes all four bytes and counts LEDs not channels`() {
        val leds = Array(226) { ColorRgb(it, it / 2, it / 3) }
        val packet = AwaRgbw().packet(leds, RgbwCalibration())
        assertEquals(6 + 226 * 4 + 3, packet.size)
        assertEquals(0, packet[3].toInt() and 255)
        assertEquals(225, packet[4].toInt() and 255)
        assertEquals((225 xor 0x55), packet[5].toInt() and 255)

        var first = 0
        var second = 0
        var extra = 0
        for (i in 0 until 226 * 4) {
            val byte = packet[6 + i].toInt() and 255
            first = (first + byte) % 255
            second = (second + first) % 255
            extra = (extra + (byte xor (i and 255))) % 255
        }
        assertEquals(first, packet[packet.size - 3].toInt() and 255)
        assertEquals(second, packet[packet.size - 2].toInt() and 255)
        assertEquals(if (extra == 0x41) 0xaa else extra,
            packet.last().toInt() and 255)
    }

    @Test
    fun `white mixer threshold suppresses white below the selected luminance`() {
        val leds = arrayOf(ColorRgb(100, 100, 100))
        val result = AwaRgbw().packet(leds,
            RgbwCalibration(whiteLedIntensity = 180, whiteMixerThreshold = 50))
        assertArrayEquals(byteArrayOf(100, 100, 100, 0), result.copyOfRange(6, 10))

        val fullWhite = AwaRgbw().packet(arrayOf(ColorRgb(255, 255, 255)),
            RgbwCalibration(whiteLedIntensity = 180, whiteMixerThreshold = 100))
        assertEquals(142, fullWhite[9].toInt() and 255)
    }

    @Test
    fun `saved White Gain migrates to ICE intensity without changing its default`() {
        assertEquals(180, RgbwCalibration.legacyGainToIntensity(255))
        assertEquals(0, RgbwCalibration.legacyGainToIntensity(0))
        assertEquals(358, RgbwCalibration.legacyGainToIntensity(128))
    }

    @Test
    fun `invalid LED counts are rejected instead of emitting broken frames`() {
        var failed = false
        try { AwaRgbw().packet(emptyArray(), RgbwCalibration()) }
        catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
