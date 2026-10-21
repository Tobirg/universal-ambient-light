package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertEquals
import org.junit.Test

class HyperSerialColorModeTest {
    @Test
    fun `default mode is RGB`() {
        assertEquals(HyperSerialColorMode.RGB, HyperSerialColorMode.fromPreference(""))
        assertEquals(HyperSerialColorMode.RGB, HyperSerialColorMode.fromPreference("rgb"))
    }

    @Test
    fun `legacy RGBW selection is preserved`() {
        assertEquals(HyperSerialColorMode.RGBW, HyperSerialColorMode.fromPreference("awa-rgbw"))
        assertEquals(HyperSerialColorMode.RGBW, HyperSerialColorMode.fromPreference("RGBW"))
    }

    @Test
    fun `ICE mode can be parsed independently of transport`() {
        assertEquals(HyperSerialColorMode.ICE, HyperSerialColorMode.fromPreference("ice"))
        assertEquals(HyperSerialColorMode.ICE, HyperSerialColorMode.fromPreference("awa-ice"))
    }
}
