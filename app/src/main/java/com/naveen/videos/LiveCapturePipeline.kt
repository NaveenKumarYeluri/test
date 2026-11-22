package com.naveen.videos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * MediaProjection -> MediaCodec -> RTP pipeline used by [LiveStreamService].
 */
class LiveCapturePipeline(
    private val context: Context,
    private val projection: MediaProjection,
    private val clientIp: String?,
    private val videoPort: Int,
    private val audioPort: Int,
    private val videoBitrate: Int,
    private val transport: String,
    private val onFinished: (String?) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val recorder = LiveRecording(context)

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var socket: DatagramSocket? = null
    private var videoSender: RtpVideoSender? = null
    private var audioSender: RtpAudioSender? = null
    private var tsBroadcaster: TsBroadcaster? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var audioConfig: ByteArray? = null
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null

    /**
     * Keeps the display awake while mirroring.  When the phone screen turns
     * off MediaProjection stops producing frames, so the laptop picture would
     * freeze until the screen is woken again.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Set by the HTTP layer (possibly from another thread) and consumed by the
     * encoder thread.  Calling MediaCodec.setParameters() from the thread that
     * drains the codec avoids vendor-specific deadlocks on older MediaTek
     * devices.
     */
    @Volatile
    private var keyframeRequested = false

    /**
     * Set by the display listener when the phone rotates.  The video thread
     * consumes it and rebuilds the encoder/display at the new size, because a
     * VirtualDisplay keeps its original orientation and would letterbox the
     * rotated screen into a tiny strip otherwise.
     */
    @Volatile
    private var reconfigureRequested = false
    @Volatile
    private var displayWidth = 0
    @Volatile
    private var displayHeight = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
        }

        override fun onDisplayRemoved(displayId: Int) {
        }

        override fun onDisplayChanged(displayId: Int) {
            if (!running.get() || displayId != Display.DEFAULT_DISPLAY) return
            val (width, height) = displaySize()
            if (width != displayWidth || height != displayHeight) {
                displayWidth = width
                displayHeight = height
                LiveState.trace("rotation:${width}x$height")
                reconfigureRequested = true
            }
        }
    }

    private val isUsb: Boolean
        get() = transport == LiveState.TRANSPORT_USB

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            finish(null)
        }
    }

    fun start() {
        if (running.get()) return
        running.set(true)
        // Claim the shared state.  A pipeline which is replaced (or finishes
        // late) must not reset the state of its successor.
        LiveState.session = this
        LiveState.recording = false
        acquireWakeLock()
        try {
            if (isUsb) {
                startUsbTransport()
            } else {
                startWifiTransport()
            }
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
            val (initialWidth, initialHeight) = displaySize()
            displayWidth = initialWidth
            displayHeight = initialHeight
            registerDisplayListener()
            val hasAudio = setupAudio()
            if (isUsb) {
                val broadcaster = TsBroadcaster(hasAudio).apply {
                    onKeyframeRequest = { requestKeyFrame() }
                    onClientCountChanged = { count -> onPlayerCountChanged(count) }
                }
                tsBroadcaster = broadcaster
                LiveState.ts = broadcaster
            } else if (hasAudio) {
                val udp = socket ?: throw IllegalStateException("UDP socket not ready")
                val address = InetAddress.getByName(clientIp)
                audioSender = RtpAudioSender(
                    udp, address, audioPort, (Math.random() * Int.MAX_VALUE).toInt(), 44100
                )
            }
            setupVideo(initialWidth, initialHeight)
            startThreads()
            if (isUsb) {
                LiveState.state = LiveState.STREAMING
                LiveState.message = if (hasAudio) {
                    "USB ready · waiting for the Linux player"
                } else {
                    "USB ready · waiting for the Linux player (no internal audio on Android 9)"
                }
            } else {
                updateSdp()
                LiveState.message = "Waiting for the first video keyframe…"
            }
        } catch (t: Throwable) {
            fail(t)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = power.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Videos:live"
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
            LiveState.trace("wakeLock:acquired")
        } catch (t: Throwable) {
            LiveState.trace("wakeLock:${t.javaClass.simpleName}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    private fun startUsbTransport() {
        LiveState.transport = LiveState.TRANSPORT_USB
        LiveState.sdp = null
    }

    private fun startWifiTransport() {
        LiveState.transport = LiveState.TRANSPORT_WIFI
        LiveState.sdp = null
        val ip = clientIp
        if (ip.isNullOrBlank()) throw IllegalStateException("No Linux IP address")
        val address = InetAddress.getByName(ip)
        val udp = DatagramSocket()
        try {
            udp.sendBufferSize = 1 shl 20
        } catch (_: Throwable) {
        }
        socket = udp
        videoSender = RtpVideoSender(
            udp, address, videoPort, (Math.random() * Int.MAX_VALUE).toInt()
        ).apply {
            onParameterSets = { sps, pps ->
                lastSps = sps
                lastPps = pps
                updateSdp()
            }
        }
    }

    /**
     * Size of the physical display in its current rotation, capped to a 1440 px
     * long edge and aligned to 8 pixels for the encoder.
     */
    private fun displaySize(): Pair<Int, Int> {
        val point = Point()
        try {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            manager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(point)
        } catch (_: Throwable) {
        }
        var width = if (point.x > 0) point.x else context.resources.displayMetrics.widthPixels
        var height = if (point.y > 0) point.y else context.resources.displayMetrics.heightPixels
        val maxSize = 1440
        if (width >= height && width > maxSize) {
            height = height * maxSize / width
            width = maxSize
        } else if (height > width && height > maxSize) {
            width = width * maxSize / height
            height = maxSize
        }
        width = (width / 8) * 8
        height = (height / 8) * 8
        return width to height
    }

    private fun registerDisplayListener() {
        try {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            manager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            LiveState.trace("displayListener:${t.javaClass.simpleName}")
        }
    }

    private fun unregisterDisplayListener() {
        try {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            manager.unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        finish(null)
    }

    fun startRecording() {
        recorder.start()
        if (LiveState.recording) {
            LiveState.message = "Recording the live mirror to Movies/Videos"
        }
    }

    fun stopRecording() {
        val saved = recorder.stop()
        LiveState.recording = false
        LiveState.message = if (saved) {
            "Recording saved to Movies/Videos"
        } else {
            "Recording stopped (no file was saved)"
        }
    }

    private fun setupVideo(width: Int, height: Int) {
        val metrics = context.resources.displayMetrics
        if (width < 16 || height < 16) {
            throw IllegalStateException("Invalid display size ${width}x$height")
        }
        LiveState.trace("videoSetup:${width}x$height")

        val candidates = ArrayList<Pair<Int, Int>>()
        candidates.add(width to height)
        // Fallback for encoders which reject the native size.
        candidates.add(640 to 480)
        var lastError: Throwable? = null
        for ((candidateWidth, candidateHeight) in candidates) {
            var codec: MediaCodec? = null
            try {
                LiveState.trace("try:${candidateWidth}x$candidateHeight")
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, candidateWidth, candidateHeight
                )
                format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Mediatek's encoder refuses to configure unless the input
                // color format is declared as Surface up front.
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, 0x7F000789)
                format.setInteger(
                    MediaFormat.KEY_PROFILE,
                    android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
                format.setInteger(
                    MediaFormat.KEY_LEVEL,
                    android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel4
                )
                format.setInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                    candidateWidth * candidateHeight * 3 / 2
                )
                // Screen content can stay static for a long time.  Repeating
                // the previous frame keeps the TS pipe (and its timestamps)
                // alive instead of making a late USB player wait forever.
                try {
                    format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                } catch (_: Throwable) {
                }
                LiveState.trace("bitrate:${videoBitrate}")
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                videoCodec = codec
                inputSurface = surface
                videoWidth = candidateWidth
                videoHeight = candidateHeight
                LiveState.width = candidateWidth
                LiveState.height = candidateHeight
                LiveState.trace("codecStarted:${candidateWidth}x$candidateHeight")
                virtualDisplay = projection.createVirtualDisplay(
                    "Videos",
                    candidateWidth,
                    candidateHeight,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
                LiveState.trace("virtualDisplayReady")
                return
            } catch (t: Throwable) {
                lastError = t
                LiveState.trace("tryFailed:${candidateWidth}x$candidateHeight:${t.message}")
                try {
                    codec?.release()
                } catch (_: Throwable) {
                }
            }
        }
        throw lastError ?: IllegalStateException("No usable AVC encoder")
    }

    private fun setupAudio(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(capture)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 16384))
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            record.startRecording()
            audioRecord = record
            audioCodec = codec
            // AAC LC, 44100 Hz, stereo AudioSpecificConfig.
            audioConfig = byteArrayOf(0x12, 0x10)
            true
        } catch (_: Throwable) {
            releaseAudio()
            false
        }
    }

    private fun startThreads() {
        startVideoThread()
        if (audioCodec != null && audioRecord != null) {
            audioThread = Thread {
                audioLoop()
            }.apply {
                name = "videos-audio"
                start()
            }
        }
    }

    private fun startVideoThread() {
        videoThread = Thread {
            videoLoop()
        }.apply {
            name = "videos-video"
            start()
        }
    }

    /**
     * Runs on the video thread after a rotation.  The encoder and virtual
     * display are tied to one surface size, so both are recreated at the new
     * orientation and a fresh video thread takes over.
     */
    private fun reconfigureVideo() {
        LiveState.trace("reconfigureVideo")
        if (LiveState.recording) {
            try {
                recorder.stop()
            } catch (_: Throwable) {
            }
            LiveState.recording = false
            LiveState.message = "Recording stopped (screen rotated)"
        }
        releaseVideo()
        try {
            val (width, height) = displaySize()
            displayWidth = width
            displayHeight = height
            if (!isUsb) LiveState.sdp = null
            setupVideo(width, height)
            tsBroadcaster?.resetCache()
            requestKeyFrame()
            videoThread = null
            startVideoThread()
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun updateSdp() {
        if (isUsb) return
        val sps = lastSps ?: return
        val pps = lastPps ?: return
        val client = clientIp ?: return
        if (LiveState.sdp != null) return
        val ip = NetworkUtils.localIpv4() ?: client
        LiveState.sdp = SdpBuilder.build(ip, client, videoPort, audioPort, sps, pps, audioConfig)
        LiveState.state = LiveState.STREAMING
        LiveState.message = "Mirroring ${videoWidth}x${videoHeight} to $client"
    }

    private fun requestKeyFrame() {
        // Handled by the encoder thread on its next iteration.
        keyframeRequested = true
    }

    private fun onPlayerCountChanged(count: Int) {
        if (!isUsb || !running.get()) return
        LiveState.message = when {
            count > 1 -> "Mirroring ${videoWidth}x${videoHeight} over USB to $count players"
            count == 1 -> "Mirroring ${videoWidth}x${videoHeight} over USB"
            else -> "USB ready · waiting for the Linux player"
        }
    }

    private fun fail(t: Throwable) {
        val detail = t.message?.let { ": $it" } ?: ""
        val message = "Capture failed (${t.javaClass.simpleName})$detail"
        try {
            java.io.File(context.filesDir, "live_error.txt")
                .writeText(t.stackTraceToString())
        } catch (_: Throwable) {
        }
        LiveState.trace("pipe.fail:${t.javaClass.simpleName}$detail")
        if (LiveState.session === this) {
            LiveState.state = LiveState.ERROR
            LiveState.message = message
        }
        Thread {
            finish(message)
        }.start()
    }

    private fun finish(error: String?) {
        if (!finished.compareAndSet(false, true)) return
        LiveState.trace("pipe.finish:$error")
        running.set(false)
        releaseWakeLock()
        unregisterDisplayListener()
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        try {
            videoCodec?.signalEndOfInputStream()
        } catch (_: Throwable) {
        }
        join(videoThread)
        join(audioThread)
        tsBroadcaster?.close()
        if (LiveState.ts === tsBroadcaster) LiveState.ts = null
        tsBroadcaster = null
        releaseVideo()
        releaseAudio()
        try {
            recorder.stop()
        } catch (_: Throwable) {
        }
        val owner = LiveState.session === this
        if (owner) {
            LiveState.session = null
            LiveState.recording = false
        }
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        try {
            projection.unregisterCallback(callback)
        } catch (_: Throwable) {
        }
        try {
            projection.stop()
        } catch (_: Throwable) {
        }
        if (owner) {
            if (error != null) {
                LiveState.state = LiveState.ERROR
                LiveState.message = error
            } else {
                LiveState.reset()
            }
        }
        try {
            onFinished(error)
        } catch (_: Throwable) {
        }
    }

    private fun videoLoop() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        var lastKeyRequest = 0L
        while (running.get()) {
            if (reconfigureRequested) {
                reconfigureRequested = false
                reconfigureVideo()
                return
            }
            try {
                val now = SystemClock.elapsedRealtime()
                if (keyframeRequested || now - lastKeyRequest > 2000) {
                    keyframeRequested = false
                    try {
                        codec.setParameters(
                            Bundle().apply {
                                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                            }
                        )
                    } catch (_: Throwable) {
                    }
                    lastKeyRequest = now
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = codec.outputFormat
                    videoFormat = format
                    recorder.setVideoFormat(format)
                    val csd0 = csdBytes(format, "csd-0")
                    val csd1 = csdBytes(format, "csd-1")
                    if (csd0 != null && csd1 != null) {
                        val sps = stripStartCode(csd0)
                        val pps = stripStartCode(csd1)
                        lastSps = sps
                        lastPps = pps
                        if (isUsb) {
                            tsBroadcaster?.writeCodecConfig(csd0)
                            tsBroadcaster?.writeCodecConfig(csd1)
                        } else {
                            videoSender?.sendParameterSets(sps, pps, 0)
                        }
                        updateSdp()
                    }
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(data)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            parseParameterSets(data)
                            if (isUsb) tsBroadcaster?.writeCodecConfig(data)
                        } else {
                            if (isUsb) {
                                tsBroadcaster?.writeVideo(data, info.presentationTimeUs)
                            } else {
                                videoSender?.sendAnnexB(data, info.presentationTimeUs)
                            }
                            recorder.writeVideo(data, info)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            } catch (t: Throwable) {
                LiveState.trace("videoLoop:${t.javaClass.simpleName}")
                if (running.get()) fail(t)
                return
            }
        }
    }

    private fun audioLoop() {
        val codec = audioCodec ?: return
        val record = audioRecord ?: return
        val info = MediaCodec.BufferInfo()
        val direct = ByteBuffer.allocateDirect(32 * 1024)
        var samples = 0L
        while (running.get()) {
            try {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex)
                    if (input == null) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    } else {
                        val capacity = min(direct.capacity(), input.capacity())
                        direct.clear()
                        val read = record.read(direct, capacity)
                        if (read > 0) {
                            direct.position(0)
                            direct.limit(read)
                            input.clear()
                            input.put(direct)
                            val pts = samples * 1_000_000L / 44100L
                            codec.queueInputBuffer(inputIndex, 0, read, pts, 0)
                            samples += read / 4L
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        }
                    }
                }
                while (running.get()) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val format = codec.outputFormat
                        audioFormat = format
                        recorder.setAudioFormat(format)
                        continue
                    }
                    if (outputIndex < 0) break
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(data)
                        if (isUsb) {
                            tsBroadcaster?.writeAudio(data, info.presentationTimeUs)
                        } else {
                            audioSender?.sendFrame(data, info.presentationTimeUs)
                        }
                        recorder.writeAudio(data, info)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            } catch (t: Throwable) {
                if (running.get()) fail(t)
                return
            }
        }
    }

    private fun parseParameterSets(data: ByteArray) {
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) {
                val start = i + 3
                var end = data.size
                var j = start
                while (j + 2 < data.size) {
                    if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() &&
                        data[j + 2] == 1.toByte()
                    ) {
                        end = j
                        break
                    }
                    j++
                }
                var realEnd = end
                while (realEnd > start && data[realEnd - 1] == 0.toByte()) realEnd--
                if (realEnd > start) {
                    val nal = data.copyOfRange(start, realEnd)
                    when (nal[0].toInt() and 0x1F) {
                        7 -> lastSps = nal
                        8 -> lastPps = nal
                    }
                }
                if (end == data.size) break
                i = end
            } else {
                i++
            }
        }
        val sps = lastSps
        val pps = lastPps
        if (sps != null && pps != null) {
            videoSender?.sendParameterSets(sps, pps, 0)
            updateSdp()
        }
    }

    private fun csdBytes(format: MediaFormat, key: String): ByteArray? {
        return try {
            val buffer = format.getByteBuffer(key) ?: return null
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            bytes
        } catch (_: Throwable) {
            null
        }
    }

    private fun stripStartCode(data: ByteArray): ByteArray {
        return when {
            data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 0.toByte() && data[3] == 1.toByte() -> data.copyOfRange(4, data.size)
            data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 1.toByte() -> data.copyOfRange(3, data.size)
            else -> data
        }
    }

    private fun releaseVideo() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            videoCodec?.stop()
        } catch (_: Throwable) {
        }
        try {
            videoCodec?.release()
        } catch (_: Throwable) {
        }
        videoCodec = null
        try {
            inputSurface?.release()
        } catch (_: Throwable) {
        }
        inputSurface = null
        videoFormat = null
    }

    private fun releaseAudio() {
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }
        audioRecord = null
        try {
            audioCodec?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioCodec?.release()
        } catch (_: Throwable) {
        }
        audioCodec = null
        audioSender = null
        audioFormat = null
    }

    private fun join(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(1500)
        } catch (_: Throwable) {
        }
    }
}
