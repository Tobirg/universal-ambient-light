package com.vasmarfas.UniversalAmbientLight.common.network

/**
 * Кодировщики пакетов Adalight и HyperSerial.
 * AWA использует трёхбайтовую контрольную сумму HyperHDR.
 */
internal object AdalightPacketEncoder {
    /** Параметры калибровки белого канала прошивки HyperSerial. */
    data class WhiteCalibration(val limit: Int, val red: Int, val green: Int, val blue: Int) {
        init {
            require(listOf(limit, red, green, blue).all { it in 0..255 })
        }
    }

    /** Транспортный кадр: RGBW-каналы подготавливаются на уровне обработки цвета. */
    sealed class AwaFrame {
        class Rgb(val leds: Array<ColorRgb>, val calibration: WhiteCalibration? = null) : AwaFrame()
        class Rgbw(val channels: ByteArray) : AwaFrame()
    }

    /**
     * Сохраняет RGBW при чёрном кадре сглаживания, чтобы очистка не переключала
     * формат контроллера. Обычные цветные RGB-кадры используют прежний протокол.
     */
    fun frameForSmoothedRgb(leds: Array<ColorRgb>, previous: AwaFrame?): AwaFrame {
        val rgbw = previous as? AwaFrame.Rgbw
        return if (rgbw != null && rgbw.channels.size == leds.size * 4 &&
            leds.all { it.red == 0 && it.green == 0 && it.blue == 0 }
        ) {
            AwaFrame.Rgbw(ByteArray(rgbw.channels.size))
        } else {
            AwaFrame.Rgb(Array(leds.size) { leds[it].clone() })
        }
    }

    fun createAwaPacket(frame: AwaFrame): ByteArray = when (frame) {
        is AwaFrame.Rgb -> createAwaPacket(frame.leds, frame.calibration)
        is AwaFrame.Rgbw -> createAwaRgbwPacket(frame.channels)
    }

    fun createAdaPacket(leds: Array<ColorRgb>): ByteArray {
        val ledCount = leds.size
        require(ledCount in 1..65536) { "LED count must be between 1 and 65536" }
        val dataSize = ledCount * 3
        val packet = ByteArray(6 + dataSize)

        // Заголовок
        packet[0] = 'A'.code.toByte()
        packet[1] = 'd'.code.toByte()
        packet[2] = 'a'.code.toByte()

        val ledCountMinusOne = ledCount - 1
        packet[3] = ((ledCountMinusOne shr 8) and 0xFF).toByte()
        packet[4] = (ledCountMinusOne and 0xFF).toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        // Данные RGB
        var offset = 6
        for (led in leds) {
            packet[offset++] = led.red.toByte()
            packet[offset++] = led.green.toByte()
            packet[offset++] = led.blue.toByte()
        }

        return packet
    }

    fun createAwaPacket(leds: Array<ColorRgb>, calibration: WhiteCalibration? = null): ByteArray {
        val ledCount = leds.size
        require(ledCount in 1..65536) { "LED count must be between 1 and 65536" }
        val dataSize = ledCount * 3 + if (calibration != null) 4 else 0
        // Размер контрольной суммы — 3 байта (Флетчер)
        val packet = ByteArray(6 + dataSize + 3)

        packet[0] = 'A'.code.toByte()
        packet[1] = 'w'.code.toByte()
        packet[2] = if (calibration != null) 'A'.code.toByte() else 'a'.code.toByte()

        val ledCountMinusOne = ledCount - 1
        packet[3] = ((ledCountMinusOne shr 8) and 0xFF).toByte()
        packet[4] = (ledCountMinusOne and 0xFF).toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        var offset = 6
        for (led in leds) {
            packet[offset++] = led.red.toByte()
            packet[offset++] = led.green.toByte()
            packet[offset++] = led.blue.toByte()
        }

        if (calibration != null) {
            packet[offset++] = calibration.limit.toByte()
            packet[offset++] = calibration.red.toByte()
            packet[offset++] = calibration.green.toByte()
            packet[offset++] = calibration.blue.toByte()
        }

        writeAwaChecksum(packet, 6, dataSize)

        return packet
    }


    /**
     * Формирует пакет HyperHDR Infinite Color Engine: AWA и четыре канала на LED.
     * Преобразование цвета выполняется до вызова, порядок каналов сохраняется.
     */
    fun createAwaRgbwPacket(rgbw: ByteArray): ByteArray {
        require(rgbw.isNotEmpty() && rgbw.size % 4 == 0) {
            "RGBW payload must contain exactly four bytes per LED"
        }
        val ledCount = rgbw.size / 4
        require(ledCount in 1..65536) { "LED count must be between 1 and 65536" }
        val packet = ByteArray(6 + rgbw.size + 3)
        packet[0] = 'A'.code.toByte()
        packet[1] = 'W'.code.toByte()
        packet[2] = 'a'.code.toByte()
        val count = ledCount - 1
        packet[3] = (count shr 8).toByte()
        packet[4] = count.toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()
        rgbw.copyInto(packet, 6)
        writeAwaChecksum(packet, 6, rgbw.size)

        return packet
    }

    /** Контрольная сумма HyperHDR: Fletcher-16 и XOR по позициям байтов полезной нагрузки. */
    private fun writeAwaChecksum(packet: ByteArray, payloadOffset: Int, payloadLength: Int) {
        var sum1 = 0
        var sum2 = 0
        var extended = 0
        for (i in 0 until payloadLength) {
            val value = packet[payloadOffset + i].toInt() and 0xff
            extended = (extended + (value xor (i and 0xff))) % 255
            sum1 = (sum1 + value) % 255
            sum2 = (sum2 + sum1) % 255
        }
        val checksumOffset = payloadOffset + payloadLength
        packet[checksumOffset] = sum1.toByte()
        packet[checksumOffset + 1] = sum2.toByte()
        packet[checksumOffset + 2] = if (extended == 0x41) 0xaa.toByte() else extended.toByte()
    }
}
