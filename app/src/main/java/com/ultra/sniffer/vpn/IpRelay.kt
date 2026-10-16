package com.ultra.sniffer.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "IpRelay"

/**
 * Main relay loop: reads IPv4 packets from the TUN interface, dispatches them
 * to the TCP/UDP engines and writes synthetic reply packets back into the TUN.
 */
class IpRelay(
    vpn: VpnService,
    tun: ParcelFileDescriptor,
    sniffer: TrafficSniffer,
    blockQuic: () -> Boolean
) : Runnable {

    private val input = FileInputStream(tun.fileDescriptor)
    private val output = FileOutputStream(tun.fileDescriptor)
    private val writeLock = Any()

    private val tcp = TcpEngine(vpn, ::inject, sniffer)
    private val udp = UdpEngine(vpn, ::inject, sniffer, blockQuic)

    @Volatile
    private var running = true

    /** Thread-safe packet injection into the VPN interface. */
    fun inject(packet: ByteArray) {
        synchronized(writeLock) {
            runCatching {
                output.write(packet)
                output.flush()
            }
        }
    }

    fun stop() {
        running = false
        runCatching { input.close() }
    }

    override fun run() {
        tcp.start()
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = try {
                    input.read(buf)
                } catch (t: Throwable) {
                    break
                }
                if (n <= 0) continue
                try {
                    dispatch(buf, n)
                } catch (t: Throwable) {
                    Log.w(TAG, "dispatch error: ${t.message}")
                }
            }
        } finally {
            tcp.shutdown()
            udp.shutdown()
        }
    }

    private fun dispatch(pkt: ByteArray, len: Int) {
        if (len < 20) return
        val vihl = pkt[0].toInt() and 0xFF
        if (vihl ushr 4 != 4) return                      // IPv4 only
        val ihl = (vihl and 0x0F) * 4
        if (ihl < 20 || len < ihl) return

        // ignore fragmented packets (we do not reassemble IP fragments)
        val frag = PacketUtil.readShort(pkt, 6)
        if ((frag and 0x2000) != 0 || (frag and 0x1FFF) != 0) return

        val totalLen = PacketUtil.readShort(pkt, 2)
        val end = if (totalLen in ihl..len) totalLen else len
        val proto = pkt[9].toInt() and 0xFF
        val srcIp = PacketUtil.readInt(pkt, 12)
        val dstIp = PacketUtil.readInt(pkt, 16)

        when (proto) {
            PacketUtil.PROTO_TCP -> dispatchTcp(pkt, ihl, end, srcIp, dstIp)
            PacketUtil.PROTO_UDP -> dispatchUdp(pkt, ihl, end, srcIp, dstIp)
            // ICMP is intentionally dropped
        }
    }

    private fun dispatchTcp(pkt: ByteArray, off: Int, end: Int, srcIp: Int, dstIp: Int) {
        if (end - off < 20) return
        val srcPort = PacketUtil.readShort(pkt, off)
        val dstPort = PacketUtil.readShort(pkt, off + 2)
        val seq = PacketUtil.readInt(pkt, off + 4)
        val ack = PacketUtil.readInt(pkt, off + 8)
        val dataOff = ((pkt[off + 12].toInt() and 0xF0) shr 4) * 4
        if (dataOff < 20) return
        val flags = pkt[off + 13].toInt() and 0xFF
        val window = PacketUtil.readShort(pkt, off + 14)
        val payloadOff = off + dataOff
        val payloadLen = if (payloadOff < end) end - payloadOff else 0

        tcp.onPacket(
            srcIp, srcPort, dstIp, dstPort, seq, ack, flags, window,
            pkt, payloadOff, payloadLen, off + 20, dataOff - 20
        )
    }

    private fun dispatchUdp(pkt: ByteArray, off: Int, end: Int, srcIp: Int, dstIp: Int) {
        if (end - off < 8) return
        val srcPort = PacketUtil.readShort(pkt, off)
        val dstPort = PacketUtil.readShort(pkt, off + 2)
        val payloadOff = off + 8
        val payloadLen = if (payloadOff < end) end - payloadOff else 0
        udp.onPacket(srcIp, srcPort, dstIp, dstPort, pkt, payloadOff, payloadLen)
    }
}
