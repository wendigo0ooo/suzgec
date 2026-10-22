package com.wendigo.suzgec

import android.content.Context
import android.content.SharedPreferences

class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var upstreamKey: String
        get() = prefs.getString(KEY_UPSTREAM, "cloudflare_ip") ?: "cloudflare_ip"
        set(value) = prefs.edit().putString(KEY_UPSTREAM, value).apply()

    var customDoh: String
        get() = prefs.getString(KEY_CUSTOM_DOH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_DOH, value).apply()

    var blockMode: String
        get() = prefs.getString(KEY_BLOCK_MODE, MODE_ZERO) ?: MODE_ZERO
        set(value) = prefs.edit().putString(KEY_BLOCK_MODE, value).apply()

    var serviceWanted: Boolean
        get() = prefs.getBoolean(KEY_WANTED, false)
        set(value) = prefs.edit().putBoolean(KEY_WANTED, value).apply()

    var useBundled: Boolean
        get() = prefs.getBoolean(KEY_BUNDLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BUNDLED, value).apply()

    var mode: String
        get() = prefs.getString(KEY_MODE, null)
            ?: if (prefs.getBoolean(KEY_WANTED, false)) MODE_TUNNEL else MODE_VPN
        set(value) = prefs.edit().putString(KEY_MODE, value).apply()

    var lastClient: String
        get() = prefs.getString(KEY_LAST_CLIENT, "v2rayng") ?: "v2rayng"
        set(value) = prefs.edit().putString(KEY_LAST_CLIENT, value).apply()

    var userLists: Set<String>
        get() = prefs.getStringSet(KEY_USER_LISTS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_USER_LISTS, value).apply()

    companion object {
        const val PREFS = "suzgec_prefs"
        const val DEFAULT_PORT = 5353
        const val KEY_PORT = "port"
        const val KEY_UPSTREAM = "upstream"
        const val KEY_CUSTOM_DOH = "custom_doh"
        const val KEY_BLOCK_MODE = "block_mode"
        const val KEY_WANTED = "service_wanted"
        const val KEY_MODE = "protection_mode"
        const val MODE_VPN = "vpn"
        const val MODE_TUNNEL = "tunnel"
        const val KEY_BUNDLED = "use_bundled"
        const val KEY_LAST_CLIENT = "last_client"
        const val KEY_USER_LISTS = "user_lists"
        const val MODE_NXDOMAIN = "nxdomain"
        const val MODE_ZERO = "zero"
    }
}
