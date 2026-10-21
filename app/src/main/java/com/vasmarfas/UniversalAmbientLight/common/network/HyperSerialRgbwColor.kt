package com.vasmarfas.UniversalAmbientLight.common.network

/**
 * Выделяет общий белый компонент из RGB без изменения суммарного значения каналов.
 * Порядок каналов соответствует транспортному формату HyperSerial: R, G, B, W.
 */
internal object HyperSerialRgbwColor {
    fun convert(leds: Array<ColorRgb>): ByteArray {
        require(leds.isNotEmpty() && leds.size <= 65536) {
            "LED count must be between 1 and 65536"
        }
        val channels = ByteArray(leds.size * 4)
        for (i in leds.indices) {
            val led = leds[i]
            writePixel(channels, i * 4, led.red, led.green, led.blue)
        }
        return channels
    }

    fun white(red: Int, green: Int, blue: Int): Int = minOf(red, green, blue)

    fun writePixel(channels: ByteArray, offset: Int, red: Int, green: Int, blue: Int) {
        val white = white(red, green, blue)
        channels[offset] = (red - white).toByte()
        channels[offset + 1] = (green - white).toByte()
        channels[offset + 2] = (blue - white).toByte()
        channels[offset + 3] = white.toByte()
    }
}
