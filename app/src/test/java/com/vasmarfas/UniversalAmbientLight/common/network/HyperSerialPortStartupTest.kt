package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperSerialPortStartupTest {
    @Test
    fun `known native USB boards avoid reset pulses`() {
        for ((vendor, product) in listOf(
            0x2e8a to 0x000a, 0x2e8a to 0x0009,
            0x303a to 0x80c2, 0x1106 to 0x3483,
        )) {
            val events = mutableListOf<String>()
            assertTrue(HyperSerialPortStartup.usesNativeUsb(vendor, product))
            HyperSerialPortStartup.initialize(
                vendor, product,
                { dtr, rts -> events.add("$dtr,$rts") },
                { delay -> events.add("sleep:$delay") },
            )
            assertEquals(listOf("true,true"), events)
        }
    }

    @Test
    fun `unknown USB UART follows timed reset sequence`() {
        val events = mutableListOf<String>()
        assertFalse(HyperSerialPortStartup.usesNativeUsb(0x1a86, 0x7523))
        HyperSerialPortStartup.initialize(
            0x1a86, 0x7523,
            { dtr, rts -> events.add("$dtr,$rts") },
            { delay -> events.add("sleep:$delay") },
        )
        assertEquals(
            listOf("true,false", "sleep:50", "false,true", "sleep:150", "false,false", "sleep:100"),
            events,
        )
    }

    @Test
    fun `interrupted reset does not continue toggling lines`() {
        val events = mutableListOf<String>()
        HyperSerialPortStartup.initialize(
            0x10c4, 0xea60,
            { dtr, rts -> events.add("$dtr,$rts") },
            { throw InterruptedException("test") },
        )
        assertEquals(listOf("true,false"), events)
        Thread.interrupted() // Clear interrupt flag for the test runner.
    }
}
