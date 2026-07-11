package com.opdash.protocol

import java.nio.charset.StandardCharsets

data class K1GPacket(
    val sequence: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as K1GPacket
        if (sequence != other.sequence) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sequence
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object K1GPacketBuilder {

    /**
     * Builds a standard K1G packet frame.
     * @param elementCount Number of TLVs in this packet (sent at byte offset 2-3).
     * @param payload The concatenated TLV payload bytes.
     * @return The final byte array.
     */
    fun buildPacket(elementCount: Int, payload: ByteArray): ByteArray {
        val header = ByteArray(17)
        val totalLength = header.size + payload.size + 2 // Header + Payload + 2 bytes CRC

        // Bytes 0-1: Total length of entire packet (big-endian)
        header[0] = (totalLength shr 8).toByte()
        header[1] = (totalLength and 0xFF).toByte()

        // Bytes 2-3: Element count / signal count (big-endian)
        header[2] = (elementCount shr 8).toByte()
        header[3] = (elementCount and 0xFF).toByte()

        // Bytes 4-7: Padding / constant zeroes
        header[4] = 0x00
        header[5] = 0x00
        header[6] = 0x00
        header[7] = 0x00

        // Bytes 8-9: Constant 02 01 (Some firmwares use 01 01)
        header[8] = 0x02
        header[9] = 0x01

        // Bytes 10-11: Length of identifier "K1G " (0x0004)
        header[10] = 0x00
        header[11] = 0x04

        // Bytes 12-15: ASCII bytes of "K1G " (4B 31 47 20)
        header[12] = 0x4B.toByte()
        header[13] = 0x31.toByte()
        header[14] = 0x47.toByte()
        header[15] = 0x20.toByte()
        
        // OR Try without the space if "K1G " fails: 4B 31 47 00 and Length 4
        // Original RE app usually sends "K1G " with a trailing space.

        // Byte 16: Sequence counter placeholder (to be replaced at send-time)
        header[16] = 0x00

        // Return header + payload + 2 bytes placeholder for CRC
        return header + payload + byteArrayOf(0x00, 0x00)
    }

    /**
     * Constructs a single TLV (Tag-Length-Value) block.
     * @param tag Tag identifier (e.g. 0x0517 for music active)
     * @param value Byte array representing the value.
     */
    fun buildTlv(tag: Int, value: ByteArray): ByteArray {
        val tlv = ByteArray(4 + value.size)
        // Tag (2 bytes)
        tlv[0] = (tag shr 8).toByte()
        tlv[1] = (tag and 0xFF).toByte()
        // Length (2 bytes)
        tlv[2] = (value.size shr 8).toByte()
        tlv[3] = (value.size and 0xFF).toByte()
        // Value
        System.arraycopy(value, 0, tlv, 4, value.size)
        return tlv
    }

    /**
     * Builds a TLV block for String values (converts to ASCII bytes).
     */
    fun buildStringTlv(tag: Int, value: String): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        return buildTlv(tag, bytes)
    }

    /**
     * Builds a TLV block for Integer values with a specific byte length.
     */
    fun buildIntTlv(tag: Int, value: Int, length: Int): ByteArray {
        val bytes = ByteArray(length)
        for (i in 0 until length) {
            bytes[length - 1 - i] = (value shr (i * 8)).toByte()
        }
        return buildTlv(tag, bytes)
    }

    /**
     * Prepares the music active / inactive packet.
     * Active payload: "0517000155" (music playing) or "05170001AA" (music paused/stopped).
     * Element count is hardcoded to 2 in RE app.
     */
    /**
     * Prepares the music active / inactive packet.
     * Active payload: "0517000155" (music playing) or "05170001AA" (music paused/stopped).
     * Element count is hardcoded to 1.
     */
    fun buildMusicActivePacket(isActive: Boolean): ByteArray {
        val value = byteArrayOf(if (isActive) 0x55.toByte() else 0xAA.toByte())
        val tlv = buildTlv(0x0517, value)
        return buildPacket(1, tlv)
    }

    /**
     * Prepares music play/pause control feedback packet.
     * Playing payload: "0519000155", Paused payload: "05190001AA".
     */
    fun buildMusicPlayStatePacket(isPlaying: Boolean): ByteArray {
        val value = byteArrayOf(if (isPlaying) 0x55.toByte() else 0xAA.toByte())
        val tlv = buildTlv(0x0519, value)
        return buildPacket(1, tlv)
    }

    /**
     * Prepares music track info metadata packet (0x050D tag).
     * Value layout: [album title] + \0 + [song title] + \0 + [artist name]
     * Truncated at 19 bytes per string.
     */
    fun buildMusicMetadataPacket(title: String, artist: String, album: String): ByteArray {
        val cleanTitle = if (title.length > 20) title.substring(0, 20) else title
        val cleanArtist = if (artist.length > 20) artist.substring(0, 20) else artist
        val cleanAlbum = if (album.length > 20) album.substring(0, 20) else album

        val titleBytes = cleanTitle.toByteArray(StandardCharsets.US_ASCII)
        val artistBytes = cleanArtist.toByteArray(StandardCharsets.US_ASCII)
        val albumBytes = cleanAlbum.toByteArray(StandardCharsets.US_ASCII)

        // Concatenate with null separators: album + \0 + title + \0 + artist
        val value = ByteArray(albumBytes.size + 1 + titleBytes.size + 1 + artistBytes.size)
        var pos = 0
        System.arraycopy(albumBytes, 0, value, pos, albumBytes.size)
        pos += albumBytes.size
        value[pos++] = 0x00
        System.arraycopy(titleBytes, 0, value, pos, titleBytes.size)
        pos += titleBytes.size
        value[pos++] = 0x00
        System.arraycopy(artistBytes, 0, value, pos, artistBytes.size)

        val tlv = buildTlv(0x050D, value)
        return buildPacket(1, tlv)
    }

    /**
     * Prepares the periodic telemetry status packet.
     * Contains 6 status fields:
     * 1. Cellular Signal: Tag 0x0608 (1 byte, 0..4 signal strength)
     * 2. Battery Percentage: Tag 0x0604 (1 byte, batteryLevel + 100)
     * 3. Charging Status: Tag 0x060F (1 byte, 1 if charging, 0 if not)
     * 4. GPS Status: Tag 0x0603 (1 byte, 1 if active, 0 if not)
     * 5. Volume: Tag 0x054C (1 byte, standard volume e.g. 0x10..0x1A or 0x11)
     * 6. Temp/Weather: Tag 0x0521 (1 byte, mock weather details)
     */
    fun buildTelemetryPacket(
        cellSignalStrength: Int, // 0..4
        batteryLevel: Int, // 0..100
        isCharging: Boolean,
        isGpsActive: Boolean,
        volume: Int, // 0..10
        weatherTemp: Int // temp in Celsius
    ): ByteArray {
        val cellTlv = buildTlv(0x0608, byteArrayOf(cellSignalStrength.toByte()))
        val battTlv = buildTlv(0x0604, byteArrayOf((batteryLevel + 100).toByte()))
        val chargeTlv = buildTlv(0x060F, byteArrayOf(if (isCharging) 0x01 else 0x00))
        val gpsTlv = buildTlv(0x0603, byteArrayOf(if (isGpsActive) 0x01 else 0x00))

        // Volume scale mapping (simple volume status representation)
        val volByte = (0x10 + volume.coerceIn(0, 10)).toByte()
        val volTlv = buildTlv(0x054C, byteArrayOf(volByte))

        // Temperature representation
        val tempTlv = buildTlv(0x0521, byteArrayOf(weatherTemp.toByte()))

        // Concatenate all 6 TLVs
        val payload = cellTlv + battTlv + chargeTlv + gpsTlv + volTlv + tempTlv
        return buildPacket(6, payload)
    }

    /**
     * Builds Turn-by-Turn navigation data packet (Group 0x05).
     */
    fun buildNavigationPacket(
        roadName: String,
        primaryManeuver: Int, // turn ID
        distanceToTurn: Int, // e.g. 250
        distanceUnit: Int, // 1 = m, 2 = km, 3 = ft, 4 = miles
        etaHours: Int,
        etaMinutes: Int,
        totalDistanceRemaining: Int,
        totalDistanceUnit: Int
    ): ByteArray {
        var elementCount = 0
        var payload = byteArrayOf()

        // 1. Road Name: Category 0x05, Tag 0x01. Needs a null terminator.
        if (roadName.isNotEmpty()) {
            val cleanRoad = if (roadName.length > 18) roadName.substring(0, 18) else roadName
            val roadBytes = cleanRoad.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0x00)
            payload += buildTlv(0x0501, roadBytes)
            elementCount++
        }

        // 2. Primary Maneuver/Turn Icon: Category 0x05, Tag 0x02 (1 byte value)
        payload += buildTlv(0x0502, byteArrayOf(primaryManeuver.toByte()))
        elementCount++

        // 3. Distance to Turn: Category 0x05, Tag 0x04 (2 bytes value)
        payload += buildIntTlv(0x0504, distanceToTurn, 2)
        elementCount++

        // 4. Distance to Turn Unit: Category 0x05, Tag 0x06 (1 byte value)
        payload += buildTlv(0x0506, byteArrayOf(distanceUnit.toByte()))
        elementCount++

        // 5. ETA Time (HH:MM): Category 0x05, Tag 0x08 (4 bytes ASCII string e.g. "1234")
        val hh = String.format("%02d", etaHours)
        val mm = String.format("%02d", etaMinutes)
        val etaStrBytes = (hh + mm).toByteArray(StandardCharsets.US_ASCII)
        payload += buildTlv(0x0508, etaStrBytes)
        elementCount++

        // 6. Total Remaining Distance: Category 0x05, Tag 0x09 (2 bytes value)
        payload += buildIntTlv(0x0509, totalDistanceRemaining, 2)
        elementCount++

        // 7. Total Distance Unit: Category 0x05, Tag 0x70 (1 byte value)
        payload += buildTlv(0x0570, byteArrayOf(totalDistanceUnit.toByte()))
        elementCount++

        return buildPacket(elementCount, payload)
    }

    /**
     * Builds an Image Fragment packet (Category 0x08).
     * @param fragmentIndex Index of this fragment (starting at 0).
     * @param totalFragments Total number of fragments for this image.
     * @param data The JPEG data chunk.
     */
    fun buildImagePacket(fragmentIndex: Int, totalFragments: Int, data: ByteArray): ByteArray {
        val infoValue = ByteArray(4)
        infoValue[0] = (totalFragments shr 8).toByte()
        infoValue[1] = (totalFragments and 0xFF).toByte()
        infoValue[2] = (fragmentIndex shr 8).toByte()
        infoValue[3] = (fragmentIndex and 0xFF).toByte()
        
        val infoTlv = buildTlv(0x0801, infoValue)
        val dataTlv = buildTlv(0x0802, data)
        
        return buildPacket(2, infoTlv + dataTlv)
    }

    /**
     * Builds a Handshake / Keep-alive packet (often needed to wake up Dash).
     * Category 0x00, Tag 0x000B (FOTA signal often used as handshake).
     */
    fun buildHandshakePacket(): ByteArray {
        val tlv = buildTlv(0x000B, byteArrayOf(0x00))
        return buildPacket(1, tlv)
    }
}