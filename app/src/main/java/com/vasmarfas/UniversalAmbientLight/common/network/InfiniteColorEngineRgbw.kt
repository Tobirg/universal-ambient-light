package com.vasmarfas.UniversalAmbientLight.common.network

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * RGBW-часть ICE HyperHDR: адаптивное выделение белого и компенсация ошибки.
 * Основано на MIT-реализации awawa-dev/HyperHDR InfiniteColorEngineRgbw.cpp.
 */
internal class InfiniteColorEngineRgbw(
    private val whiteMixerThreshold: Float = 0f,
    private val whiteLedIntensity: Float = 1f,
    whitePointRgb: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    private class LedState {
        var lastR = -1f
        var lastG = -1f
        var lastB = -1f
        var errorR = 0f
        var errorG = 0f
        var errorB = 0f
        var flow = 0
        var sentR: Byte = 0
        var sentG: Byte = 0
        var sentB: Byte = 0
        var sentW: Byte = 0
    }

    private val mWhitePoint = whitePointRgb.copyOf()
    private var mStates = emptyArray<LedState>()

    init {
        require(mWhitePoint.size == 3 && mWhitePoint.all { it.isFinite() && it > 0f })
        require(whiteMixerThreshold.isFinite() && whiteLedIntensity.isFinite())
    }

    fun convert(
        leds: Array<ColorRgb>, intervalMs: Int = 17, brightness: Int = 255,
        precise: FloatArray? = null,
    ): ByteArray {
        require(leds.size in 1..65536) { "LED count must be between 1 and 65536" }
        return ByteArray(leds.size * 4).also {
            convertInto(leds, it, intervalMs = intervalMs, brightness = brightness, precise = precise)
        }
    }

    /**
     * Записывает RGBW в буфер вызывающего кода, сохраняя байты вне кадра.
     * Буфер нельзя одновременно читать или изменять из другого потока.
     */
    @Synchronized
    fun convertInto(
        leds: Array<ColorRgb>,
        output: ByteArray,
        offset: Int = 0,
        intervalMs: Int = 17,
        brightness: Int = 255,
        precise: FloatArray? = null,
    ) {
        require(brightness in 0..255) { "Brightness must be between 0 and 255" }
        require(leds.size in 1..65536) { "LED count must be between 1 and 65536" }
        require(offset >= 0 && offset <= output.size - leds.size * 4) {
            "Output buffer must contain the complete RGBW frame"
        }
        require(precise == null || (precise.size == leds.size * 3 && precise.all { it.isFinite() && it in 0f..255f })) {
            "Precise RGB must contain three finite channels per LED in range 0 to 255"
        }
        if (mStates.size != leds.size) mStates = Array(leds.size) { LedState() }

        val threshold = whiteMixerThreshold.coerceIn(0f, 1f) * 255f
        val inverse = if (255f - threshold > 0.00001f) 1f / (255f - threshold) else 0f
        val motionThreshold = 0.01f / 255f * (if (intervalMs in 1..16) intervalMs / 17f else 1f)
        val intensity = whiteLedIntensity.coerceAtLeast(0f)

        leds.forEachIndexed { index, led ->
            val state = mStates[index]
            val source = index * 3
            val r = (precise?.get(source) ?: led.red.toFloat()) * brightness / (255f * 255f)
            val g = (precise?.get(source + 1) ?: led.green.toFloat()) * brightness / (255f * 255f)
            val b = (precise?.get(source + 2) ?: led.blue.toFloat()) * brightness / (255f * 255f)

            if (r == state.lastR && g == state.lastG && b == state.lastB) {
                state.errorR = 0f
                state.errorG = 0f
                state.errorB = 0f
            } else {
                if (r < 0.5f / 255f && g < 0.5f / 255f && b < 0.5f / 255f) {
                    state.lastR = r
                    state.lastG = g
                    state.lastB = b
                    state.errorR = 0f
                    state.errorG = 0f
                    state.errorB = 0f
                    state.flow = 0
                    state.sentR = 0
                    state.sentG = 0
                    state.sentB = 0
                    state.sentW = 0
                } else {
                    val dr = r - state.lastR
                    val dg = g - state.lastG
                    val db = b - state.lastB
                    val largest = maxOf(abs(dr), abs(dg), abs(db))
                    val direction = when (largest) {
                        abs(dr) -> dr.compareTo(0f)
                        abs(dg) -> dg.compareTo(0f)
                        else -> db.compareTo(0f)
                    }
                    val dithering = state.flow == direction && largest >= motionThreshold
                    state.flow = direction

                    val inputR = r * 255f + if (dithering) state.errorR else 0f
                    val inputG = g * 255f + if (dithering) state.errorG else 0f
                    val inputB = b * 255f + if (dithering) state.errorB else 0f
                    val white = if (intensity > 0.00001f) {
                        val common = minOf(
                            inputR * mWhitePoint[0],
                            inputG * mWhitePoint[1],
                            inputB * mWhitePoint[2]
                        )
                        val factor = if (inverse > 0.00001f) {
                            ((common - threshold) * inverse).coerceIn(0f, 1f)
                        } else 1f
                        (common * factor / intensity).roundToInt().toFloat()
                    } else 0f

                    val targetR = inputR - white * intensity * mWhitePoint[0]
                    val targetG = inputG - white * intensity * mWhitePoint[1]
                    val targetB = inputB - white * intensity * mWhitePoint[2]
                    val roundedR = targetR.roundToInt().coerceIn(0, 255)
                    val roundedG = targetG.roundToInt().coerceIn(0, 255)
                    val roundedB = targetB.roundToInt().coerceIn(0, 255)
                    state.sentR = roundedR.toByte()
                    state.sentG = roundedG.toByte()
                    state.sentB = roundedB.toByte()
                    state.sentW = white.roundToInt().coerceIn(0, 255).toByte()

                    val delta = dr * dr + dg * dg + db * db
                    val leak = 0.96f - 0.16f * (delta * 50f).coerceIn(0f, 1f)
                    state.errorR = if (dithering) (targetR - roundedR) * leak else 0f
                    state.errorG = if (dithering) (targetG - roundedG) * leak else 0f
                    state.errorB = if (dithering) (targetB - roundedB) * leak else 0f
                    state.lastR = r
                    state.lastG = g
                    state.lastB = b
                }
            }

            val sent = mStates[index]
            val pixelOffset = offset + index * 4
            output[pixelOffset] = sent.sentR
            output[pixelOffset + 1] = sent.sentG
            output[pixelOffset + 2] = sent.sentB
            output[pixelOffset + 3] = sent.sentW
        }
    }
}
