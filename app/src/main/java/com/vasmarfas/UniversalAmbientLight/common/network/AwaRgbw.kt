package com.vasmarfas.UniversalAmbientLight.common.network

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor

/**
 * ICE parameters exposed in UAL:
 * intensity in hundredths (180 -> 1.80), threshold in percent (0 -> 0.0).
 * The RGB components describe the relative colour of the physical white emitter.
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
        /** Preserve the previous AWa mixer settings on upgrade. 0 means W disabled. */
        fun legacyGainToIntensity(gain: Int): Int =
            if (gain <= 0) 0 else (180f * 255f / gain).toInt().coerceIn(50, 500)
    }
}

/**
 * HyperSerial v11+ direct 32-bit AWa output and stateful ICE RGBW conversion.
 * The ICE mixer and motion-adaptive error diffusion follow HyperHDR's
 * InfiniteColorEngineRgbw (MIT, copyright 2020-2026 awawa-dev).
 * The input here is UAL's existing 8-bit (nonlinear) RGB output.
 * No change to the legacy 24-bit Awa output is required.
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

    private var states = emptyArray<LedState>()
    private var lastPacketNs = 0L

    /** Caller serializes packet() with port writes: the temporal ICE state must be ordered. */
    fun packet(leds: Array<ColorRgb>, config: RgbwCalibration): ByteArray {
        require(leds.isNotEmpty() && leds.size <= 4096)
        if (states.size != leds.size) states = Array(leds.size) { LedState() }

        val c = config.normalized()
        val now = System.nanoTime()
        val interval = if (lastPacketNs == 0L) 17f else
            ((now - lastPacketNs) / 1_000_000f).coerceAtLeast(0f)
        lastPacketNs = now
        val motionThreshold = (0.01f / 255f) *
            (if (interval > 0f && interval < 17f) interval / 17f else 1f)
        val wr = c.whiteRed / 255f
        val wg = c.whiteGreen / 255f
        val wb = c.whiteBlue / 255f
        // HyperHDR parameters: intensity (0.5..5.0), white mixing threshold (0..1).
        // The optional intensity=0 keeps the previous UAL "white off" setting.
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
            val state = states[i]
            val r = led.red / 255f
            val g = led.green / 255f
            val b = led.blue / 255f

            if (state.red != r || state.green != g || state.blue != b) {
                if (max(r, max(g, b)) < 0.5f / 255f) {
                    states[i] = LedState()
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
            val encoded = states[i]
            packet[offset++] = encoded.sentR.toByte()
            packet[offset++] = encoded.sentG.toByte()
            packet[offset++] = encoded.sentB.toByte()
            packet[offset++] = encoded.sentW.toByte()
        }

        // HyperSerial Pico: Fletcher1, Fletcher2, positional XOR checksum.
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
