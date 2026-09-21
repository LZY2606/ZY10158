package app

/**
 * Simplified ISO 15765-2 (ISO-TP) frame classification, classic CAN, 11-bit IDs.
 *
 * Nibble layout of data[0]:
 *  0x0  Single frame      SF: len = data[0] & 0x0F (len 1..7)
 *  0x1  First frame       FF: total length = ((data[0] & 0x0F) << 8) | data[1]
 *  0x2  Consecutive frame CF: sequence number = data[0] & 0x0F (1..14, then wraps)
 *  0x3  Flow control      FC: data[1] = 0 continue-to-send (only CTS modeled)
 */
object IsoTp {
    fun classify(data: ByteArray): FrameType {
        if (data.isEmpty()) return FrameType.UNKNOWN
        return when ((data[0].toInt() ushr 4) and 0x0F) {
            0x0 -> if ((data[0].toInt() and 0x0F) in 1..7) FrameType.SF else FrameType.UNKNOWN
            0x1 -> if (data.size >= 2) FrameType.FF else FrameType.UNKNOWN
            0x2 -> FrameType.CF
            0x3 -> FrameType.FC
            else -> FrameType.UNKNOWN
        }
    }

    fun sfLength(data: ByteArray): Int = data[0].toInt() and 0x0F

    fun ffLength(data: ByteArray): Int = ((data[0].toInt() and 0x0F) shl 8) or (data[1].toInt() and 0xFF)

    fun cfIndex(data: ByteArray): Int = data[0].toInt() and 0x0F

    fun nextCfIndex(index: Int): Int = (index + 1) and 0x0F

    /**
     * Maps a 4-bit CF sequence number to its 1-based transfer slot, given the smallest
     * slot not yet received. CF slots run 1..14, then 0, then 1 again.
     */
    fun slotFor(index: Int, nextSlot: Int): Int {
        for (slot in nextSlot until nextSlot + 15) {
            val sn = if (slot % 15 == 0) 0 else slot % 15
            if (sn == index) return slot
        }
        error("cannot map CF index $index from nextSlot=$nextSlot")
    }

    /** UDS service id represented by a response's first payload bytes (handles 0x7F negative). */
    fun responseServiceId(payload: ByteArray): Int? {
        if (payload.isEmpty()) return null
        val first = payload[0].toInt() and 0xFF
        return when {
            first == 0x7F && payload.size >= 2 -> payload[1].toInt() and 0xFF
            first in 0x40..0x7E -> first - 0x40
            else -> null
        }
    }

    /** Payload bytes of a single-frame message. */
    fun sfPayload(data: ByteArray): ByteArray = data.copyOfRange(1, 1 + sfLength(data))

    /** First-frame payload: bytes 2..7 (6 bytes on classic CAN). */
    fun ffPayload(data: ByteArray): ByteArray = data.copyOfRange(2, data.size)

    /** Consecutive-frame payload: bytes 1..7. */
    fun cfPayload(data: ByteArray): ByteArray = data.copyOfRange(1, data.size)

    /**
     * Standard UDS physical response ID for a request ID on classic 11-bit addressing:
     * 0x7E0..0x7E7 request -> 0x7E8..0x7EF response.
     */
    fun peerCanId(canId: Int): Int? =
        if (canId in 0x7E0..0x7E7) canId + 8
        else if (canId in 0x7E8..0x7EF) canId - 8
        else null

    fun isRequestId(canId: Int): Boolean = canId in 0x7E0..0x7E7
}
