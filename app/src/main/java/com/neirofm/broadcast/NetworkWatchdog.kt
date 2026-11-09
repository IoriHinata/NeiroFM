package com.neirofm.broadcast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock

enum class BroadcastMode { ONLINE, BUFFER_ONLY, OFFLINE_LOCAL }

class NetworkWatchdog(context: Context) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val prefs = app.getSharedPreferences("neirofm_network", Context.MODE_PRIVATE)
    @Volatile var validated: Boolean = hasValidatedInternet()
        private set
    @Volatile var offlineSince: Long = prefs.getLong("offline_since", 0L)
        private set
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refresh() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refresh() }
        override fun onLost(network: Network) { refresh() }
    }

    init {
        if (!validated && offlineSince == 0L) setOfflineNow()
        if (validated) clearOffline()
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
    }

    private fun hasValidatedInternet(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Synchronized fun refresh() {
        val nowOnline = hasValidatedInternet()
        if (nowOnline) clearOffline() else if (offlineSince == 0L) setOfflineNow()
        validated = nowOnline
    }

    private fun setOfflineNow() {
        offlineSince = System.currentTimeMillis()
        prefs.edit().putLong("offline_since", offlineSince).apply()
    }
    private fun clearOffline() {
        offlineSince = 0L
        prefs.edit().remove("offline_since").apply()
    }

    fun mode(onlineTtsConfigured: Boolean, bufferedMs: Long): BroadcastMode {
        refresh()
        if (validated && onlineTtsConfigured) return BroadcastMode.ONLINE
        val elapsed = if (offlineSince == 0L) 0L else System.currentTimeMillis() - offlineSince
        return if (!validated && elapsed >= FOUR_HOURS_MS) BroadcastMode.OFFLINE_LOCAL else BroadcastMode.BUFFER_ONLY
    }

    fun offlineDurationMs(): Long = if (offlineSince == 0L) 0L else (System.currentTimeMillis() - offlineSince).coerceAtLeast(0L)
    fun close() { runCatching { connectivity.unregisterNetworkCallback(callback) } }

    companion object { const val FOUR_HOURS_MS = 4L * 60L * 60L * 1000L }
}
