package com.naveen.videos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Build
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private val captureRequestCode = 1001

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var captureSurface: Surface? = null
    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var audioTrackIndex = -1
    private var isCapturing = false

    private var captureFile: File? = null

    private val stopRequested = AtomicBoolean(false)

    private lateinit var captureButton: Button
    private lateinit var statusText: TextView

    private val handler = Handler()

    private companion object {
        private const val LINUX_SERVER_HOST = "192.168.1.100" // TODO: IP of the Linux host machine
        private const val LINUX_SERVER_PORT = 8000
    }

    private val captureCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            // MVP: ignore region resize
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            // MVP: ignore region visibility change
        }

        override fun onStop() {
            stopCapture()
        }
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        captureButton = findViewById(R.id.capture_button)
        statusText = findViewById(R.id.status_text)

        captureButton.setOnClickListener { startCapture() }
    }

    private fun startCapture() {
        if (isCapturing) return
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            captureRequestCode
        )
    }

    protected override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == captureRequestCode && resultCode == RESULT_OK) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = data ?: return@onActivityResult
            mediaProjection = projectionManager.getMediaProjection(resultCode, intent)
            mediaProjection?.registerCallback(captureCallback, handler)
            startCaptureSession()
        } else {
            statusText.text = "Capture not started"
        }
    }

    private fun startCaptureSession() {
        val projection = mediaProjection ?: return
        if (isCapturing) return
        isCapturing = true
        stopRequested.set(false)

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.coerceAtLeast(320)
        val height = displayMetrics.heightPixels.coerceAtLeast(240)

        val videoFormat = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        }

        val mediaCodec = MediaCodec.createEncoderByType("video/avc")
        mediaCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val captureSurface = mediaCodec.createInputSurface()
        mediaCodec.start()

        captureFile = File(getExternalFilesDir(null), "capture_${System.currentTimeMillis()}.mp4")
        val mediaMuxer = MediaMuxer(
            captureFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        val videoTrackIndex = mediaMuxer.addTrack(videoFormat)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
                val record = AudioRecord.Builder().setAudioPlaybackCaptureConfig(captureConfig).build()
                record.startRecording()
                audioRecord = record

                val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44_100, 2).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
                }
                val audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioEncoder.start()
                audioCodec = audioEncoder
                audioTrackIndex = mediaMuxer.addTrack(audioFormat)
            } catch (t: Throwable) {
                audioRecord = null
                audioCodec = null
                audioTrackIndex = -1
            }
        }

        mediaMuxer.start()

        virtualDisplay = projection.createVirtualDisplay(
            "VideosCapture",
            width,
            height,
            displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            captureSurface,
            null,
            handler
        )

        this.mediaCodec = mediaCodec
        this.mediaMuxer = mediaMuxer
        this.captureSurface = captureSurface
        statusText.text = "Capturing…"

        Thread {
            val c = mediaCodec ?: return@Thread
            val audioEnc = audioCodec ?: return@Thread
            val record = audioRecord ?: return@Thread
            val bufferInfo = MediaCodec.BufferInfo()
            val audioBufferInfo = MediaCodec.BufferInfo()
            var endOfStream = false
            val audioInBuffer = ByteBuffer.allocate(4096)
            var audioTimestamp = 0L
            while (!endOfStream && !stopRequested.get()) {
                val outIndex = c.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val encodedData = c.getOutputBuffer(outIndex)
                    if (encodedData != null) {
                        mediaMuxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    c.releaseOutputBuffer(outIndex, true)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        endOfStream = true
                    }
                }

                val audioInIndex = audioEnc.dequeueInputBuffer(10_000)
                if (audioInIndex >= 0) {
                    val audioBuf = audioEnc.getInputBuffer(audioInIndex) ?: return@Thread
                    audioBuf.position(0)
                    val read = record.read(audioBuf, 0, audioBuf.capacity())
                    if (read > 0) {
                        audioBufferInfo.offset = 0
                        audioBufferInfo.size = read
                        audioEnc.queueInputBuffer(audioInIndex, 0, read, audioTimestamp, 0)
                        audioTimestamp += read.toLong()
                    }
                }

                val audioOutIndex = audioEnc.dequeueOutputBuffer(audioBufferInfo, 10_000)
                if (audioOutIndex >= 0) {
                    val audioEncoded = audioEnc.getOutputBuffer(audioOutIndex)
                    if (audioEncoded != null) {
                        audioEnc.releaseOutputBuffer(audioOutIndex, true)
                        mediaMuxer?.writeSampleData(audioTrackIndex, audioEncoded, audioBufferInfo)
                    }
                }
            }

            c.stop()
            c.release()
            mediaMuxer?.stop()
            mediaMuxer?.release()
            this.mediaMuxer = null
            captureSurface?.release()
            this.captureSurface = null
            this.mediaCodec = null

            audioEnc.stop()
            audioEnc.release()
            record.stop()
            record.release()
            this.audioCodec = null
            this.audioRecord = null

            virtualDisplay?.release()
            virtualDisplay = null

            projection.stop()
            mediaProjection = null

            captureFile?.let { file ->
                uploadCaptureFile(file)
            }
            this.captureFile = null
        }.start()
    }

    private fun uploadCaptureFile(file: File) {
        fun setStatus(message: String) {
            runOnUiThread { statusText.text = message }
        }
        try {
            val url = "http://${LINUX_SERVER_HOST}:${LINUX_SERVER_PORT}/upload?name=${Uri.encode(file.name)}"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.outputStream.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            val responseCode = connection.responseCode
            connection.disconnect()
            if (responseCode !in 200..299) {
                setStatus("Upload failed (HTTP $responseCode)")
            } else {
                setStatus("Capture sent to Linux")
            }
        } catch (t: Throwable) {
            setStatus("Upload failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        stopRequested.set(true)

        mediaCodec?.signalEndOfInputStream()
        audioCodec?.signalEndOfInputStream()
        audioRecord?.stop()
        audioRecord?.release()
        audioCodec?.stop()
        audioCodec?.release()
        mediaCodec = null
        audioCodec = null
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    protected override fun onDestroy() {
        super.onDestroy()
        stopCapture()
    }
}
