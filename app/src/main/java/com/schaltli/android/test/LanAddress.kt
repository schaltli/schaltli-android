package com.schaltli.android.test

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/**
 * This phone's IPv4 address on the network it uses right now - WiFi, a
 * cable, a USB adapter alike: the active network's own link address, not an
 * interface picked by its name (until 2026-10-14 `wlan*` went first, and a
 * mobile or VPN interface could come before `eth0`; schaltli-designer#70).
 */
fun lanAddress(context: Context): String? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = connectivity.activeNetwork ?: return null
    val properties = connectivity.getLinkProperties(network) ?: return null
    return properties.linkAddresses
        .map { it.address }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
}
