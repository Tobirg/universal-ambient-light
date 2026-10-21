package com.vasmarfas.UniversalAmbientLight.common.network

/** Заголовок DDP: смещение и длина измеряются в байтах каналов. */
internal object WledDdpHeader {
    const val SIZE = 10

    fun write(packet: ByteArray, offset: Int, length: Int, rgbw: Boolean, last: Boolean) {
        require(packet.size >= SIZE + length)
        require(offset >= 0 && length in 0..0xFFFF)
        packet[0] = (0x40 or if (last) 1 else 0).toByte()
        packet[1] = 0
        packet[2] = (if (rgbw) 0x1B else 0x0B).toByte()
        packet[3] = 1
        packet[4] = (offset ushr 24).toByte()
        packet[5] = (offset ushr 16).toByte()
        packet[6] = (offset ushr 8).toByte()
        packet[7] = offset.toByte()
        packet[8] = (length ushr 8).toByte()
        packet[9] = length.toByte()
    }
}
