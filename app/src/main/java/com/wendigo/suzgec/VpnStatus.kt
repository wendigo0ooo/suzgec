package com.wendigo.suzgec

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress

object VpnStatus {

    data class Snapshot(
        val vpnActive: Boolean,
        val dnsServers: List<String>,
        val loopbackDns: Boolean
    ) {
        fun dnsText(): String = if (dnsServers.isEmpty()) "-" else dnsServers.joinToString(", ")
    }

    fun snapshot(context: Context): Snapshot {
        return try {
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: return Snapshot(false, emptyList(), false)
            val network = manager.activeNetwork ?: return Snapshot(false, emptyList(), false)
            val capabilities = manager.getNetworkCapabilities(network)
            val vpnActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            if (!vpnActive) return Snapshot(false, emptyList(), false)

            val properties = manager.getLinkProperties(network)
                ?: return Snapshot(true, emptyList(), false)
            val addresses = properties.dnsServers
            val loopback = addresses.any { it.isLoopbackAddress }
            Snapshot(
                vpnActive = true,
                dnsServers = addresses.mapNotNull { safeHost(it) },
                loopbackDns = loopback
            )
        } catch (_: Exception) {
            Snapshot(false, emptyList(), false)
        }
    }

    fun otherVpnActive(context: Context): Boolean =
        snapshot(context).vpnActive && !SuzgecVpnService.active

    fun tunnelPointsToUs(context: Context): Boolean {
        val snapshot = snapshot(context)
        return snapshot.loopbackDns || snapshot.dnsServers.any { it == LOOPBACK }
    }

    private fun safeHost(address: InetAddress): String? = try {
        address.hostAddress
    } catch (_: Exception) {
        null
    }

    private const val LOOPBACK = "127.0.0.1"
}
