package com.wendigo.suzgec.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

interface Upstream {
    val label: String
    fun query(packet: ByteArray): ByteArray?
    fun close() {}
}

class DohUpstream(private val url: String) : Upstream {

    override val label: String = url

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

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .writeTimeout(4, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}

class UdpUpstream(private val server: String, private val timeoutMs: Int = 4000) : Upstream {

    override val label: String = "udp://$server"

    override fun query(packet: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            val address = InetAddress.getByName(server)
            val s = DatagramSocket()
            socket = s
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

    fun create(key: String, custom: String): Upstream {
        val url = when (key) {
            KEY_CLOUDFLARE_IP -> CLOUDFLARE_IP_URL
            KEY_ADGUARD -> ADGUARD_URL
            KEY_QUAD9 -> QUAD9_URL
            KEY_CLOUDFLARE -> CLOUDFLARE_URL
            KEY_GOOGLE -> GOOGLE_URL
            KEY_CUSTOM -> custom.trim()
            else -> CLOUDFLARE_IP_URL
        }
        val fallback = DohUpstream(CLOUDFLARE_IP_FALLBACK)
        return if (url.startsWith("https://")) {
            CompositeUpstream(DohUpstream(url), fallback)
        } else {
            fallback
        }
    }
}
