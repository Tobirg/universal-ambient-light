package com.vasmarfas.UniversalAmbientLight.common.network

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor

/**
 * Параметры ICE: интенсивность в сотых долях, порог в процентах.
 * Компоненты RGB задают относительный цвет физического белого светодиода.
 */
data class RgbwCalibration(
    val whiteLedIntensity: Int = 180,
    val whiteMixerThreshold: Int = 0,
    val whiteRed: Int = 255,
    val whiteGreen: Int = 255,
    val whiteBlue: Int = 255,
) {
    fun normalized() = RgbwCalibration(
        whiteLedIntensity.coerceIn(0, 500),
        whiteMixerThreshold.coerceIn(0, 100),
        whiteRed.coerceIn(0, 255),
        whiteGreen.coerceIn(0, 255),
        whiteBlue.coerceIn(0, 255),
    )

    companion object {
        /** При обновлении сохраняем прежнюю настройку усиления белого. */
        fun legacyGainToIntensity(gain: Int): Int =
            if (gain <= 0) 0 else (180f * 255f / gain).toInt().coerceIn(50, 500)
    }
}

/**
 * Прямой 32-битный AWa для HyperSerial v11+ и преобразование RGBW с состоянием.
 * Алгоритм смешивания и адаптивного дизеринга основан на HyperHDR
 * InfiniteColorEngineRgbw (MIT, Copyright (c) 2020-2026 awawa-dev):
 * https://github.com/awawa-dev/HyperHDR/blob/master/sources/led-drivers/InfiniteColorEngineRgbw.cpp
 * Вход — существующие 8-битные RGB UAL; старый 24-битный AWa не меняется.
 * Лицензия и уведомление: THIRD_PARTY_LICENSES.md.
 */
class AwaRgbw {
    private class LedState {
        var red = 0f
        var green = 0f
        var blue = 0f
        var errorR = 0f
        var errorG = 0f
        var errorB = 0f
        var flow = 0
        var sentR = 0
        var sentG = 0
        var sentB = 0
        var sentW = 0
    }

    private var mStates = emptyArray<LedState>()
    private var mLastPacketNs = 0L

    /** Порядок вызовов совпадает с записью в порт для сохранения состояния ICE. */
    fun packet(leds: Array<ColorRgb>, config: RgbwCalibration): ByteArray {
        require(leds.isNotEmpty() && leds.size <= 65536)
        if (mStates.size != leds.size) mStates = Array(leds.size) { LedState() }

        val c = config.normalized()
        val now = System.nanoTime()
        val interval = if (mLastPacketNs == 0L) 17f else
            ((now - mLastPacketNs) / 1_000_000f).coerceAtLeast(0f)
        mLastPacketNs = now
        val motionThreshold = (0.01f / 255f) *
            (if (interval > 0f && interval < 17f) interval / 17f else 1f)
        val wr = c.whiteRed / 255f
        val wg = c.whiteGreen / 255f
        val wb = c.whiteBlue / 255f
        // Параметры HyperHDR: интенсивность и порог смешивания белого.
        // Ноль позволяет полностью отключить белый канал.
        val intensity = c.whiteLedIntensity / 100f
        val threshold255 = (c.whiteMixerThreshold / 100f) * 255f
        val invRange = if (255f - threshold255 > 0.00001f)
            1f / (255f - threshold255) else 0f

        val packet = ByteArray(6 + leds.size * 4 + 3)
        packet[0] = 'A'.code.toByte()
        packet[1] = 'W'.code.toByte()
        packet[2] = 'a'.code.toByte()
        val countMinusOne = leds.size - 1
        packet[3] = (countMinusOne shr 8).toByte()
        packet[4] = countMinusOne.toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        var offset = 6
        for (i in leds.indices) {
            val led = leds[i]
            val state = mStates[i]
            val r = led.red / 255f
            val g = led.green / 255f
            val b = led.blue / 255f

            if (state.red != r || state.green != g || state.blue != b) {
                if (max(r, max(g, b)) < 0.5f / 255f) {
                    mStates[i] = LedState()
                } else {
                    val dr = r - state.red
                    val dg = g - state.green
                    val db = b - state.blue
                    val absR = abs(dr)
                    val absG = abs(dg)
                    val absB = abs(db)
                    val mainDiff = if (absR >= absG && absR >= absB) dr else
                        if (absG >= absB) dg else db
                    val flow = when {
                        mainDiff > 0f -> 1
                        mainDiff < 0f -> -1
                        else -> 0
                    }
                    val dither = state.flow == flow && max(absR, max(absG, absB)) >= motionThreshold
                    state.flow = flow

                    val ir = r * 255f + if (dither) state.errorR else 0f
                    val ig = g * 255f + if (dither) state.errorG else 0f
                    val ib = b * 255f + if (dither) state.errorB else 0f
                    val common = min(ir * wr, min(ig * wg, ib * wb))
                    val whiteFactor = if (invRange > 0.00001f)
                        ((common - threshold255) * invRange).coerceIn(0f, 1f)
                        else 1f
                    val white = if (intensity > 0.00001f)
                        quantize(common * whiteFactor / intensity) else 0
                    val rr = ir - white * intensity * wr
                    val gg = ig - white * intensity * wg
                    val bb = ib - white * intensity * wb
                    val qr = quantize(rr)
                    val qg = quantize(gg)
                    val qb = quantize(bb)

                    if (dither) {
                        val movement = dr * dr + dg * dg + db * db
                        val leak = 0.96f - 0.16f * (movement * 50f).coerceIn(0f, 1f)
                        state.errorR = (rr - qr) * leak
                        state.errorG = (gg - qg) * leak
                        state.errorB = (bb - qb) * leak
                    } else {
                        state.errorR = 0f
                        state.errorG = 0f
                        state.errorB = 0f
                    }
                    state.sentR = qr
                    state.sentG = qg
                    state.sentB = qb
                    state.sentW = white
                    state.red = r
                    state.green = g
                    state.blue = b
                }
            } else {
                state.errorR = 0f
                state.errorG = 0f
                state.errorB = 0f
            }
            val encoded = mStates[i]
            packet[offset++] = encoded.sentR.toByte()
            packet[offset++] = encoded.sentG.toByte()
            packet[offset++] = encoded.sentB.toByte()
            packet[offset++] = encoded.sentW.toByte()
        }

        // Контрольная сумма HyperSerial Pico: Fletcher1, Fletcher2, позиционный XOR.
        var sum1 = 0
        var sum2 = 0
        var sumExt = 0
        var position = 0
        for (index in 6 until offset) {
            val value = packet[index].toInt() and 0xFF
            sumExt = (sumExt + (value xor position)) % 255
            sum1 = (sum1 + value) % 255
            sum2 = (sum2 + sum1) % 255
            position = (position + 1) and 0xFF
        }
        packet[offset++] = sum1.toByte()
        packet[offset++] = sum2.toByte()
        packet[offset] = (if (sumExt == 0x41) 0xAA else sumExt).toByte()
        return packet
    }

    private fun quantize(value: Float): Int = floor(value.toDouble() + 0.5).toInt().coerceIn(0, 255)
}
