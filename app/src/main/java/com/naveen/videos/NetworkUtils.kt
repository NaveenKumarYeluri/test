package com.naveen.videos

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /** Returns the most likely LAN IPv4 address of this device. */
    fun localIpv4(): String? {
        return try {
            val candidates = ArrayList<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (address in iface.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress ?: continue
                        if (host.startsWith("169.254.")) continue
                        candidates.add(host)
                        if (iface.name.startsWith("wlan")) return host
                    }
                }
            }
            candidates.firstOrNull { it.startsWith("192.168.") }
                ?: candidates.firstOrNull { it.startsWith("10.") }
                ?: candidates.firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }
}
