package com.ultra.sniffer.vpn

import android.net.VpnService
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

private const val TAG = "TcpEngine"
private const val RECV_WINDOW = 65535
private const val QUEUE_MAX_BYTES = 512 * 1024
private const val DEFAULT_MSS = 1400
private const val IDLE_TIMEOUT_MS = 120_000L
private const val CONNECT_TIMEOUT_MS = 10_000
private const val MAX_RETRIES = 6
private const val TIME_WAIT_MS = 6_000L
private val EOF_MARKER = ByteArray(0)

/**
 * Terminating TCP relay (userspace NAT).
 *
 * The VPN captures app TCP connections. This engine terminates them locally:
 * it answers the handshake with a synthetic SYN-ACK, tracks sequence numbers
 * and windows itself, opens a protected real socket to the destination and
 * relays payload in both directions. Calls are made from the TUN reader
 * thread; socket pumps run on a small cached thread pool.
 */
class TcpEngine(
    private val owner: VpnService,
    private val inject: (ByteArray) -> Unit,
    private val sniffer: TrafficSniffer
) {

    private class Seg(val endSeq: Int, val packet: ByteArray) {
        var lastSent = System.currentTimeMillis()
        var rto = 500L
        var retries = 0
    }

    private class Conn(val key: FlowKey, val serverIsn: Int, val mss: Int) {
        val lock = ReentrantLock()
        val cond: Condition = lock.newCondition()
        var serverNextSeq = serverIsn + 1
        var serverUna = serverIsn + 1
        var clientExpectedSeq = 0
        var clientWindow = RECV_WINDOW
        var lastAdvertisedWindow = RECV_WINDOW
        var finFromClient = false
        var finFromServer = false
        var closed = false
        var lastActive = System.currentTimeMillis()

        @Volatile
        var socket: Socket? = null
        val outQueue = LinkedBlockingQueue<ByteArray>()

        @Volatile
        var queuedBytes = 0
        val sent = ArrayDeque<Seg>()
    }

    private val conns = HashMap<FlowKey, Conn>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ultra-tcp-pump").apply { isDaemon = true }
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ultra-tcp-tick").apply { isDaemon = true }
    }

    @Volatile
    private var running = true

    fun start() {
        ticker.scheduleWithFixedDelay({ runCatching { tick() } }, 250, 250, TimeUnit.MILLISECONDS)
    }

    fun shutdown() {
        running = false
        ticker.shutdownNow()
        val snapshot = synchronized(this) { conns.values.toList() }
        for (c in snapshot) closeConn(c, notifyClient = false)
        synchronized(this) { conns.clear() }
        pool.shutdownNow()
    }

    // ------------------------------------------------------------------
    // Entry point (TUN reader thread)
    // ------------------------------------------------------------------

    fun onPacket(
        srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        pkt: ByteArray, payloadOff: Int, payloadLen: Int,
        optionsOff: Int, optionsLen: Int
    ) {
        if ((flags and PacketUtil.FLAG_SYN) != 0 && (flags and PacketUtil.FLAG_ACK) == 0) {
            handleSyn(srcIp, srcPort, dstIp, dstPort, seq, window, pkt, optionsOff, optionsLen)
            return
        }

        val key = FlowKey(srcIp, srcPort, dstIp, dstPort)
        val conn = synchronized(this) { conns[key] }
        if (conn == null) {
            if ((flags and PacketUtil.FLAG_RST) == 0) {
                sendRstForUnknown(srcIp, srcPort, dstIp, dstPort, seq, payloadLen, flags)
            }
            return
        }

        conn.lock.withLock {
            if (conn.closed) return
            conn.lastActive = System.currentTimeMillis()
            if ((flags and PacketUtil.FLAG_RST) != 0) {
                closeConn(conn, notifyClient = false)
                return
            }
            if ((flags and PacketUtil.FLAG_ACK) != 0) processAckLocked(conn, ack, window)
            if (payloadLen > 0) handleClientDataLocked(conn, seq, pkt, payloadOff, payloadLen)
            if ((flags and PacketUtil.FLAG_FIN) != 0) handleClientFinLocked(conn)
        }
    }

    // ------------------------------------------------------------------
    // Handshake / connect
    // ------------------------------------------------------------------

    private fun handleSyn(
        srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
        seq: Int, window: Int, pkt: ByteArray, optionsOff: Int, optionsLen: Int
    ) {
        val key = FlowKey(srcIp, srcPort, dstIp, dstPort)

        synchronized(this) {
            conns[key]?.let { existing ->
                // SYN retransmission: answer again with the same ISN
                runCatching {
                    inject(
                        PacketUtil.buildTcpPacket(
                            dstIp, srcIp, dstPort, srcPort,
                            existing.serverIsn, seq + 1, PacketUtil.FLAG_SYN or PacketUtil.FLAG_ACK,
                            RECV_WINDOW, null, 0, 0, PacketUtil.mssOption(DEFAULT_MSS)
                        )
                    )
                }
                return
            }
        }

        // parse client MSS option (kind 2, len 4)
        var mss = DEFAULT_MSS
        var i = optionsOff
        val end = optionsOff + optionsLen
        while (i + 1 < end) {
            val kind = pkt[i].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) { i += 1; continue }
            if (i + 2 > end) break
            val olen = pkt[i + 1].toInt() and 0xFF
            if (olen < 2 || i + olen > end) break
            if (kind == 2 && olen == 4) mss = max(536, PacketUtil.readShort(pkt, i + 2))
            i += olen
        }

        val serverIsn = PacketUtil.randomSeq()
        val conn = Conn(key, serverIsn, min(mss, DEFAULT_MSS))
        conn.lock.withLock {
            conn.clientExpectedSeq = seq + 1
            conn.clientWindow = window
        }
        synchronized(this) { conns[key] = conn }

        runCatching {
            inject(
                PacketUtil.buildTcpPacket(
                    dstIp, srcIp, dstPort, srcPort,
                    serverIsn, seq + 1, PacketUtil.FLAG_SYN or PacketUtil.FLAG_ACK,
                    RECV_WINDOW, null, 0, 0, PacketUtil.mssOption(DEFAULT_MSS)
                )
            )
        }

        pool.execute {
            try {
                val socket = Socket()
                owner.protect(socket)
                socket.tcpNoDelay = true
                socket.soTimeout = 0
                socket.connect(
                    InetSocketAddress(InetAddress.getByAddress(PacketUtil.ipToBytes(dstIp)), dstPort),
                    CONNECT_TIMEOUT_MS
                )
                conn.lock.withLock {
                    if (conn.closed) {
                        runCatching { socket.close() }
                        return@withLock
                    }
                    conn.socket = socket
                }
                if (conn.closed) {
                    runCatching { socket.close() }
                    return@execute
                }
                startPumps(conn, socket)
            } catch (t: Throwable) {
                Log.w(TAG, "connect failed ${conn.key.dstIp}:${conn.key.dstPort} -> ${t.message}")
                conn.lock.withLock {
                    if (!conn.closed) {
                        runCatching {
                            inject(
                                PacketUtil.buildTcpPacket(
                                    dstIp, srcIp, dstPort, srcPort,
                                    conn.serverNextSeq, conn.clientExpectedSeq,
                                    PacketUtil.FLAG_RST or PacketUtil.FLAG_ACK, 0, null, 0, 0
                                )
                            )
                        }
                    }
                }
                closeConn(conn, notifyClient = false)
            }
        }
    }

    // ------------------------------------------------------------------
    // Socket pumps
    // ------------------------------------------------------------------

    private fun startPumps(conn: Conn, socket: Socket) {
        val input: InputStream
        val output: OutputStream
        try {
            input = socket.getInputStream()
            output = socket.getOutputStream()
        } catch (t: Throwable) {
            closeConn(conn, notifyClient = true)
            return
        }

        // client -> server
        pool.execute {
            try {
                while (running && !conn.closed) {
                    val chunk = conn.outQueue.poll(1, TimeUnit.SECONDS) ?: continue
                    if (chunk.isEmpty()) {
                        runCatching {
                            output.flush()
                            socket.shutdownOutput()
                        }
                        break
                    }
                    output.write(chunk)
                    conn.lock.withLock {
                        conn.queuedBytes = max(0, conn.queuedBytes - chunk.size)
                        if (!conn.closed && conn.lastAdvertisedWindow == 0) sendAckLocked(conn)
                    }
                }
            } catch (t: Throwable) {
                closeConn(conn, notifyClient = true)
            }
        }

        // server -> client
        pool.execute {
            try {
                val buf = ByteArray(16 * 1024)
                while (running && !conn.closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    conn.lastActive = System.currentTimeMillis()
                    val copy = buf.copyOf(n)
                    sniffer.serverData(conn.key.dstIp, conn.key.dstPort, conn.key.srcIp, conn.key.srcPort, copy, 0, n)
                    sendDataToClient(conn, copy)
                }
                sendFinToClient(conn)
            } catch (t: Throwable) {
                if (!conn.closed && !conn.finFromServer) closeConn(conn, notifyClient = true)
            }
        }
    }

    // ------------------------------------------------------------------
    // Client -> server data + ACK handling
    // ------------------------------------------------------------------

    private fun processAckLocked(conn: Conn, ack: Int, window: Int) {
        conn.clientWindow = window
        if (PacketUtil.seqLess(conn.serverUna, ack) && PacketUtil.seqLEQ(ack, conn.serverNextSeq)) {
            conn.serverUna = ack
            while (conn.sent.isNotEmpty() && PacketUtil.seqLEQ(conn.sent.peekFirst().endSeq, ack)) {
                conn.sent.removeFirst()
            }
        }
        conn.cond.signalAll()
    }

    private fun handleClientDataLocked(conn: Conn, seq: Int, pkt: ByteArray, dataOff: Int, dataLen: Int) {
        var off = dataOff
        var len = dataLen

        if (seq != conn.clientExpectedSeq) {
            if (PacketUtil.seqLess(seq, conn.clientExpectedSeq)) {
                val already = PacketUtil.seqDiff(conn.clientExpectedSeq, seq)
                if (already >= len) {
                    sendAckLocked(conn) // pure duplicate
                    return
                }
                off += already
                len -= already
            } else {
                sendAckLocked(conn) // out of order -> trigger retransmit
                return
            }
        }
        if (len <= 0) {
            sendAckLocked(conn)
            return
        }

        // sniff before flow-control gate so detection survives heavy load
        sniffer.clientData(conn.key.srcIp, conn.key.srcPort, conn.key.dstIp, conn.key.dstPort, pkt, off, len)

        if (conn.queuedBytes + len > QUEUE_MAX_BYTES) {
            // refuse the data for now: advertise a zero window, client retries later
            sendAckLocked(conn, windowOverride = 0)
            return
        }

        conn.queuedBytes += len
        conn.outQueue.offer(pkt.copyOfRange(off, off + len))
        conn.clientExpectedSeq += len
        sendAckLocked(conn)
    }

    private fun handleClientFinLocked(conn: Conn) {
        if (conn.finFromClient) return
        conn.finFromClient = true
        conn.clientExpectedSeq += 1
        sendAckLocked(conn)
        conn.outQueue.offer(EOF_MARKER)
        if (conn.finFromServer) scheduleClose(conn)
    }

    private fun sendAckLocked(conn: Conn, windowOverride: Int? = null) {
        val win = windowOverride
            ?: if (conn.queuedBytes > QUEUE_MAX_BYTES - 65536) 0 else RECV_WINDOW
        conn.lastAdvertisedWindow = win
        runCatching {
            inject(
                PacketUtil.buildTcpPacket(
                    conn.key.dstIp, conn.key.srcIp, conn.key.dstPort, conn.key.srcPort,
                    conn.serverNextSeq, conn.clientExpectedSeq, PacketUtil.FLAG_ACK, win, null, 0, 0
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // Server -> client data (with flow control)
    // ------------------------------------------------------------------

    private fun sendDataToClient(conn: Conn, data: ByteArray) {
        var offset = 0
        conn.lock.withLock {
            while (offset < data.size && !conn.closed) {
                val inflight = PacketUtil.seqDiff(conn.serverNextSeq, conn.serverUna)
                val avail = conn.clientWindow - inflight
                if (avail <= 0) {
                    try {
                        conn.cond.await(500, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                    continue
                }
                val chunk = minOf(avail, conn.mss, data.size - offset)
                val pkt = PacketUtil.buildTcpPacket(
                    conn.key.dstIp, conn.key.srcIp, conn.key.dstPort, conn.key.srcPort,
                    conn.serverNextSeq, conn.clientExpectedSeq,
                    PacketUtil.FLAG_ACK or PacketUtil.FLAG_PSH,
                    RECV_WINDOW, data, offset, chunk
                )
                conn.sent.addLast(Seg(conn.serverNextSeq + chunk, pkt))
                conn.serverNextSeq += chunk
                offset += chunk
                runCatching { inject(pkt) }
            }
        }
    }

    private fun sendFinToClient(conn: Conn) {
        conn.lock.withLock {
            if (conn.closed || conn.finFromServer) return
            conn.finFromServer = true
            val pkt = PacketUtil.buildTcpPacket(
                conn.key.dstIp, conn.key.srcIp, conn.key.dstPort, conn.key.srcPort,
                conn.serverNextSeq, conn.clientExpectedSeq,
                PacketUtil.FLAG_FIN or PacketUtil.FLAG_ACK, RECV_WINDOW, null, 0, 0
            )
            conn.sent.addLast(Seg(conn.serverNextSeq + 1, pkt))
            conn.serverNextSeq += 1
            runCatching { inject(pkt) }
            if (conn.finFromClient) scheduleClose(conn)
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private fun scheduleClose(conn: Conn) {
        if (ticker.isShutdown) return
        runCatching {
            ticker.schedule({ closeConn(conn, notifyClient = false) }, TIME_WAIT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun closeConn(conn: Conn, notifyClient: Boolean) {
        var alreadyClosed = false
        conn.lock.withLock {
            if (conn.closed) {
                alreadyClosed = true
                return@withLock
            }
            conn.closed = true
            conn.cond.signalAll()
        }
        if (alreadyClosed) return
        synchronized(this) { conns.remove(conn.key) }
        runCatching { conn.socket?.close() }
        if (notifyClient) {
            runCatching {
                inject(
                    PacketUtil.buildTcpPacket(
                        conn.key.dstIp, conn.key.srcIp, conn.key.dstPort, conn.key.srcPort,
                        conn.serverNextSeq, conn.clientExpectedSeq,
                        PacketUtil.FLAG_RST or PacketUtil.FLAG_ACK, 0, null, 0, 0
                    )
                )
            }
        }
    }

    private fun sendRstForUnknown(
        srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
        seq: Int, payloadLen: Int, flags: Int
    ) {
        var ackNum = seq + payloadLen
        if ((flags and PacketUtil.FLAG_SYN) != 0) ackNum += 1
        if ((flags and PacketUtil.FLAG_FIN) != 0) ackNum += 1
        runCatching {
            inject(
                PacketUtil.buildTcpPacket(
                    dstIp, srcIp, dstPort, srcPort, 0, ackNum,
                    PacketUtil.FLAG_RST or PacketUtil.FLAG_ACK, 0, null, 0, 0
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // Periodic maintenance: retransmissions + idle cleanup
    // ------------------------------------------------------------------

    private fun tick() {
        if (!running) return
        val now = System.currentTimeMillis()
        val snapshot = synchronized(this) { conns.values.toList() }
        for (conn in snapshot) {
            val action = conn.lock.withLock {
                if (conn.closed) return@withLock 0
                val first = conn.sent.peekFirst()
                if (first != null && now - first.lastSent > first.rto) {
                    if (first.retries >= MAX_RETRIES) return@withLock 2
                    first.retries++
                    first.rto = minOf(first.rto * 2, 4000L)
                    first.lastSent = now
                    runCatching { inject(first.packet) }
                    return@withLock 0
                }
                if (now - conn.lastActive > IDLE_TIMEOUT_MS && conn.sent.isEmpty() && conn.queuedBytes == 0) {
                    return@withLock 2
                }
                if (conn.finFromClient && conn.finFromServer && conn.sent.isEmpty()) {
                    return@withLock 1
                }
                0
            }
            when (action) {
                1 -> closeConn(conn, notifyClient = false)
                2 -> closeConn(conn, notifyClient = true)
            }
        }
    }
}
