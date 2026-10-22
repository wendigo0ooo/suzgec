package com.wendigo.suzgec.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DnsServer(
    private val port: Int,
    private val zeroAddressMode: Boolean,
    private val upstream: Upstream,
    private val rulesProvider: () -> FilterRules,
    private val onResult: (host: String?, blocked: Boolean) -> Unit,
    private val onError: (String) -> Unit = {}
) {

    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var udpThread: Thread? = null
    private var tcpThread: Thread? = null

    private val workers: ExecutorService = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "suzgec-dns").apply { isDaemon = true }
    }
    private val tcpWorkers: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "suzgec-tcpc").apply { isDaemon = true }
    }

    val boundPort: Int get() = udpSocket?.localPort ?: port

    fun start() {
        if (running.get()) return
        val address = InetAddress.getByName("127.0.0.1")
        val udp = DatagramSocket(null)
        try {
            udp.reuseAddress = true
            udp.bind(InetSocketAddress(address, port))
        } catch (e: Exception) {
            try {
                udp.close()
            } catch (_: Exception) {
            }
            throw e
        }
        val tcp = ServerSocket()
        try {
            tcp.reuseAddress = true
            tcp.bind(InetSocketAddress(address, port))
        } catch (e: Exception) {
            try {
                udp.close()
            } catch (_: Exception) {
            }
            try {
                tcp.close()
            } catch (_: Exception) {
            }
            throw e
        }
        udpSocket = udp
        tcpSocket = tcp
        running.set(true)
        udpThread = Thread({ udpLoop(udp) }, "suzgec-udp").also {
            it.isDaemon = true
            it.start()
        }
        tcpThread = Thread({ tcpLoop(tcp) }, "suzgec-tcpl").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        try {
            tcpSocket?.close()
        } catch (_: Exception) {
        }
        udpSocket = null
        tcpSocket = null
        workers.shutdownNow()
        tcpWorkers.shutdownNow()
        try {
            upstream.close()
        } catch (_: Exception) {
        }
    }

    fun handle(query: ByteArray, length: Int): ByteArray? {
        if (length < DnsWire.HEADER_SIZE) return null
        if (DnsWire.isResponse(query, length)) return null

        val questionEnd = DnsWire.questionEnd(query, length)
        if (questionEnd == null) {
            onError("Bozuk DNS sorgusu")
            return DnsWire.buildError(query, length, 1)
        }
        val host = DnsWire.questionName(query, length)
        if (host == null) {
            onError("Sorgu adı okunamadı")
            return DnsWire.buildError(query, length, 1)
        }

        val blocked = try {
            rulesProvider().isBlocked(host)
        } catch (_: Exception) {
            false
        }
        onResult(host, blocked)

        if (blocked) {
            return if (zeroAddressMode) {
                DnsWire.buildAddressAnswer(query, length, byteArrayOf(0, 0, 0, 0))
                    ?: DnsWire.buildError(query, length, DnsWire.RCODE_NXDOMAIN)
            } else {
                DnsWire.buildError(query, length, DnsWire.RCODE_NXDOMAIN)
            }
        }

        val packet = if (query.size == length) query else query.copyOf(length)
        val answer = try {
            upstream.query(packet)
        } catch (_: Exception) {
            null
        }
        if (answer == null || answer.size < DnsWire.HEADER_SIZE) {
            return DnsWire.buildError(query, length, 2)
        }
        return answer
    }

    private fun udpLoop(socket: DatagramSocket) {
        val buffer = ByteArray(4096)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                if (!running.get()) break
                continue
            }
            val query = buffer.copyOf(packet.length)
            val address = packet.address
            val clientPort = packet.port
            workers.execute {
                val response = handle(query, query.size) ?: return@execute
                try {
                    socket.send(DatagramPacket(response, response.size, address, clientPort))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun tcpLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                if (!running.get()) break
                continue
            }
            tcpWorkers.execute { handleTcp(client) }
        }
    }

    private fun handleTcp(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 10000
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val length = try {
                    input.readUnsignedShort()
                } catch (_: Exception) {
                    return
                }
                if (length <= 0) return
                val query = ByteArray(length)
                input.readFully(query)
                val response = handle(query, query.size) ?: return
                output.writeShort(response.size)
                output.write(response)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }
}
