package com.vasmarfas.UniversalAmbientLight.common.network

/** Общая калибровка ICE для HyperSerial и WLED; коэффициенты не зависят от транспорта. */
data class RgbwCalibration(
    val whiteThreshold: Float = 0f,
    val whiteIntensity: Float = 1f,
    val whiteRed: Float = 1f,
    val whiteGreen: Float = 1f,
    val whiteBlue: Float = 1f,
) {
    init {
        require(whiteThreshold.isFinite() && whiteThreshold in 0f..1f)
        require(whiteIntensity.isFinite() && whiteIntensity in 0f..2f)
        require(listOf(whiteRed, whiteGreen, whiteBlue).all { it.isFinite() && it in 0.01f..2f })
    }
}
