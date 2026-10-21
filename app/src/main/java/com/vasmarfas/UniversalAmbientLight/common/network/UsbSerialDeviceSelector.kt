package com.vasmarfas.UniversalAmbientLight.common.network

/**
 * Выбирает первый USB-Serial драйвер с уже выданным разрешением.
 * Если разрешений нет, сохраняет прежний выбор первого обнаруженного драйвера.
 */
internal object UsbSerialDeviceSelector {
    fun <T> select(devices: List<T>, hasPermission: (T) -> Boolean): T {
        require(devices.isNotEmpty()) { "No USB serial devices found" }
        return devices.firstOrNull(hasPermission) ?: devices.first()
    }
}
