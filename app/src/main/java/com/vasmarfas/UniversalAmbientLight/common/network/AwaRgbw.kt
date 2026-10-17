package com.vasmarfas.UniversalAmbientLight.common.network

import kotlin.math.min

/**
 * Простой управляемый RGB -> RGBW для SK6812.
 * Это не ICE: нет временного дизеринга и оценки белой точки по истории кадров.
 * Откалиброванные коэффициенты описывают вклад белого диода в каналы RGB.
 */
data class RgbwCalibration(
    val whiteGain: Int = 255,
    val whiteRed: Int = 255,
    val whiteGreen: Int = 255,
    val whiteBlue: Int = 255,
) {
    fun normalized() = RgbwCalibration(
        whiteGain.coerceIn(0, 255),
        whiteRed.coerceIn(0, 255),
        whiteGreen.coerceIn(0, 255),
        whiteBlue.coerceIn(0, 255),
    )
}

object AwaRgbw {
    /**
     * AWA v11+ прямой 32-битный режим: 'AWa', LED count-1, CRC, RGBW, Fletcher.
     * Байты W идут после RGB; прошивка сама перекладывает их в аппаратный GRBW.
     */
    fun packet(leds: Array<ColorRgb>, config: RgbwCalibration): ByteArray {
        require(leds.isNotEmpty() && leds.size <= 4096)
        val c = config.normalized()
        val packet = ByteArray(6 + leds.size * 4 + 3)
        packet[0] = 'A'.code.toByte()
        packet[1] = 'W'.code.toByte()
        packet[2] = 'a'.code.toByte()
        val countMinusOne = leds.size - 1
        packet[3] = (countMinusOne shr 8).toByte()
        packet[4] = countMinusOne.toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        var pos = 6
        var fletcher1 = 0
        var fletcher2 = 0
        var fletcherExt = 0
        var position = 0

        fun add(byte: Int) {
            val value = byte and 0xff
            packet[pos++] = value.toByte()
            fletcher1 = (fletcher1 + value) % 255
            fletcher2 = (fletcher2 + fletcher1) % 255
            fletcherExt = (fletcherExt + (value xor position)) % 255
            position = (position + 1) and 255
        }

        for (led in leds) {
            val red = led.red
            val green = led.green
            val blue = led.blue
            val wRed = c.whiteRed
            val wGreen = c.whiteGreen
            val wBlue = c.whiteBlue

            // Белый канал нельзя зажигать настолько, чтобы его вклад превышал
            // любой из исходных RGB каналов. 0 означает отсутствие вклада.
            var white = min(red, min(green, blue)) * c.whiteGain / 255
            if (wRed > 0) white = min(white, red * 255 / wRed)
            if (wGreen > 0) white = min(white, green * 255 / wGreen)
            if (wBlue > 0) white = min(white, blue * 255 / wBlue)

            add((red - white * wRed / 255).coerceIn(0, 255))
            add((green - white * wGreen / 255).coerceIn(0, 255))
            add((blue - white * wBlue / 255).coerceIn(0, 255))
            add(white)
        }

        packet[pos++] = fletcher1.toByte()
        packet[pos++] = fletcher2.toByte()
        packet[pos] = (if (fletcherExt == 0x41) 0xaa else fletcherExt).toByte()
        return packet
    }
}
