@file:JvmName("IsoTp")
package app

/* 简化 ISO 15765-2 分组层（Classical CAN / CAN FD 数据均按 PCI 解析）。
 *
 * PCI 高 nibble:
 *   0x0 SF   0x1 FF   0x2 CF   0x3 FC
 * SF:  byte0 低 nibble 为长度（<8 的 CAN）；0 则为 escape：byte1=长度（CAN FD, 12..62）
 * FF:  12bit 长度（byte0 低 nibble<<8 | byte1）；0 则为 escape：byte2..5 为 32bit 长度
 * CF:  低 nibble 为 SN（首 CF=1，1..15 循环）
 * FC:  byte0 低 nibble=FS(0=CTS 1=WAIT 2=ABORT)，byte1=BS，byte2=STmin
 */
object IsoTp {

    fun classify(data: ByteArray): ParsedPci {
        if (data.isEmpty()) return ParsedPci(FrameClass.OTHER, reason = "空帧")
        val pci = data[0].toInt() and 0xF0
        return when (pci) {
            0x00 -> parseSf(data)
            0x10 -> parseFf(data)
            0x20 -> ParsedPci(FrameClass.CF, sn = data[0].toInt() and 0x0F)
            0x30 -> parseFc(data)
            else -> ParsedPci(FrameClass.OTHER, reason = "保留 PCI 类型 0x%X".format(data[0].toInt() and 0xF0))
        }
    }

    private fun parseSf(data: ByteArray): ParsedPci {
        val n = data[0].toInt() and 0x0F
        if (n != 0) {
            if (n >= 7) return ParsedPci(FrameClass.OTHER, reason = "SF 长度字段非法=$n")
            if (data.size < n + 1) return ParsedPci(FrameClass.OTHER, reason = "SF 声明 $n 字节但帧长 ${data.size - 1}")
            if (n == 0) return ParsedPci(FrameClass.OTHER, reason = "SF 长度为 0")
            return ParsedPci(FrameClass.SF, len = n)
        }
        if (data.size < 2) return ParsedPci(FrameClass.OTHER, reason = "escape SF 缺少长度字节")
        val escLen = data[1].toInt() and 0xFF
        if (escLen == 0) return ParsedPci(FrameClass.OTHER, reason = "SF 长度为 0")
        if (escLen < 12) return ParsedPci(FrameClass.OTHER, reason = "escape SF 长度必须 >=12，实际=$escLen")
        if (data.size < escLen + 2) return ParsedPci(FrameClass.OTHER, reason = "escape SF 数据不足")
        return ParsedPci(FrameClass.SF, len = escLen)
    }

    private fun parseFf(data: ByteArray): ParsedPci {
        val hi = data[0].toInt() and 0x0F
        val lo = data[1].toInt() and 0xFF
        val len12 = (hi shl 8) or lo
        if (len12 != 0) {
            if (len12 < 8) return ParsedPci(FrameClass.OTHER, reason = "FF 声明长度 <8，应使用 SF（=$len12）")
            if (data.size < 8) return ParsedPci(FrameClass.OTHER, reason = "FF 不足 8 字节")
            return ParsedPci(FrameClass.FF, len = len12)
        }
        if (data.size < 6) return ParsedPci(FrameClass.OTHER, reason = "escape FF 不足 6 字节")
        val len32 = ((data[2].toInt() and 0xFF) shl 24) or
                ((data[3].toInt() and 0xFF) shl 16) or
                ((data[4].toInt() and 0xFF) shl 8) or
                (data[5].toInt() and 0xFF)
        if (len32 < 4096) return ParsedPci(FrameClass.OTHER, reason = "escape FF 长度必须 >=4096，实际=$len32")
        return ParsedPci(FrameClass.FF, len = len32)
    }

    private fun parseFc(data: ByteArray): ParsedPci {
        if (data.size < 3) return ParsedPci(FrameClass.OTHER, reason = "FC 帧不足 3 字节")
        val fs = data[0].toInt() and 0x0F
        val status = when (fs) {
            0 -> FcFlowStatus.CONTINUE
            1 -> FcFlowStatus.WAIT
            2 -> FcFlowStatus.ABORT
            else -> FcFlowStatus.RESERVED
        }
        return ParsedPci(
            FrameClass.FC, fcStatus = status,
            blockSize = data[1].toInt() and 0xFF,
            stMinUs = decodeStMin(data[2].toInt() and 0xFF)
        )
    }

    /** STmin 解码：0x00-0x7F 毫秒*0.1；0xF1-0xF9 100..900us；其余保留按 127ms。 */
    fun decodeStMin(v: Int): Int = when {
        v <= 0x7F -> v * 100
        v in 0xF1..0xF9 -> (v - 0xF0) * 100
        else -> 0x7F * 100
    }

    fun sfPayload(data: ByteArray, pci: ParsedPci): ByteArray =
        data.copyOfRange(1, 1 + (pci.len ?: 0))

    // 经典 CAN：PCI 高 nibble+12bit 长度跨 byte0/byte1，服务数据从 byte2 开始（6 字节）
    // CAN FD escape：长度占 byte2..5，服务数据从 byte6 开始
    fun ffPayload(data: ByteArray, esc: Boolean): ByteArray =
        data.copyOfRange(if (esc) 6 else 2, data.size)
}
