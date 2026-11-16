package com.naveen.videos

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.min

/**
 * Tiny HTTP/1.1 server embedded in the phone.
 *
 * It exposes the phone's video library (with byte-range streaming), thumbnails
 * and the live-mirror control endpoints consumed by the Linux app.
 */
class PhoneServer(
    private val context: Context,
    private val port: Int,
    private val onLiveStart: (LiveRequest) -> Unit,
    private val onLiveStop: () -> Unit,
    private val onLiveRecord: (Boolean) -> Unit
) {
    data class LiveRequest(
        val clientIp: String,
        val videoPort: Int,
        val audioPort: Int,
        val videoBitrate: Int,
        val transport: String
    )

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        acceptThread = Thread {
            while (running) {
                try {
                    val client = socket.accept() ?: break
                    Thread { handle(client) }.apply { isDaemon = true }.start()
                } catch (_: Throwable) {
                    if (!running) break
                }
            }
        }.apply {
            isDaemon = true
            name = "videos-http"
            start()
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        acceptThread = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
            val headers = readHeaders(input) ?: return
            val lines = headers.split("\r\n")
            if (lines.isEmpty()) return
            val requestLine = lines[0].split(" ")
            if (requestLine.size < 2) return
            val method = requestLine[0].uppercase()
            val target = requestLine[1]
            val headerMap = HashMap<String, String>()
            for (index in 1 until lines.size) {
                val line = lines[index]
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headerMap[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
            val uri = Uri.parse("http://phone$target")
            val path = uri.path ?: "/"
            val headOnly = method == "HEAD"

            when {
                path == "/api/info" -> sendJson(output, 200, infoJson())
                path == "/api/library" -> sendJson(output, 200, libraryJson())
                path == "/api/thumbnail" -> {
                    val id = uri.getQueryParameter("id")?.toLongOrNull()
                    val bytes = if (id != null) {
                        MediaLibrary.thumbnail(
                            context,
                            VideoItem(id, "", 0, 0, "video/mp4", 0, null)
                        )
                    } else {
                        null
                    }
                    if (bytes == null) {
                        sendJson(output, 404, JSONObject().put("error", "thumbnail not available"))
                    } else {
                        sendBytes(
                            output, 200, "image/jpeg", bytes, headOnly,
                            mapOf("Cache-Control" to "max-age=3600")
                        )
                    }
                }
                path == "/media" -> {
                    val id = uri.getQueryParameter("id")?.toLongOrNull()
                    if (id == null) {
                        sendJson(output, 400, JSONObject().put("error", "missing id"))
                    } else {
                        streamMedia(output, MediaLibrary.contentUri(id), headOnly, headerMap["range"])
                    }
                }
                path == "/api/live/status" -> sendJson(output, 200, LiveState.statusJson())
                path == "/api/live/stream.ts" -> {
                    if (headOnly) {
                        sendBytes(
                            output, 200, "video/mp2t", ByteArray(0), true,
                            mapOf("Cache-Control" to "no-cache, no-store")
                        )
                    } else {
                        streamLive(output)
                    }
                }
                path == "/api/live/sdp" -> {
                    val sdp = LiveState.sdp
                    if (sdp == null) {
                        sendJson(output, 404, JSONObject().put("error", "not streaming"))
                    } else {
                        sendBytes(
                            output, 200, "application/sdp",
                            sdp.toByteArray(Charsets.UTF_8), headOnly
                        )
                    }
                }
                path == "/api/live/start" -> {
                    val clientIp = client.inetAddress?.hostAddress ?: ""
                    val videoPort = uri.getQueryParameter("videoPort")?.toIntOrNull() ?: 5004
                    val audioPort = uri.getQueryParameter("audioPort")?.toIntOrNull() ?: 5006
                    val bitrate = uri.getQueryParameter("videoBitrate")?.toIntOrNull() ?: 8_000_000
                    val requestedMode = uri.getQueryParameter("mode")?.lowercase()
                    // `adb forward` connections arrive from loopback, so USB is
                    // also a sensible default when the caller omits the mode.
                    val transport = when {
                        requestedMode == "usb" || requestedMode == "tcp" ->
                            LiveState.TRANSPORT_USB
                        requestedMode == "wifi" || requestedMode == "rtp" ->
                            LiveState.TRANSPORT_WIFI
                        client.inetAddress?.isLoopbackAddress == true ->
                            LiveState.TRANSPORT_USB
                        else -> LiveState.TRANSPORT_WIFI
                    }
                    if (LiveState.state != LiveState.STREAMING) {
                        // A start request while streaming is just a status poll:
                        // it must not overwrite the active session's details.
                        LiveState.clientIp = clientIp
                        LiveState.videoPort = videoPort
                        LiveState.audioPort = audioPort
                        LiveState.transport = transport
                        LiveState.state = LiveState.REQUESTING
                        LiveState.message = "Waiting for screen-capture permission on the phone"
                        LiveState.sdp = null
                        onLiveStart(
                            LiveRequest(clientIp, videoPort, audioPort, bitrate, transport)
                        )
                        sendJson(
                            output, 200,
                            JSONObject().put("status", "requesting")
                                .put("mode", transport)
                                .put("client", clientIp)
                        )
                    } else {
                        sendJson(output, 200, LiveState.statusJson())
                    }
                }
                path == "/api/live/stop" -> {
                    LiveState.state = LiveState.STOPPING
                    onLiveStop()
                    sendJson(output, 200, JSONObject().put("status", "stopping"))
                }
                path == "/api/live/record" -> {
                    if (LiveState.state != LiveState.STREAMING) {
                        sendJson(output, 409, JSONObject().put("error", "not streaming"))
                    } else {
                        val on = uri.getQueryParameter("on") != "0"
                        onLiveRecord(on)
                        sendJson(output, 200, JSONObject().put("recording", on))
                    }
                }
                method == "OPTIONS" -> sendBytes(
                    output, 200, "text/plain", ByteArray(0), true,
                    mapOf("Allow" to "GET, HEAD, POST, OPTIONS")
                )
                else -> sendJson(output, 404, JSONObject().put("error", "not found"))
            }
            try {
                output.flush()
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
            // Client disconnects and malformed requests are normal.
        } finally {
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun streamLive(output: OutputStream) {
        val broadcaster = LiveState.ts
        if (broadcaster == null) {
            sendJson(output, 409, JSONObject().put("error", "live stream is not running"))
            return
        }
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/mp2t\r\n")
            append("Cache-Control: no-cache, no-store\r\n")
            append("Pragma: no-cache\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.flush()
        broadcaster.stream(output)
    }

    private fun streamMedia(
        output: OutputStream,
        uri: Uri,
        headOnly: Boolean,
        rangeHeader: String?
    ) {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Throwable) {
            null
        }
        if (descriptor == null) {
            sendJson(output, 404, JSONObject().put("error", "media not found"))
            return
        }
        try {
            val total = descriptor.statSize
            if (total <= 0) {
                sendJson(output, 500, JSONObject().put("error", "unknown media size"))
                return
            }
            val mime = context.contentResolver.getType(uri) ?: "video/mp4"
            var start = 0L
            var end = total - 1
            var partial = false
            if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                val spec = rangeHeader.removePrefix("bytes=").split(",").first().trim()
                val dash = spec.indexOf('-')
                if (dash >= 0) {
                    val left = spec.substring(0, dash).toLongOrNull()
                    val right = spec.substring(dash + 1).toLongOrNull()
                    if (left != null) {
                        start = left.coerceAtMost(total - 1)
                        end = (right ?: (total - 1)).coerceAtMost(total - 1)
                    } else if (right != null) {
                        start = (total - right).coerceAtLeast(0)
                        end = total - 1
                    }
                    partial = true
                }
            }
            if (start > end || start >= total) {
                val header = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                    "Content-Range: bytes */$total\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
                output.write(header.toByteArray(Charsets.US_ASCII))
                return
            }
            val length = end - start + 1
            val status = if (partial) "206 Partial Content" else "200 OK"
            val header = buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                append("Content-Type: ").append(mime).append("\r\n")
                append("Content-Length: ").append(length).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (partial) append("Content-Range: bytes ").append(start)
                    .append("-").append(end).append("/").append(total).append("\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray(Charsets.US_ASCII))
            if (headOnly) return
            val stream = FileInputStream(descriptor.fileDescriptor)
            stream.channel.position(start)
            val buffer = ByteArray(128 * 1024)
            var remaining = length
            while (remaining > 0) {
                val wanted = min(buffer.size.toLong(), remaining).toInt()
                val read = stream.read(buffer, 0, wanted)
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        } finally {
            try {
                descriptor.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun infoJson(): JSONObject = JSONObject().apply {
        val ip = NetworkUtils.localIpv4() ?: ""
        put("app", "videos")
        put("version", "2.0")
        put("model", Build.MODEL)
        put("brand", Build.BRAND)
        put("android", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
        put("url", "http://$ip:$port")
        put("live", LiveState.state)
        put("transport", LiveState.transport)
        put("transports", JSONArray().put("usb").put("wifi"))
        put("internalAudio", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    }

    private fun libraryJson(): JSONObject {
        val array = JSONArray()
        for (item in MediaLibrary.query(context)) array.put(item.toJson())
        return JSONObject().put("items", array)
    }

    private fun sendJson(output: OutputStream, code: Int, payload: JSONObject) {
        sendBytes(
            output, code, "application/json; charset=utf-8",
            payload.toString().toByteArray(Charsets.UTF_8), false
        )
    }

    private fun sendBytes(
        output: OutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val reason = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            416 -> "Range Not Satisfiable"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n")
            for ((key, value) in extraHeaders) append(key).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        if (!headOnly && body.isNotEmpty()) output.write(body)
    }

    private fun readHeaders(input: BufferedInputStream): String? {
        val builder = StringBuilder()
        var matched = 0
        val pattern = "\r\n\r\n"
        while (builder.length < 32768) {
            val value = input.read()
            if (value == -1) return null
            val char = value.toChar()
            builder.append(char)
            matched = if (char == pattern[matched]) {
                matched + 1
            } else if (char == '\r') {
                1
            } else {
                0
            }
            if (matched == pattern.length) return builder.toString()
        }
        return null
    }
}
