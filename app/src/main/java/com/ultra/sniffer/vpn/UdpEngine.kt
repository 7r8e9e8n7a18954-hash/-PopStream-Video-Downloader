package com.ultra.sniffer.vpn

import android.net.VpnService
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

private const val UDP_IDLE_MS = 120_000L

/**
 * Userspace UDP NAT.
 *
 * Every intercepted UDP datagram (DNS, QUIC, gaming, ...) gets a protected
 * DatagramSocket that forwards it to the real network. Replies are wrapped
 * back into IP packets and re-injected into the TUN interface.
 */
class UdpEngine(
    private val owner: VpnService,
    private val inject: (ByteArray) -> Unit,
    private val sniffer: TrafficSniffer,
    private val blockQuic: () -> Boolean
) {

    private data class UdpKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

    private class UdpFlow(val key: UdpKey, val socket: DatagramSocket) {
        @Volatile
        var lastActive = System.currentTimeMillis()

        @Volatile
        var closed = false
    }

    private val flows = HashMap<UdpKey, UdpFlow>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ultra-udp").apply { isDaemon = true }
    }

    fun onPacket(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, data: ByteArray, off: Int, len: Int) {
        if (dstPort == 443 && blockQuic()) return                              // QUIC blocked on purpose
        if ((dstIp ushr 28) == 0xE || dstIp == -1 || dstIp == 0) return        // multicast / broadcast / 0.0.0.0

        val key = UdpKey(srcIp, srcPort, dstIp, dstPort)
        val flow = synchronized(this) { flows[key] } ?: createFlow(key, dstIp, dstPort)
        if (flow.closed) return
        try {
            val payload = if (off == 0 && len == data.size) data else data.copyOfRange(off, off + len)
            flow.socket.send(
                DatagramPacket(payload, len, InetAddress.getByAddress(PacketUtil.ipToBytes(dstIp)), dstPort)
            )
            flow.lastActive = System.currentTimeMillis()
        } catch (t: Throwable) {
            closeFlow(flow)
        }
    }

    private fun createFlow(key: UdpKey, dstIp: Int, dstPort: Int): UdpFlow {
        val socket = DatagramSocket()
        runCatching { owner.protect(socket) }
        socket.soTimeout = 1000
        val flow = UdpFlow(key, socket)
        synchronized(this) { flows[key] = flow }
        pool.execute { receiveLoop(flow) }
        return flow
    }

    private fun receiveLoop(flow: UdpFlow) {
        val buf = ByteArray(65535)
        try {
            while (!flow.closed) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    flow.socket.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    if (System.currentTimeMillis() - flow.lastActive > UDP_IDLE_MS) break
                    continue
                }
                flow.lastActive = System.currentTimeMillis()
                val reply = PacketUtil.buildUdpPacket(
                    flow.key.dstIp, flow.key.srcIp, flow.key.dstPort, flow.key.srcPort,
                    buf, 0, pkt.length
                )
                inject(reply)
            }
        } catch (t: Throwable) {
            // socket closed or send failure: fall through to cleanup
        } finally {
            closeFlow(flow)
        }
    }

    private fun closeFlow(flow: UdpFlow) {
        if (flow.closed) return
        flow.closed = true
        synchronized(this) { flows.remove(flow.key) }
        runCatching { flow.socket.close() }
    }

    fun shutdown() {
        val all = synchronized(this) { flows.values.toList() }
        all.forEach { closeFlow(it) }
        pool.shutdownNow()
    }
}
