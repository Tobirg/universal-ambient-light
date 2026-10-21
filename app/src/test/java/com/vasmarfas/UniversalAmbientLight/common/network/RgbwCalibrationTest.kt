package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Test

class RgbwCalibrationTest {
    @Test(expected = IllegalArgumentException::class)
    fun `calibration rejects a nonfinite intensity`() {
        RgbwCalibration(whiteIntensity = Float.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calibration rejects an out of range threshold`() {
        RgbwCalibration(whiteThreshold = 1.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `calibration rejects a zero white point component`() {
        RgbwCalibration(whiteRed = 0f)
    }
}
