package com.naveen.videos

import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Fans the live MPEG-TS stream out to one or more HTTP clients.
 *
 * USB players reach the phone through `adb forward`, which is TCP-only.
 * Encoded frames are queued per client so a slow player can never stall the
 * MediaCodec capture threads; clients that fall too far behind are dropped.
 *
 * The muxed packets since the most recent keyframe are cached as a GOP.  A
 * player that connects later is primed with that cache instead of waiting for
 * the next IDR, which makes starting a mirror instant (and keeps working when
 * the phone screen is static and the encoder pauses).
 */
class TsBroadcaster(hasAudio: Boolean) {
    companion object {
        private const val MAX_QUEUED_CHUNKS = 512
        private const val MAX_GOP_BYTES = 12 * 1024 * 1024
        private val END = ByteArray(0)
        private const val CRLF = "\r\n"
    }

    private val lock = Any()
    private val muxer = TsMuxer(hasAudio)
    private val clients = LinkedHashSet<TsClient>()
    private val gop = ArrayDeque<ByteArray>()
    private var gopBytes = 0L
    private var gopHasKeyframe = false
    private var closed = false

    /** Diagnostics surfaced through `/api/live/status`. */
    @Volatile var chunksMuxed = 0L
        private set
    @Volatile var bytesMuxed = 0L
        private set
    @Volatile var waitingClients = 0
        private set
    private val sentChunks = AtomicLong(0L)
    private val sentBytes = AtomicLong(0L)
    private val lastMuxAt = AtomicLong(0L)

    val chunksSent: Long
        get() = sentChunks.get()

    val bytesSent: Long
        get() = sentBytes.get()

    /** Called when a new player connects so the encoder emits an IDR frame. */
    var onKeyframeRequest: (() -> Unit)? = null
    var onClientCountChanged: ((Int) -> Unit)? = null

    val clientCount: Int
        get() = synchronized(lock) { clients.size }

    val bufferedChunks: Int
        get() = synchronized(lock) { gop.size }

    val bufferedBytes: Long
        get() = synchronized(lock) { gopBytes }

    /** Milliseconds since the last muxed video/audio chunk, or -1 before the first one. */
    val lastFrameAgeMs: Long
        get() {
            val last = lastMuxAt.get()
            if (last == 0L) return -1
            return (System.nanoTime() - last) / 1_000_000L
        }

    fun writeCodecConfig(data: ByteArray) {
        synchronized(lock) {
            if (!closed) muxer.setCodecConfig(data)
        }
    }

    fun writeVideo(data: ByteArray, ptsUs: Long) {
        synchronized(lock) {
            if (closed) return
            LiveState.tsVideoCalls++
            val frame = muxer.writeVideo(data, ptsUs) ?: return
            LiveState.tsVideoFrames++
            if (frame.keyframe) LiveState.tsKeyframes++
            recordMuxed(frame.packets)
            cacheForNewClients(frame.packets, frame.keyframe)
            for (client in clients) {
                if (client.waitingForKeyframe) {
                    if (!frame.keyframe) continue
                    client.waitingForKeyframe = false
                }
                client.enqueue(frame.packets)
            }
            updateWaitingClients()
        }
    }

    fun writeAudio(data: ByteArray, ptsUs: Long) {
        synchronized(lock) {
            if (closed) return
            val packets = muxer.writeAudio(data, ptsUs) ?: return
            recordMuxed(packets)
            // Audio is not cached: a GOP can contain hundreds of tiny AAC
            // packets, and priming them would overflow a new client's queue.
            for (client in clients) client.enqueue(packets)
        }
    }

    /**
     * Blocks the calling HTTP handler thread, forwarding the live stream using
     * HTTP/1.1 chunked transfer encoding until the player disconnects or the
     * capture session ends.
     */
    fun stream(output: OutputStream) {
        val client = TsClient()
        var count: Int
        synchronized(lock) {
            if (closed) {
                client.finish()
                count = 0
            } else {
                clients.add(client)
                client.enqueue(muxer.psi())
                if (gopHasKeyframe && gop.isNotEmpty()) {
                    for (chunk in gop) client.enqueue(chunk)
                    client.waitingForKeyframe = false
                }
                count = clients.size
                updateWaitingClients()
            }
        }
        if (count > 0) {
            onClientCountChanged?.invoke(count)
            // A primed client already has a complete GOP.  Still ask for a
            // fresh IDR so what it sees is current, but do not block the
            // response on it.
            onKeyframeRequest?.invoke()
        }

        try {
            while (true) {
                val chunk = client.queue.poll(500, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    if (client.isFinished) break
                    continue
                }
                if (chunk.isEmpty()) break
                output.write(chunkHeader(chunk.size).toByteArray(Charsets.US_ASCII))
                output.write(chunk)
                output.write(CRLF.toByteArray(Charsets.US_ASCII))
                output.flush()
                sentChunks.incrementAndGet()
                sentBytes.addAndGet(chunk.size.toLong())
                client.sentBytes += chunk.size.toLong()
            }
            output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (_: IOException) {
            // Player closed the connection.
        } finally {
            client.finish()
            val remaining = synchronized(lock) {
                clients.remove(client)
                updateWaitingClients()
                clients.size
            }
            onClientCountChanged?.invoke(remaining)
        }
    }

    fun close() {
        val remaining = synchronized(lock) {
            if (closed) return
            closed = true
            for (client in clients.toList()) {
                client.finish()
                clients.remove(client)
            }
            gop.clear()
            gopBytes = 0
            gopHasKeyframe = false
            updateWaitingClients()
            clients.size
        }
        onClientCountChanged?.invoke(remaining)
    }

    private fun recordMuxed(packets: ByteArray) {
        chunksMuxed++
        bytesMuxed += packets.size.toLong()
        lastMuxAt.set(System.nanoTime())
    }

    /**
     * Keeps the packets from the latest keyframe onwards.  The cache is only
     * valid when it starts with an IDR (and its SPS/PPS), so it is dropped as
     * a whole if it grows past [MAX_GOP_BYTES] before the next keyframe.
     */
    private fun cacheForNewClients(packets: ByteArray, keyframe: Boolean) {
        if (keyframe) {
            gop.clear()
            gop.addLast(packets)
            gopBytes = packets.size.toLong()
            gopHasKeyframe = true
            return
        }
        if (!gopHasKeyframe || gop.isEmpty()) return
        gop.addLast(packets)
        gopBytes += packets.size.toLong()
        if (gopBytes > MAX_GOP_BYTES) {
            gop.clear()
            gopBytes = 0
            gopHasKeyframe = false
        }
    }

    private fun updateWaitingClients() {
        var waiting = 0
        for (client in clients) if (client.waitingForKeyframe) waiting++
        waitingClients = waiting
    }

    private fun chunkHeader(size: Int): String = Integer.toHexString(size) + CRLF

    private class TsClient {
        val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUED_CHUNKS)

        @Volatile
        var waitingForKeyframe = true

        @Volatile
        var sentBytes = 0L

        @Volatile
        var isFinished = false
            private set

        fun enqueue(data: ByteArray) {
            if (isFinished) return
            if (!queue.offer(data)) {
                // The player is too slow to keep up with the live stream.
                finish()
            }
        }

        fun finish() {
            if (isFinished) return
            isFinished = true
            queue.clear()
            queue.offer(END)
        }
    }
}
