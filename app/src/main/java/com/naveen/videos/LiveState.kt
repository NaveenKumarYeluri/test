package com.naveen.videos

import org.json.JSONObject

/**
 * Shared live-mirror state between the UI, the HTTP server and the capture
 * service. All fields are volatile because they are read from network threads.
 */
object LiveState {
    const val IDLE = "idle"
    const val REQUESTING = "requesting"
    const val AWAITING_PERMISSION = "awaiting_permission"
    const val STREAMING = "streaming"
    const val STOPPING = "stopping"
    const val ERROR = "error"

    const val TRANSPORT_USB = "usb"
    const val TRANSPORT_WIFI = "wifi"

    @Volatile var state: String = IDLE
    @Volatile var message: String = ""
    @Volatile var sdp: String? = null
    @Volatile var clientIp: String? = null
    @Volatile var transport: String = TRANSPORT_WIFI
    @Volatile var videoPort: Int = 5004
    @Volatile var audioPort: Int = 5006
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0
    @Volatile var recording: Boolean = false
    @Volatile var debug: String = ""
    @Volatile var tsVideoCalls: Int = 0
    @Volatile var tsVideoFrames: Int = 0
    @Volatile var tsKeyframes: Int = 0

    /** Non-null only while a USB (TCP/MPEG-TS) mirror is running. */
    @Volatile var ts: TsBroadcaster? = null

    /**
     * The capture pipeline which currently owns this state.  A pipeline that
     * is being replaced must not reset the state of its successor.
     */
    @Volatile var session: Any? = null

    val isUsb: Boolean
        get() = transport == TRANSPORT_USB

    val isActive: Boolean
        get() = state == REQUESTING || state == AWAITING_PERMISSION ||
            state == STREAMING || state == STOPPING

    fun reset() {
        state = IDLE
        message = ""
        sdp = null
        width = 0
        height = 0
        recording = false
        transport = TRANSPORT_WIFI
        ts = null
        tsVideoCalls = 0
        tsVideoFrames = 0
        tsKeyframes = 0
    }

    fun statusJson(): JSONObject = JSONObject().apply {
        put("state", state)
        put("message", message)
        put("streaming", state == STREAMING)
        put("recording", recording)
        put("client", clientIp ?: JSONObject.NULL)
        put("transport", transport)
        put("usb", isUsb)
        put("videoPort", videoPort)
        put("audioPort", audioPort)
        put("width", width)
        put("height", height)
        put("sdpReady", sdp != null)
        val broadcaster = ts
        put("streamReady", broadcaster != null)
        put("streamPath", if (isUsb) "/api/live/stream.ts" else JSONObject.NULL)
        put("players", broadcaster?.clientCount ?: 0)
        put("tsCalls", tsVideoCalls)
        put("tsFrames", tsVideoFrames)
        put("tsKeyframes", tsKeyframes)
        put("tsChunksMuxed", broadcaster?.chunksMuxed ?: 0)
        put("tsBytesMuxed", broadcaster?.bytesMuxed ?: 0)
        put("tsChunksSent", broadcaster?.chunksSent ?: 0)
        put("tsBytesSent", broadcaster?.bytesSent ?: 0)
        put("tsBufferedChunks", broadcaster?.bufferedChunks ?: 0)
        put("tsBufferedBytes", broadcaster?.bufferedBytes ?: 0)
        put("tsWaitingClients", broadcaster?.waitingClients ?: 0)
        put("tsLastFrameMs", broadcaster?.lastFrameAgeMs ?: -1)
        put("debug", debug)
    }

    fun trace(step: String) {
        debug = (debug + step + " | ").takeLast(2000)
    }
}
