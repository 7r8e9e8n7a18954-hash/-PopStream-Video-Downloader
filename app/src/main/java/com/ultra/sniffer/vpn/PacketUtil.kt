package com.ultra.sniffer.vpn

import java.util.concurrent.ThreadLocalRandom

/** Key identifying one direction of an IP flow. IPs are stored as big-endian Ints. */
data class FlowKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

/**
 * Low level IPv4 / TCP / UDP packet building and checksum helpers.
 * Everything uses Int for speed; no allocation happens per-byte.
 */
object PacketUtil {

    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10

    const val IP_HEADER_LEN = 20
    const val TCP_HEADER_LEN = 20
    const val UDP_HEADER_LEN = 8

    fun readShort(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    fun putShort(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    fun ipToBytes(ip: Int): ByteArray = byteArrayOf(
        (ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte()
    )

    fun randomSeq(): Int = ThreadLocalRandom.current().nextInt()

    // ------------------------------------------------------------------
    // TCP sequence arithmetic (wrap-around safe)
    // ------------------------------------------------------------------

    fun seqLess(a: Int, b: Int): Boolean = (b - a) > 0
    fun seqLEQ(a: Int, b: Int): Boolean = (b - a) >= 0
    fun seqDiff(a: Int, b: Int): Int = a - b

    // ------------------------------------------------------------------
    // Checksums
    // ------------------------------------------------------------------

    /** Standard internet checksum (returns host order 16 bit value). */
    fun checksum(data: ByteArray, offset: Int, length: Int, initial: Long = 0L): Int {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (i < end) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    fun pseudoSumV4(srcIp: Int, dstIp: Int, protocol: Int, length: Int): Long {
        var sum = 0L
        sum += ((srcIp ushr 16) and 0xFFFF).toLong()
        sum += (srcIp and 0xFFFF).toLong()
        sum += ((dstIp ushr 16) and 0xFFFF).toLong()
        sum += (dstIp and 0xFFFF).toLong()
        sum += protocol.toLong()
        sum += length.toLong()
        return sum
    }

    // ------------------------------------------------------------------
    // Packet builders (IPv4 header + transport header + payload)
    // ------------------------------------------------------------------

    fun buildTcpPacket(
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        payload: ByteArray?, payloadOff: Int = 0, payloadLen: Int = 0,
        tcpOptions: ByteArray? = null
    ): ByteArray {
        val headerLen = TCP_HEADER_LEN + (tcpOptions?.size ?: 0)
        val packetLen = IP_HEADER_LEN + headerLen + payloadLen
        val pkt = ByteArray(packetLen)

        // IPv4 header
        pkt[0] = 0x45
        pkt[1] = 0
        putShort(pkt, 2, packetLen)
        putShort(pkt, 4, 0)
        putShort(pkt, 6, 0x4000) // Don't fragment
        pkt[8] = 64
        pkt[9] = PROTO_TCP.toByte()
        putShort(pkt, 10, 0)
        putInt(pkt, 12, srcIp)
        putInt(pkt, 16, dstIp)
        putShort(pkt, 10, checksum(pkt, 0, IP_HEADER_LEN))

        // TCP header
        val t = IP_HEADER_LEN
        putShort(pkt, t, srcPort)
        putShort(pkt, t + 2, dstPort)
        putInt(pkt, t + 4, seq)
        putInt(pkt, t + 8, ack)
        pkt[t + 12] = ((headerLen / 4) shl 4).toByte()
        pkt[t + 13] = flags.toByte()
        putShort(pkt, t + 14, window)
        putShort(pkt, t + 16, 0)
        putShort(pkt, t + 18, 0)
        tcpOptions?.let { System.arraycopy(it, 0, pkt, t + TCP_HEADER_LEN, it.size) }
        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, payloadOff, pkt, t + headerLen, payloadLen)
        }
        val tcpSum = checksum(
            pkt, t, headerLen + payloadLen,
            pseudoSumV4(srcIp, dstIp, PROTO_TCP, headerLen + payloadLen)
        )
        putShort(pkt, t + 16, tcpSum)
        return pkt
    }

    fun buildUdpPacket(
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        payload: ByteArray, payloadOff: Int = 0, payloadLen: Int = payload.size
    ): ByteArray {
        val packetLen = IP_HEADER_LEN + UDP_HEADER_LEN + payloadLen
        val pkt = ByteArray(packetLen)

        pkt[0] = 0x45
        pkt[1] = 0
        putShort(pkt, 2, packetLen)
        putShort(pkt, 4, 0)
        putShort(pkt, 6, 0x4000)
        pkt[8] = 64
        pkt[9] = PROTO_UDP.toByte()
        putShort(pkt, 10, 0)
        putInt(pkt, 12, srcIp)
        putInt(pkt, 16, dstIp)
        putShort(pkt, 10, checksum(pkt, 0, IP_HEADER_LEN))

        val u = IP_HEADER_LEN
        putShort(pkt, u, srcPort)
        putShort(pkt, u + 2, dstPort)
        putShort(pkt, u + 4, UDP_HEADER_LEN + payloadLen)
        putShort(pkt, u + 6, 0)
        System.arraycopy(payload, payloadOff, pkt, u + UDP_HEADER_LEN, payloadLen)
        var sum = checksum(
            pkt, u, UDP_HEADER_LEN + payloadLen,
            pseudoSumV4(srcIp, dstIp, PROTO_UDP, UDP_HEADER_LEN + payloadLen)
        )
        if (sum == 0) sum = 0xFFFF
        putShort(pkt, u + 6, sum)
        return pkt
    }

    /** TCP options blob advertising our MSS (padded to 4 bytes). */
    fun mssOption(mss: Int): ByteArray = byteArrayOf(
        0x02, 0x04, (mss ushr 8).toByte(), mss.toByte()
    )
}
