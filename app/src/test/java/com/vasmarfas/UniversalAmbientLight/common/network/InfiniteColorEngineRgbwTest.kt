package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class InfiniteColorEngineRgbwTest {
    @Test
    fun `white frame uses white channel`() {
        val channels = InfiniteColorEngineRgbw().convert(arrayOf(ColorRgb(255, 255, 255)))
        assertArrayEquals(byteArrayOf(0, 0, 0, -1), channels)
    }

    @Test
    fun `primary color does not use white channel`() {
        val channels = InfiniteColorEngineRgbw().convert(arrayOf(ColorRgb(255, 0, 0)))
        assertArrayEquals(byteArrayOf(-1, 0, 0, 0), channels)
    }

    @Test
    fun `unchanged frame remains stable`() {
        val engine = InfiniteColorEngineRgbw()
        val frame = arrayOf(ColorRgb(80, 90, 100))
        val first = engine.convert(frame)
        assertArrayEquals(first, engine.convert(frame))
    }

    @Test
    fun `state resizes with led count`() {
        val engine = InfiniteColorEngineRgbw()
        engine.convert(arrayOf(ColorRgb(10, 10, 10)))
        assertEquals(8, engine.convert(arrayOf(ColorRgb(0, 0, 0), ColorRgb(255, 255, 255))).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty frame is rejected`() {
        InfiniteColorEngineRgbw().convert(emptyArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid white point is rejected`() {
        InfiniteColorEngineRgbw(whitePointRgb = floatArrayOf(1f, 0f, 1f))
    }
    @Test
    fun `brightness scales RGBW output without intermediate LED objects`() {
        val channels = InfiniteColorEngineRgbw().convert(
            arrayOf(ColorRgb(255, 255, 255)), brightness = 128
        )
        assertArrayEquals(byteArrayOf(64, 64, 64, 64), channels)
    }

    @Test
    fun `black clears previous ICE output`() {
        val engine = InfiniteColorEngineRgbw()
        engine.convert(arrayOf(ColorRgb(255, 255, 255)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), engine.convert(arrayOf(ColorRgb(0, 0, 0))))
    }

    @Test
    fun `unchanged input clears dithering error before next movement`() {
        val engine = InfiniteColorEngineRgbw(whiteLedIntensity = 0f)
        val frame = arrayOf(ColorRgb(40, 50, 60))
        engine.convert(frame)
        engine.convert(arrayOf(ColorRgb(41, 51, 61)))
        engine.convert(arrayOf(ColorRgb(42, 52, 62)))
        engine.convert(arrayOf(ColorRgb(42, 52, 62)))
        val actual = engine.convert(arrayOf(ColorRgb(43, 53, 63)))
        val fresh = InfiniteColorEngineRgbw(whiteLedIntensity = 0f)
        fresh.convert(arrayOf(ColorRgb(42, 52, 62)))
        val expected = fresh.convert(arrayOf(ColorRgb(43, 53, 63)))
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `black frame clears state without affecting next color`() {
        val engine = InfiniteColorEngineRgbw()
        engine.convert(arrayOf(ColorRgb(255, 255, 255)))
        engine.convert(arrayOf(ColorRgb(0, 0, 0)))
        assertArrayEquals(
            InfiniteColorEngineRgbw().convert(arrayOf(ColorRgb(80, 90, 100))),
            engine.convert(arrayOf(ColorRgb(80, 90, 100)))
        )
    }

    @Test
    fun `ICE channel stream crosses DDP RGBW packet boundary without gaps`() {
        val leds = Array(361) { index ->
            if (index == 359) ColorRgb(255, 0, 0)
            else if (index == 360) ColorRgb(0, 255, 0)
            else ColorRgb(0, 0, 0)
        }
        val channels = InfiniteColorEngineRgbw().convert(leds)
        assertArrayEquals(
            byteArrayOf(-1, 0, 0, 0, 0, -1, 0, 0),
            channels.copyOfRange(359 * 4, 361 * 4)
        )
    }

    @Test
    fun `ICE preserves the last DRGBW pixel at 367 LEDs`() {
        val leds = Array(367) { ColorRgb(0, 0, 0) }
        leds[366] = ColorRgb(255, 255, 255)
        val channels = InfiniteColorEngineRgbw().convert(leds)
        assertArrayEquals(byteArrayOf(0, 0, 0, -1), channels.copyOfRange(366 * 4, 367 * 4))
    }

    @Test
    fun `ICE output can exceed the UDP DRGBW pixel limit for DDP`() {
        val channels = InfiniteColorEngineRgbw().convert(Array(368) { ColorRgb(255, 0, 0) })
        assertEquals(368 * 4, channels.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid brightness is rejected`() {
        InfiniteColorEngineRgbw().convert(arrayOf(ColorRgb(1, 2, 3)), brightness = 256)
    }

    @Test
    fun `fractional brightness survives until channel rounding`() {
        val channels = InfiniteColorEngineRgbw().convert(
            arrayOf(ColorRgb(1, 0, 0)), brightness = 128
        )
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), channels)
    }

    @Test
    fun `zero brightness clears all channels`() {
        val engine = InfiniteColorEngineRgbw()
        engine.convert(arrayOf(ColorRgb(255, 255, 255)))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0),
            engine.convert(arrayOf(ColorRgb(255, 255, 255)), brightness = 0)
        )
    }

    @Test
    fun `buffer conversion preserves packet header and trailer`() {
        val buffer = ByteArray(8) { 42 }
        InfiniteColorEngineRgbw().convertInto(arrayOf(ColorRgb(255, 0, 0)), buffer, offset = 2)
        assertArrayEquals(byteArrayOf(42, 42, -1, 0, 0, 0, 42, 42), buffer)
    }

    @Test
    fun `reused buffer receives unchanged frame after being cleared`() {
        val engine = InfiniteColorEngineRgbw()
        val frame = arrayOf(ColorRgb(255, 255, 255))
        val buffer = ByteArray(4)
        engine.convertInto(frame, buffer)
        buffer.fill(0)
        engine.convertInto(frame, buffer)
        assertArrayEquals(byteArrayOf(0, 0, 0, -1), buffer)
    }

    @Test
    fun `writing next frame does not overwrite previous output`() {
        val engine = InfiniteColorEngineRgbw()
        val sent = ByteArray(4)
        engine.convertInto(arrayOf(ColorRgb(255, 0, 0)), sent)
        engine.convertInto(arrayOf(ColorRgb(0, 255, 0)), ByteArray(4))
        assertArrayEquals(byteArrayOf(-1, 0, 0, 0), sent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short output buffer is rejected`() {
        InfiniteColorEngineRgbw().convertInto(arrayOf(ColorRgb(1, 2, 3)), ByteArray(3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative output offset is rejected`() {
        InfiniteColorEngineRgbw().convertInto(arrayOf(ColorRgb(1, 2, 3)), ByteArray(4), offset = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `overflowing output offset is rejected`() {
        InfiniteColorEngineRgbw().convertInto(
            arrayOf(ColorRgb(1, 2, 3)), ByteArray(4), offset = Int.MAX_VALUE
        )
    }

    @Test
    fun `ICE uses fractional RGB instead of the rounded transport view`() {
        val output = InfiniteColorEngineRgbw().convert(
            arrayOf(ColorRgb(100, 100, 100)), precise = floatArrayOf(100.4f, 100.4f, 100.4f)
        )
        assertArrayEquals(byteArrayOf(60, 60, 60, 40), output)
    }

    @Test
    fun `zero white intensity disables white extraction`() {
        val output = InfiniteColorEngineRgbw(whiteLedIntensity = 0f).convert(arrayOf(ColorRgb(255, 255, 255)))
        assertArrayEquals(byteArrayOf(-1, -1, -1, 0), output)
    }

    @Test
    fun `white threshold keeps dim gray in RGB channels`() {
        val output = InfiniteColorEngineRgbw(whiteMixerThreshold = 0.5f).convert(arrayOf(ColorRgb(100, 100, 100)))
        assertArrayEquals(byteArrayOf(100, 100, 100, 0), output)
    }

    @Test
    fun `white intensity scales white output before compensation`() {
        val output = InfiniteColorEngineRgbw(whiteLedIntensity = 2f).convert(arrayOf(ColorRgb(200, 200, 200)))
        assertArrayEquals(byteArrayOf(44, 44, 44, 78), output)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ICE rejects nonfinite precise channels`() {
        InfiniteColorEngineRgbw().convert(arrayOf(ColorRgb(1, 2, 3)), precise = floatArrayOf(Float.NaN, 2f, 3f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ICE rejects incomplete precise pixels`() {
        InfiniteColorEngineRgbw().convert(arrayOf(ColorRgb(1, 2, 3)), precise = floatArrayOf(1f, 2f))
    }

    @Test
    fun `custom white point changes white extraction and RGB compensation`() {
        val output = InfiniteColorEngineRgbw(whitePointRgb = floatArrayOf(0.5f, 1f, 1f))
            .convert(arrayOf(ColorRgb(255, 255, 255)))
        assertArrayEquals(byteArrayOf(-33, -65, -65, 64), output)
    }

}
