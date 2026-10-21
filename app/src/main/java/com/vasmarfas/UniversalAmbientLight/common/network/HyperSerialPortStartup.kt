package com.vasmarfas.UniversalAmbientLight.common.network

/** Последовательность управления линиями USB при подключении HyperSerial. */
internal object HyperSerialPortStartup {
    fun usesNativeUsb(vendorId: Int, productId: Int): Boolean =
        (vendorId == 0x2e8a && (productId == 0x000a || productId == 0x0009)) ||
            (vendorId == 0x303a && productId == 0x80c2) ||
            (vendorId == 0x1106 && productId == 0x3483)

    fun initialize(
        vendorId: Int,
        productId: Int,
        setLines: (dtr: Boolean, rts: Boolean) -> Unit,
        sleep: (milliseconds: Long) -> Unit,
    ) {
        if (usesNativeUsb(vendorId, productId)) {
            // Для нативного USB сохраняем линии без импульса сброса.
            setLines(true, true)
            return
        }

        // Для мостов USB-UART выполняем импульс сброса независимо от Android API.
        setLines(true, false)
        try {
            sleep(50)
            setLines(false, true)
            sleep(150)
            setLines(false, false)
            sleep(100)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
