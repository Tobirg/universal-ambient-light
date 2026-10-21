package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedColorInterpolatorTest {
    private fun gray(value: Int) = arrayOf(ColorRgb(value, value, value))

    @Test
    fun `linear interpolation keeps a fractional dark transition`() {
        val interpolator = LedColorInterpolator()
        interpolator.setTarget(gray(0), 0, 200)
        interpolator.setTarget(gray(1), 0, 200)
        assertArrayEquals(floatArrayOf(0.25f, 0.25f, 0.25f), interpolator.snapshot(50), 0.0001f)
    }

    @Test
    fun `identical input does not postpone the transition deadline`() {
        val interpolator = LedColorInterpolator()
        interpolator.setTarget(gray(0), 0, 200)
        interpolator.setTarget(gray(1), 0, 200)
        for (time in 10L..200L step 10) interpolator.setTarget(gray(1), time, 200)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), interpolator.snapshot(200), 0f)
    }

    @Test
    fun `linear result does not depend on number of timer ticks`() {
        val frequent = LedColorInterpolator()
        val sparse = LedColorInterpolator()
        for (interpolator in listOf(frequent, sparse)) {
            interpolator.setTarget(gray(0), 0, 200)
            interpolator.setTarget(gray(100), 0, 200)
        }
        for (time in 10L..90L step 10) frequent.snapshot(time)
        assertArrayEquals(sparse.snapshot(100), frequent.snapshot(100), 0.0001f)
    }

    @Test
    fun `retargeting starts at the current fractional position`() {
        val interpolator = LedColorInterpolator()
        interpolator.setTarget(gray(0), 0, 200)
        interpolator.setTarget(gray(100), 0, 200)
        interpolator.setTarget(gray(0), 100, 200)
        assertArrayEquals(floatArrayOf(25f, 25f, 25f), interpolator.snapshot(200), 0.0001f)
    }

    @Test
    fun `output snapshots do not alias interpolation state`() {
        val interpolator = LedColorInterpolator()
        interpolator.setTarget(gray(10), 0, 200)
        interpolator.snapshot(0).fill(255f)
        assertArrayEquals(floatArrayOf(10f, 10f, 10f), interpolator.snapshot(1), 0f)
    }

    @Test
    fun `disabled smoothing snaps an existing target to its final value`() {
        val interpolator = LedColorInterpolator()
        interpolator.setTarget(gray(0), 0, 200)
        interpolator.setTarget(gray(100), 0, 200)
        interpolator.setTarget(gray(100), 50, 0)
        assertArrayEquals(floatArrayOf(100f, 100f, 100f), interpolator.snapshot(50), 0f)
    }

    @Test
    fun `resizing initializes all channels without stale state`() {
        val interpolator = LedColorInterpolator("hybrid")
        interpolator.setTarget(gray(0), 0, 200)
        interpolator.setTarget(gray(255), 0, 200)
        interpolator.snapshot(100)
        interpolator.setTarget(arrayOf(ColorRgb(1, 2, 3), ColorRgb(4, 5, 6)), 100, 200)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), interpolator.snapshot(100), 0f)
    }

    @Test
    fun `exponential interpolation is consistent across tick frequencies`() {
        val frequent = LedColorInterpolator("exponential")
        val sparse = LedColorInterpolator("exponential")
        for (interpolator in listOf(frequent, sparse)) {
            interpolator.setTarget(gray(0), 0, 200)
            interpolator.setTarget(gray(100), 0, 200)
        }
        for (time in 10L..190L step 10) frequent.snapshot(time)
        assertArrayEquals(sparse.snapshot(200), frequent.snapshot(200), 0.001f)
    }

    @Test
    fun `exponential transition reaches the exact target after settling`() {
        val interpolator = LedColorInterpolator("exponential")
        interpolator.setTarget(gray(0), 0, 200)
        interpolator.setTarget(gray(1), 0, 200)
        interpolator.snapshot(3000)
        assertTrue(interpolator.settled)
    }

    @Test
    fun `YUV interpolation ends at the exact original RGB color`() {
        val interpolator = LedColorInterpolator("yuv")
        interpolator.setTarget(arrayOf(ColorRgb(255, 0, 0)), 0, 200)
        interpolator.setTarget(arrayOf(ColorRgb(0, 0, 255)), 0, 200)
        assertArrayEquals(floatArrayOf(0f, 0f, 255f), interpolator.snapshot(200), 0f)
    }

    @Test
    fun `YUV luminance limit prevents an abrupt brightness jump`() {
        val interpolator = LedColorInterpolator("yuv", 255f)
        interpolator.setTarget(gray(0), 0, 100)
        interpolator.setTarget(gray(255), 0, 100)
        assertArrayEquals(floatArrayOf(25.5f, 25.5f, 25.5f), interpolator.snapshot(100), 0.001f)
    }

    @Test
    fun `limited YUV transition eventually reaches its target`() {
        val interpolator = LedColorInterpolator("yuv", 255f)
        interpolator.setTarget(gray(0), 0, 100)
        interpolator.setTarget(gray(255), 0, 100)
        for (time in 100L..1100L step 100) interpolator.snapshot(time)
        assertArrayEquals(floatArrayOf(255f, 255f, 255f), interpolator.snapshot(1100), 0f)
    }

    @Test
    fun `hybrid interpolation remains finite after a long output stall`() {
        val interpolator = LedColorInterpolator("hybrid")
        interpolator.setTarget(gray(0), 0, 1)
        interpolator.setTarget(gray(255), 0, 1)
        assertTrue(interpolator.snapshot(60000).all { it.isFinite() && it in 0f..255f })
    }

    @Test
    fun `hybrid interpolation settles after reversing direction`() {
        val interpolator = LedColorInterpolator("hybrid")
        interpolator.setTarget(gray(0), 0, 200)
        interpolator.setTarget(gray(255), 0, 200)
        interpolator.snapshot(100)
        interpolator.setTarget(gray(0), 100, 200)
        for (time in 110L..3000L step 10) interpolator.snapshot(time)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), interpolator.snapshot(3000), 0f)
    }

    @Test
    fun `an unchanged target reports no new transition`() {
        val interpolator = LedColorInterpolator()
        interpolator.setTarget(gray(1), 0, 200)
        assertFalse(interpolator.setTarget(gray(1), 10, 200))
    }

    @Test
    fun `an unknown algorithm falls back to linear RGB`() {
        val interpolator = LedColorInterpolator("unknown")
        interpolator.setTarget(gray(0), 0, 200)
        interpolator.setTarget(gray(100), 0, 200)
        assertEquals(50f, interpolator.snapshot(100)[0], 0.001f)
    }
    @Test
    fun `fractional smoothing reaches ICE without intermediate rounding`() {
        val interpolator = LedColorInterpolator()
        interpolator.setTarget(gray(100), 0, 200)
        interpolator.setTarget(gray(101), 0, 200)
        val channels = InfiniteColorEngineRgbw().convert(gray(100), precise = interpolator.snapshot(80))
        assertArrayEquals(byteArrayOf(60, 60, 60, 40), channels)
    }

}
