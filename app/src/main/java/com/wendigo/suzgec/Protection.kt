package com.wendigo.suzgec

import android.content.Context

object Protection {

    fun active(context: Context): Boolean =
        if (Store(context).mode == Store.MODE_VPN) SuzgecVpnService.active else FilterService.running

    fun loading(context: Context): Boolean =
        if (Store(context).mode == Store.MODE_VPN) SuzgecVpnService.loading else FilterService.loading

    fun lastError(context: Context): String? =
        if (Store(context).mode == Store.MODE_VPN) SuzgecVpnService.lastError else FilterService.lastError

    fun ruleCount(context: Context): Int =
        if (Store(context).mode == Store.MODE_VPN) SuzgecVpnService.ruleCount else FilterService.ruleCount

    fun sourceCount(context: Context): Int =
        if (Store(context).mode == Store.MODE_VPN) SuzgecVpnService.sourceCount else FilterService.sourceCount

    fun start(context: Context): Boolean {
        val store = Store(context)
        store.serviceWanted = true
        return if (store.mode == Store.MODE_VPN) {
            SuzgecVpnService.start(context)
        } else {
            FilterService.start(context)
            true
        }
    }

    fun stop(context: Context) {
        Store(context).serviceWanted = false
        SuzgecVpnService.stop(context, clearWanted = false)
        FilterService.stop(context, clearWanted = false)
    }

    fun stopForSwitch(context: Context) {
        SuzgecVpnService.stop(context, clearWanted = false)
        FilterService.stop(context, clearWanted = false)
    }

    fun reload(context: Context) {
        val store = Store(context)
        if (store.mode == Store.MODE_VPN) {
            if (SuzgecVpnService.active) SuzgecVpnService.reload(context)
        } else if (FilterService.running) {
            FilterService.reload(context)
        }
    }

    fun startAtBoot(context: Context) {
        val store = Store(context)
        if (!store.serviceWanted) return
        if (store.mode == Store.MODE_VPN) {
            val prepared = try {
                android.net.VpnService.prepare(context) == null
            } catch (_: Exception) {
                false
            }
            if (prepared) SuzgecVpnService.start(context)
        } else {
            FilterService.start(context)
        }
    }
}
