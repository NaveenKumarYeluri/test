package com.naveen.videos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private val captureRequestCode = 1001

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: Display? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var outputSurface: Surface? = null
    private var isCapturing = false

    private val stopRequested = AtomicBoolean(false)

    private lateinit var captureButton: Button
    private lateinit var statusText: TextView

    private val handler = Handler()

    private val captureCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
        }

        override fun onStop(): Unit {
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
            statusText.text = "Preparing capture…"
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
        val height = displayMetrics.heightPixels.coerceAtLeast(240))

        val videoFormat = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        }

        val codec = MediaCodec.createEncoderByType("video/avc")
        val surface = codec.createSurface()
        codec.configure(videoFormat, surface, null, 0)
        codec.start()

        val muxer = MediaMuxer(
            File(getExternalFilesDir(null), "capture_${System.currentTimeMillis()}.mp4").absolutePath,
            MediaMuxer.OutputFormat.MUX_PER_FILE
        )
        muxer.addTrack(videoFormat)
        muxer.start()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        virtualDisplay = windowManager.createVirtualDisplay(
            "VideosCapture",
            width,
            height,
            displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            handler,
            null
        )

        this.codec = codec
        this.muxer = muxer
        this.outputSurface = surface
        statusText.text = "Capturing…"

        Thread {
            val c = codec ?: return@Thread
            val bufferInfo = MediaCodec.BufferInfo()
            var endOfStream = false
            while (!endOfStream && !stopRequested.get()) {
                val outIndex = c.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    c.releaseOutputBuffer(outIndex, true)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        endOfStream = true
                    }
                }
            }

            c.stop()
            c.release()
            muxer?.stop()
            muxer?.release()
            muxer = null
            outputSurface?.release()
            outputSurface = null
            codec = null

            virtualDisplay?.release()
            virtualDisplay = null

            mediaProjection?.stop()
            mediaProjection = null
        }.start()
    }

    private fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        stopRequested.set(true)

        codec?.signalEndOfInputStream()
        mediaProjection?.stop()
        mediaProjection = null
    }

    protected override fun onDestroy() {
        super.onDestroy()
        stopCapture()
    }
}
