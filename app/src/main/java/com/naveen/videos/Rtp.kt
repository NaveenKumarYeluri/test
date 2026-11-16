package com.naveen.videos

import android.util.Base64
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.min

/**
 * Minimal RTP packetizers for H.264 (RFC 6184) and AAC (RFC 3640).
 *
 * The Linux companion fetches the SDP produced by [SdpBuilder] and hands it to
 * VLC, which listens on the advertised UDP ports.
 */
class RtpVideoSender(
    private val socket: DatagramSocket,
    private val address: InetAddress,
    private val port: Int,
    private val ssrc: Int
) {
    companion object {
        const val PAYLOAD_TYPE = 96
        private const val MTU = 1400
    }

    private var sequence = (Math.random() * 65535).toInt()
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    private var parameterSetsReported = false

    /** Called once when both SPS and PPS have been observed in the stream. */
    var onParameterSets: ((ByteArray, ByteArray) -> Unit)? = null

    fun sendAnnexB(data: ByteArray, ptsUs: Long) {
        val units = splitAnnexB(data)
        if (units.isEmpty()) return

        var hasSps = false
        var hasPps = false
        var hasIdr = false
        for (nal in units) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                5 -> hasIdr = true
                7 -> hasSps = true
                8 -> hasPps = true
            }
        }
        // Repeat SPS/PPS before every IDR so a receiver that joins late can
        // start decoding even if it missed the first parameter sets.
        if (hasIdr && !hasSps && !hasPps) {
            lastSps?.let { sendNal(it, ptsUs, marker = false) }
            lastPps?.let { sendNal(it, ptsUs, marker = false) }
        }

        for ((index, nal) in units.withIndex()) {
            if (nal.isEmpty()) continue
            val type = nal[0].toInt() and 0x1F
            if (type == 7) lastSps = nal
            if (type == 8) lastPps = nal
            if (type == 9) continue // access unit delimiter
            sendNal(nal, ptsUs, marker = index == units.lastIndex)
        }

        val sps = lastSps
        val pps = lastPps
        if (!parameterSetsReported && sps != null && pps != null) {
            parameterSetsReported = true
            onParameterSets?.invoke(sps, pps)
        }
    }

    /** Re-send cached SPS/PPS so late joiners can start decoding. */
    fun sendParameterSets(sps: ByteArray, pps: ByteArray, ptsUs: Long) {
        lastSps = sps
        lastPps = pps
        sendNal(sps, ptsUs, marker = false)
        sendNal(pps, ptsUs, marker = false)
    }

    private fun sendNal(nal: ByteArray, ptsUs: Long, marker: Boolean) {
        if (nal.isEmpty()) return
        val timestamp = ((ptsUs * 90L) / 1000L).toInt()
        if (nal.size <= MTU - 12) {
            val packet = header(nal.size, marker, timestamp)
            System.arraycopy(nal, 0, packet, 12, nal.size)
            send(packet)
            return
        }

        val maxFragment = MTU - 12 - 2
        var offset = 1
        var first = true
        while (offset < nal.size) {
            val size = min(maxFragment, nal.size - offset)
            val last = offset + size >= nal.size
            val packet = header(2 + size, marker && last, timestamp)
            packet[12] = ((nal[0].toInt() and 0xE0) or 28).toByte()
            packet[13] = (((if (first) 0x80 else 0) or
                (if (last) 0x40 else 0) or (nal[0].toInt() and 0x1F))).toByte()
            System.arraycopy(nal, offset, packet, 14, size)
            send(packet)
            offset += size
            first = false
        }
    }

    private fun header(payloadSize: Int, marker: Boolean, timestamp: Int): ByteArray {
        val packet = ByteArray(12 + payloadSize)
        packet[0] = 0x80.toByte()
        packet[1] = ((if (marker) 0x80 else 0) or PAYLOAD_TYPE).toByte()
        packet[2] = ((sequence shr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        packet[4] = ((timestamp ushr 24) and 0xFF).toByte()
        packet[5] = ((timestamp ushr 16) and 0xFF).toByte()
        packet[6] = ((timestamp ushr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        packet[8] = ((ssrc ushr 24) and 0xFF).toByte()
        packet[9] = ((ssrc ushr 16) and 0xFF).toByte()
        packet[10] = ((ssrc ushr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
        sequence = (sequence + 1) and 0xFFFF
        return packet
    }

    private fun send(packet: ByteArray) {
        try {
            socket.send(DatagramPacket(packet, packet.size, address, port))
        } catch (_: Throwable) {
            // Network hiccups must not kill the capture pipeline.
        }
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val units = ArrayList<ByteArray>()
        var start = -1
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) {
                if (start >= 0) addUnit(units, data, start, i)
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (start in 0 until data.size) addUnit(units, data, start, data.size)
        if (units.isEmpty() && data.isNotEmpty()) {
            val type = data[0].toInt() and 0x1F
            if (type in 1..23) units.add(data.copyOf())
        }
        return units
    }

    private fun addUnit(units: MutableList<ByteArray>, data: ByteArray, start: Int, end: Int) {
        var realEnd = end
        while (realEnd > start && data[realEnd - 1] == 0.toByte()) realEnd--
        if (realEnd > start) units.add(data.copyOfRange(start, realEnd))
    }
}

class RtpAudioSender(
    private val socket: DatagramSocket,
    private val address: InetAddress,
    private val port: Int,
    private val ssrc: Int,
    private val sampleRate: Int
) {
    companion object {
        const val PAYLOAD_TYPE = 97
    }

    private var sequence = (Math.random() * 65535).toInt()

    fun sendFrame(aac: ByteArray, ptsUs: Long) {
        if (aac.isEmpty()) return
        val timestamp = ((ptsUs * sampleRate) / 1_000_000L).toInt()
        val packet = ByteArray(16 + aac.size)
        packet[0] = 0x80.toByte()
        packet[1] = (0x80 or PAYLOAD_TYPE).toByte() // marker
        packet[2] = ((sequence shr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        packet[4] = ((timestamp ushr 24) and 0xFF).toByte()
        packet[5] = ((timestamp ushr 16) and 0xFF).toByte()
        packet[6] = ((timestamp ushr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        packet[8] = ((ssrc ushr 24) and 0xFF).toByte()
        packet[9] = ((ssrc ushr 16) and 0xFF).toByte()
        packet[10] = ((ssrc ushr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
        sequence = (sequence + 1) and 0xFFFF
        // AU-headers-length (16 bits) followed by a 13-bit size / 3-bit index.
        packet[12] = 0
        packet[13] = 16
        val auHeader = (aac.size shl 3) and 0xFFFF
        packet[14] = ((auHeader shr 8) and 0xFF).toByte()
        packet[15] = (auHeader and 0xF8).toByte()
        System.arraycopy(aac, 0, packet, 16, aac.size)
        try {
            socket.send(DatagramPacket(packet, packet.size, address, port))
        } catch (_: Throwable) {
        }
    }
}

object SdpBuilder {
    fun build(
        localIp: String,
        clientIp: String,
        videoPort: Int,
        audioPort: Int,
        sps: ByteArray,
        pps: ByteArray,
        audioConfig: ByteArray?
    ): String {
        val profile = if (sps.size >= 4) {
            sps.copyOfRange(1, 4).joinToString("") { "%02x".format(it) }
        } else {
            "42e01f"
        }
        val spsBase64 = Base64.encodeToString(sps, Base64.NO_WRAP)
        val ppsBase64 = Base64.encodeToString(pps, Base64.NO_WRAP)
        val sdp = StringBuilder()
        sdp.append("v=0\r\n")
        sdp.append("o=- 0 0 IN IP4 ").append(localIp).append("\r\n")
        sdp.append("s=Videos Live\r\n")
        sdp.append("c=IN IP4 ").append(clientIp).append("\r\n")
        sdp.append("t=0 0\r\n")
        sdp.append("a=tool:Videos\r\n")
        sdp.append("m=video ").append(videoPort).append(" RTP/AVP 96\r\n")
        sdp.append("a=rtpmap:96 H264/90000\r\n")
        sdp.append("a=fmtp:96 packetization-mode=1;profile-level-id=")
            .append(profile)
            .append(";sprop-parameter-sets=")
            .append(spsBase64).append(",").append(ppsBase64).append("\r\n")
        sdp.append("a=sendonly\r\n")
        if (audioConfig != null && audioConfig.isNotEmpty()) {
            val configHex = audioConfig.joinToString("") { "%02x".format(it) }
            sdp.append("m=audio ").append(audioPort).append(" RTP/AVP 97\r\n")
            sdp.append("a=rtpmap:97 MPEG4-GENERIC/44100/2\r\n")
            sdp.append("a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;")
                .append("sizelength=13;indexlength=3;indexdeltalength=3;config=")
                .append(configHex).append("\r\n")
            sdp.append("a=sendonly\r\n")
        }
        return sdp.toString()
    }
}
