package com.jarvis.edge.setup

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether a download may run now. [wifiOnly] is the user's switch: when on, only unmetered networks count. */
interface NetworkGate {
    fun state(): NetState
    var wifiOnly: Boolean
}

/** The phone's active network, read through ConnectivityManager. Not unit-tested (Android classes). */
class AndroidNetworkGate(private val context: Context, private val prefs: SharedPreferences) : NetworkGate {

    override var wifiOnly: Boolean
        get() = prefs.getBoolean(PREF_WIFI_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(PREF_WIFI_ONLY, value).apply()
        }

    override fun state(): NetState {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetState.OFFLINE
        return if (wifiOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetState.WAITING_FOR_WIFI
        } else {
            NetState.ALLOWED
        }
    }

    private companion object {
        const val PREF_WIFI_ONLY = "setup_wifi_only"
    }
}
