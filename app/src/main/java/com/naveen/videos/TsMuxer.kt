package com.naveen.videos

import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Tiny MPEG-TS muxer for the USB live mirror.
 *
 * ADB port forwarding only carries TCP, so the USB transport serves a live
 * MPEG-TS stream over HTTP instead of RTP/UDP. The muxer wraps the same
 * H.264/AAC encoder output used by the Wi-Fi path into PAT/PMT/PES packets.
 */
class TsMuxer(private val hasAudio: Boolean) {

    data class VideoFrame(val packets: ByteArray, val keyframe: Boolean)

    companion object {
        const val PACKET_SIZE = 188

        private const val PAT_PID = 0x0000
        private const val PMT_PID = 0x1000
        private const val VIDEO_PID = 0x0100
        private const val AUDIO_PID = 0x0101
        private const val PSI_INTERVAL_US = 400_000L

        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_AAC = 0x0F

        private val ACCESS_UNIT_DELIMITER = byteArrayOf(0x09, 0xF0.toByte())
    }

    private val continuity = HashMap<Int, Int>()
    private var lastPsiUs = Long.MIN_VALUE
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null

    /** PAT + PMT packets for clients which join while the stream is running. */
    @Synchronized
    fun psi(): ByteArray {
        val out = ByteArrayOutputStream(PACKET_SIZE * 2)
        writePsi(out)
        return out.toByteArray()
    }

    /** Caches SPS/PPS from a MediaCodec codec-config buffer. */
    @Synchronized
    fun setCodecConfig(data: ByteArray) {
        for (nal in splitAnnexB(data)) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> lastSps = nal
                8 -> lastPps = nal
            }
        }
    }

    /** Muxes one Annex-B access unit. Returns null when there is nothing to send. */
    @Synchronized
    fun writeVideo(data: ByteArray, ptsUs: Long): VideoFrame? {
        val units = splitAnnexB(data)
        if (units.isEmpty()) return null

        var keyframe = false
        for (nal in units) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> lastSps = nal
                8 -> lastPps = nal
                5 -> keyframe = true
            }
        }

        val payload = ByteArrayOutputStream(data.size + 64)
        writeNal(payload, ACCESS_UNIT_DELIMITER)
        if (keyframe) {
            // Some encoders only emit parameter sets in the CSD buffer, so
            // repeat the cached ones in front of every IDR access unit.
            lastSps?.let { writeNal(payload, it) }
            lastPps?.let { writeNal(payload, it) }
        }
        for (nal in units) {
            if (nal.isEmpty()) continue
            val type = nal[0].toInt() and 0x1F
            if (type == 7 || type == 8) continue
            writeNal(payload, nal)
        }

        val out = ByteArrayOutputStream(payload.size() + PACKET_SIZE * 4)
        if (lastPsiUs == Long.MIN_VALUE || ptsUs - lastPsiUs >= PSI_INTERVAL_US) {
            lastPsiUs = ptsUs
            writePsi(out)
        }
        val pes = buildPes(0xE0, ptsUs, payload.toByteArray(), video = true)
        writePes(out, VIDEO_PID, pes, ptsUs)
        return VideoFrame(out.toByteArray(), keyframe)
    }

    /** Muxes one raw AAC access unit (MediaCodec output, without ADTS header). */
    @Synchronized
    fun writeAudio(data: ByteArray, ptsUs: Long): ByteArray? {
        if (!hasAudio || data.isEmpty()) return null
        val framed = ByteArray(data.size + 7)
        writeAdtsHeader(framed, data.size)
        System.arraycopy(data, 0, framed, 7, data.size)
        val pes = buildPes(0xC0, ptsUs, framed, video = false)
        val out = ByteArrayOutputStream(PACKET_SIZE * 2)
        writePes(out, AUDIO_PID, pes, null)
        return out.toByteArray()
    }

    private fun writePsi(out: ByteArrayOutputStream) {
        writeSectionPacket(out, PAT_PID, patSection())
        writeSectionPacket(out, PMT_PID, pmtSection())
    }

    private fun patSection(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0x00) // transport_stream_id
        body.write(0x01)
        body.write(0xC1) // version 0, current/next = 1
        body.write(0x00) // section_number
        body.write(0x00) // last_section_number
        body.write(0x00) // program_number 1
        body.write(0x01)
        body.write(0xE0 or ((PMT_PID shr 8) and 0x1F)) // reserved '111' + PID
        body.write(PMT_PID and 0xFF)
        return section(0x00, body.toByteArray())
    }

    private fun pmtSection(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0x00) // program_number 1
        body.write(0x01)
        body.write(0xC1) // version 0, current/next = 1
        body.write(0x00) // section_number
        body.write(0x00) // last_section_number
        body.write(0xE0 or ((VIDEO_PID shr 8) and 0x1F)) // PCR PID
        body.write(VIDEO_PID and 0xFF)
        body.write(0xF0) // program_info_length = 0
        body.write(0x00)
        body.write(STREAM_TYPE_H264)
        body.write(0xE0 or ((VIDEO_PID shr 8) and 0x1F))
        body.write(VIDEO_PID and 0xFF)
        body.write(0xF0) // ES_info_length = 0
        body.write(0x00)
        if (hasAudio) {
            body.write(STREAM_TYPE_AAC)
            body.write(0xE0 or ((AUDIO_PID shr 8) and 0x1F))
            body.write(AUDIO_PID and 0xFF)
            body.write(0xF0)
            body.write(0x00)
        }
        return section(0x02, body.toByteArray())
    }

    private fun section(tableId: Int, body: ByteArray): ByteArray {
        val length = body.size + 4
        val out = ByteArray(3 + length)
        out[0] = tableId.toByte()
        // section_syntax_indicator = 1, '0', reserved '11'
        out[1] = (0xB0 or ((length shr 8) and 0x0F)).toByte()
        out[2] = (length and 0xFF).toByte()
        System.arraycopy(body, 0, out, 3, body.size)
        val crc = crc32(out, 0, out.size - 4)
        out[out.size - 4] = ((crc ushr 24) and 0xFF).toByte()
        out[out.size - 3] = ((crc ushr 16) and 0xFF).toByte()
        out[out.size - 2] = ((crc ushr 8) and 0xFF).toByte()
        out[out.size - 1] = (crc and 0xFF).toByte()
        return out
    }

    private fun writeSectionPacket(out: ByteArrayOutputStream, pid: Int, section: ByteArray) {
        val packet = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte() // payload_unit_start
        packet[2] = (pid and 0xFF).toByte()
        packet[3] = (0x10 or nextContinuity(pid)).toByte()
        packet[4] = 0x00 // pointer_field
        System.arraycopy(section, 0, packet, 5, section.size)
        out.write(packet)
    }

    private fun buildPes(streamId: Int, ptsUs: Long, payload: ByteArray, video: Boolean): ByteArray {
        val pts = (ptsUs * 90L / 1000L) and 0x1FFFFFFFFL
        val headerDataLength = 5
        val lengthField = if (video) 0 else 3 + headerDataLength + payload.size
        val out = ByteArray(9 + headerDataLength + payload.size)
        out[0] = 0x00
        out[1] = 0x00
        out[2] = 0x01
        out[3] = streamId.toByte()
        out[4] = ((lengthField shr 8) and 0xFF).toByte()
        out[5] = (lengthField and 0xFF).toByte()
        out[6] = 0x80.toByte() // '10' marker, no scrambling
        out[7] = 0x80.toByte() // PTS only
        out[8] = headerDataLength.toByte()
        out[9] = (0x21 or (((pts ushr 29) and 0x0E).toInt())).toByte()
        out[10] = ((pts ushr 22) and 0xFF).toByte()
        out[11] = ((((pts ushr 14) and 0xFE) or 0x01).toInt()).toByte()
        out[12] = ((pts ushr 7) and 0xFF).toByte()
        out[13] = ((((pts shl 1) and 0xFE) or 0x01).toInt()).toByte()
        System.arraycopy(payload, 0, out, 14, payload.size)
        return out
    }

    private fun writePes(out: ByteArrayOutputStream, pid: Int, pes: ByteArray, pcrUs: Long?) {
        var offset = 0
        var first = true
        while (offset < pes.size) {
            val withPcr = first && pcrUs != null
            val maxPayload = if (withPcr) PACKET_SIZE - 12 else PACKET_SIZE - 4
            val take = min(maxPayload, pes.size - offset)
            val adaptation = withPcr || take < PACKET_SIZE - 4
            val packet = ByteArray(PACKET_SIZE) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = ((if (first) 0x40 else 0x00) or ((pid shr 8) and 0x1F)).toByte()
            packet[2] = (pid and 0xFF).toByte()
            packet[3] = (((if (adaptation) 3 else 1) shl 4) or nextContinuity(pid)).toByte()
            var pos = 4
            if (adaptation) {
                val adaptationLength = 183 - take
                packet[pos++] = (adaptationLength and 0xFF).toByte()
                if (adaptationLength > 0) {
                    packet[pos++] = (if (withPcr) 0x10 else 0x00).toByte()
                    if (withPcr) {
                        writePcr(packet, pos, pcrUs!!)
                        pos += 6
                    }
                    while (pos < 5 + adaptationLength) packet[pos++] = 0xFF.toByte()
                }
            }
            System.arraycopy(pes, offset, packet, pos, take)
            out.write(packet)
            offset += take
            first = false
        }
    }

    private fun writePcr(packet: ByteArray, offset: Int, ptsUs: Long) {
        val base = (ptsUs * 90L / 1000L) and 0x1FFFFFFFFL
        packet[offset] = ((base ushr 25) and 0xFF).toByte()
        packet[offset + 1] = ((base ushr 17) and 0xFF).toByte()
        packet[offset + 2] = ((base ushr 9) and 0xFF).toByte()
        packet[offset + 3] = ((base ushr 1) and 0xFF).toByte()
        packet[offset + 4] = ((((base and 0x01) shl 7) or 0x7E).toInt()).toByte()
        packet[offset + 5] = 0x00
    }

    private fun writeAdtsHeader(out: ByteArray, aacSize: Int) {
        val frameLength = aacSize + 7
        val profile = 1 // AAC LC
        val frequencyIndex = 4 // 44100 Hz
        val channels = 2
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte()
        out[2] = ((profile shl 6) or (frequencyIndex shl 2) or (channels shr 2)).toByte()
        out[3] = (((channels and 3) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
        out[4] = ((frameLength shr 3) and 0xFF).toByte()
        out[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
    }

    private fun writeNal(out: ByteArrayOutputStream, nal: ByteArray) {
        if (nal.isEmpty()) return
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(nal, 0, nal.size)
    }

    private fun nextContinuity(pid: Int): Int {
        val value = continuity[pid] ?: 0
        continuity[pid] = (value + 1) and 0x0F
        return value
    }

    private fun splitAnnexB(data: ByteArray): ArrayList<ByteArray> {
        val units = ArrayList<ByteArray>()
        var start = -1
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) {
                if (start in 0 until i) addUnit(units, data, start, i)
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

    private fun crc32(data: ByteArray, offset: Int, length: Int): Int {
        var crc = -1
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 24)
            for (bit in 0 until 8) {
                crc = if (crc and 0x80000000.toInt() != 0) {
                    (crc shl 1) xor 0x04C11DB7
                } else {
                    crc shl 1
                }
            }
        }
        return crc
    }
}
