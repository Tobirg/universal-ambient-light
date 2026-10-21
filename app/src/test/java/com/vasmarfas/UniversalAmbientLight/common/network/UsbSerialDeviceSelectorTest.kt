package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbSerialDeviceSelectorTest {
    @Test
    fun `selects first device when no device has permission`() {
        assertEquals("first", UsbSerialDeviceSelector.select(listOf("first", "second")) { false })
    }

    @Test
    fun `selects permitted device instead of first unpermitted device`() {
        assertEquals("second", UsbSerialDeviceSelector.select(listOf("first", "second")) {
            it == "second"
        })
    }

    @Test
    fun `selects first permitted device when multiple devices have permission`() {
        assertEquals("second", UsbSerialDeviceSelector.select(listOf("first", "second", "third")) {
            it != "first"
        })
    }

    @Test
    fun `selects sole device when permission is granted`() {
        assertEquals("only", UsbSerialDeviceSelector.select(listOf("only")) { true })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty device list`() {
        UsbSerialDeviceSelector.select(emptyList<String>()) { true }
    }
}
