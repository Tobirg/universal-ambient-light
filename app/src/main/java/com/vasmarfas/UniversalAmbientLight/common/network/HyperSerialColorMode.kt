package com.vasmarfas.UniversalAmbientLight.common.network

/**
 * Цветовой режим HyperSerial отделён от транспортного протокола AWA.
 * Старое значение awa-rgbw поддерживается для ранее сохранённых настроек.
 */
internal enum class HyperSerialColorMode {
    RGB,
    RGBW,
    ICE;

    companion object {
        fun fromPreference(value: String): HyperSerialColorMode = when (value.lowercase()) {
            "rgbw", "awa-rgbw" -> RGBW
            "ice", "awa-ice" -> ICE
            else -> RGB
        }
    }
}
