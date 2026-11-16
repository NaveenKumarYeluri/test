package com.naveen.videos

import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Broadcasts a small UDP beacon so the Linux app can discover the phone
 * automatically instead of asking the user to type an IP address.
 */
class BeaconBroadcaster(private val port: Int) {
    companion object {
        const val DISCOVERY_PORT = 8099
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                val udp = DatagramSocket()
                udp.broadcast = true
                socket = udp
                val address = InetAddress.getByName("255.255.255.255")
                while (running) {
                    val ip = NetworkUtils.localIpv4() ?: ""
                    if (ip.isNotEmpty()) {
                        val payload = JSONObject().apply {
                            put("app", "videos")
                            put("url", "http://$ip:$port")
                            put("model", Build.MODEL)
                            put("brand", Build.BRAND)
                            put("android", Build.VERSION.SDK_INT)
                        }.toString().toByteArray(Charsets.UTF_8)
                        if (payload.size <= 1400) {
                            try {
                                udp.send(DatagramPacket(payload, payload.size, address, DISCOVERY_PORT))
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {
                }
                socket = null
            }
        }.apply {
            name = "videos-beacon"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        try {
            thread?.interrupt()
        } catch (_: Throwable) {
        }
        thread = null
    }
}
