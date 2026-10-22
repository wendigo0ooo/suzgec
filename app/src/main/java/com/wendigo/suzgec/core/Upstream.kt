package com.wendigo.suzgec.core

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

interface Upstream {
    val label: String
    fun query(packet: ByteArray): ByteArray?
    fun close() {}
}

fun interface SocketProtector {
    fun protect(socket: Socket): Boolean

    fun protectDatagram(socket: DatagramSocket): Boolean = false
}

class DohUpstream(
    private val url: String,
    protector: SocketProtector? = null
) : Upstream {

    override val label: String = url

    private val client: OkHttpClient = buildClient(protector)

    override fun query(packet: ByteArray): ByteArray? {
        return try {
            val body = packet.toRequestBody(DNS_MEDIA)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("Accept", "application/dns-message")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                if (bytes.size < DnsWire.HEADER_SIZE) null
                else if (DnsWire.transactionId(bytes) != DnsWire.transactionId(packet)) null
                else bytes
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val DNS_MEDIA = "application/dns-message".toMediaType()

        private const val BOOTSTRAP_IP = "1.1.1.1"

        private val BOOTSTRAP = mapOf(
            "dns.adguard-dns.com" to listOf("94.140.14.14", "94.140.15.15"),
            "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112"),
            "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
            "dns.google" to listOf("8.8.8.8", "8.8.4.4")
        )

        private fun resolveBootstrap(
            hostname: String,
            protector: SocketProtector
        ): List<InetAddress> {
            BOOTSTRAP[hostname]?.let { fixed ->
                return fixed.mapNotNull {
                    try {
                        InetAddress.getByName(it)
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            var socket: DatagramSocket? = null
            try {
                val s = DatagramSocket()
                socket = s
                if (!protector.protectDatagram(s)) {
                    throw UnknownHostException(hostname)
                }
                s.soTimeout = 2000
                val query = DnsWire.buildQuery(hostname)
                s.send(DatagramPacket(query, query.size, InetAddress.getByName(BOOTSTRAP_IP), 53))
                val buffer = ByteArray(2048)
                val answer = DatagramPacket(buffer, buffer.size)
                s.receive(answer)
                val bytes = buffer.copyOf(answer.length)
                if (DnsWire.transactionId(bytes) != DnsWire.transactionId(query)) {
                    throw UnknownHostException(hostname)
                }
                val addresses = DnsWire.answerAddresses(bytes).mapNotNull {
                    try {
                        InetAddress.getByAddress(it)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (addresses.isEmpty()) throw UnknownHostException(hostname)
                return addresses
            } catch (e: UnknownHostException) {
                throw e
            } catch (_: Exception) {
                throw UnknownHostException(hostname)
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }

        private fun buildClient(protector: SocketProtector?): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .writeTimeout(4, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
            if (protector != null) {
                builder.socketFactory(ProtectingSocketFactory(protector))
                builder.dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        resolveBootstrap(hostname, protector)
                })
            }
            return builder.build()
        }
    }
}

private class ProtectingSocketFactory(
    private val protector: SocketProtector
) : SocketFactory() {

    override fun createSocket(): Socket = Socket().also { protector.protect(it) }

    override fun createSocket(host: String, port: Int): Socket =
        Socket(host, port).also { protector.protect(it) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        Socket(host, port, localHost, localPort).also { protector.protect(it) }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        Socket(host, port).also { protector.protect(it) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = Socket(address, port, localAddress, localPort).also { protector.protect(it) }
}

class UdpUpstream(
    private val server: String,
    private val timeoutMs: Int = 4000,
    private val protector: SocketProtector? = null
) : Upstream {

    override val label: String = "udp://$server"

    override fun query(packet: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            val address = InetAddress.getByName(server)
            val s = DatagramSocket()
            socket = s
            protector?.let { it.protectDatagram(s) }
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(packet, packet.size, address, 53))
            val buf = ByteArray(4096)
            val answer = DatagramPacket(buf, buf.size)
            s.receive(answer)
            val bytes = buf.copyOf(answer.length)
            if (DnsWire.transactionId(bytes) != DnsWire.transactionId(packet)) null else bytes
        } catch (_: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }
}

class CompositeUpstream(
    private val primary: Upstream,
    private val fallback: Upstream?
) : Upstream {

    override val label: String = primary.label

    override fun query(packet: ByteArray): ByteArray? {
        val first = primary.query(packet)
        if (first != null) return first
        return fallback?.query(packet)
    }

    override fun close() {
        primary.close()
        fallback?.close()
    }
}

object Upstreams {

    const val KEY_CLOUDFLARE_IP = "cloudflare_ip"
    const val KEY_ADGUARD = "adguard"
    const val KEY_QUAD9 = "quad9"
    const val KEY_CLOUDFLARE = "cloudflare"
    const val KEY_GOOGLE = "google"
    const val KEY_CUSTOM = "custom"

    private const val CLOUDFLARE_IP_URL = "https://1.1.1.1/dns-query"
    private const val CLOUDFLARE_IP_FALLBACK = "https://1.0.0.1/dns-query"
    private const val ADGUARD_URL = "https://dns.adguard-dns.com/dns-query"
    private const val QUAD9_URL = "https://dns.quad9.net/dns-query"
    private const val CLOUDFLARE_URL = "https://cloudflare-dns.com/dns-query"
    private const val GOOGLE_URL = "https://dns.google/dns-query"

    fun create(
        key: String,
        customUrl: String,
        protector: SocketProtector? = null
    ): Upstream {
        return when (key) {
            KEY_ADGUARD -> DohUpstream(ADGUARD_URL, protector)
            KEY_QUAD9 -> DohUpstream(QUAD9_URL, protector)
            KEY_CLOUDFLARE -> DohUpstream(CLOUDFLARE_URL, protector)
            KEY_GOOGLE -> DohUpstream(GOOGLE_URL, protector)
            KEY_CUSTOM -> DohUpstream(
                customUrl.ifBlank { CLOUDFLARE_IP_URL },
                protector
            )
            else -> CompositeUpstream(
                DohUpstream(CLOUDFLARE_IP_URL, protector),
                DohUpstream(CLOUDFLARE_IP_FALLBACK, protector)
            )
        }
    }

    fun presets(): List<Pair<String, String>> = listOf(
        KEY_CLOUDFLARE_IP to CLOUDFLARE_IP_URL,
        KEY_ADGUARD to ADGUARD_URL,
        KEY_QUAD9 to QUAD9_URL,
        KEY_CLOUDFLARE to CLOUDFLARE_URL,
        KEY_GOOGLE to GOOGLE_URL
    )

    fun labelFor(key: String, custom: String): String = when (key) {
        KEY_CLOUDFLARE_IP -> "Cloudflare (IP)"
        KEY_ADGUARD -> "AdGuard DNS"
        KEY_QUAD9 -> "Quad9"
        KEY_CLOUDFLARE -> "Cloudflare"
        KEY_GOOGLE -> "Google"
        KEY_CUSTOM -> custom.ifBlank { "Özel DoH" }
        else -> key
    }

}
