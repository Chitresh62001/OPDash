package com.opdash.ble

object PairingManager {

    private const val COMMAND_PAIR = 0x20

    fun buildPairPacket(code: String): ByteArray {

        require(code.length == 6) {
            "Dashboard code must be exactly 6 characters."
        }

        val packet = ByteArray(20)

        packet[0] = COMMAND_PAIR.toByte()

        for (i in 0 until 6) {
            packet[i + 1] = code[i].code.toByte()
        }

        val crcInput = packet.copyOfRange(0, 18)

        val crc = CRC16.calculate(crcInput)

        packet[18] = crc[0]
        packet[19] = crc[1]

        return packet
    }

}