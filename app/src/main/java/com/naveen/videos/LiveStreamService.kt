package com.naveen.videos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder

/**
 * Foreground service which owns the MediaProjection capture session. Running
 * as a foreground service guarantees the persistent "screen is being shared"
 * notification and keeps capture alive while other apps are on screen.
 */
class LiveStreamService : Service() {

    companion object {
        const val ACTION_START = "com.naveen.videos.action.LIVE_START"
        const val ACTION_STOP = "com.naveen.videos.action.LIVE_STOP"
        const val ACTION_START_RECORDING = "com.naveen.videos.action.LIVE_RECORD_START"
        const val ACTION_STOP_RECORDING = "com.naveen.videos.action.LIVE_RECORD_STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CLIENT_IP = "client_ip"
        const val EXTRA_VIDEO_PORT = "video_port"
        const val EXTRA_AUDIO_PORT = "audio_port"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_TRANSPORT = "transport"

        private const val CHANNEL_ID = "videos_live"
        private const val NOTIFICATION_ID = 42

        fun stop(context: Context) {
            val intent = Intent(context, LiveStreamService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Throwable) {
            }
        }
    }

    private var projection: MediaProjection? = null
    private var pipeline: LiveCapturePipeline? = null

    /**
     * Incremented for every capture request and every stop. Callbacks from a
     * replaced pipeline carry the generation they were created with, so they
     * cannot stop a newer session.
     */
    private var generation = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LiveState.trace("svc:${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START_RECORDING -> {
                pipeline?.startRecording()
                return START_NOT_STICKY
            }
            ACTION_STOP_RECORDING -> {
                pipeline?.stopRecording()
                return START_NOT_STICKY
            }
        }

        startForegroundCompat("Preparing screen mirror")
        // This request supersedes any capture which is still winding down.  A
        // fresh generation makes the old pipeline's finish callback a no-op.
        val captureGeneration = ++generation
        val previous = pipeline
        if (previous != null) {
            pipeline = null
            LiveState.trace("svc.replaceCapture")
            try {
                previous.stop()
            } catch (_: Throwable) {
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        val clientIp = intent?.getStringExtra(EXTRA_CLIENT_IP)
        val videoPort = intent?.getIntExtra(EXTRA_VIDEO_PORT, 5004) ?: 5004
        val audioPort = intent?.getIntExtra(EXTRA_AUDIO_PORT, 5006) ?: 5006
        val bitrate = intent?.getIntExtra(EXTRA_BITRATE, 8_000_000) ?: 8_000_000
        val transport = intent?.getStringExtra(EXTRA_TRANSPORT)
            ?: LiveState.TRANSPORT_WIFI
        val usb = transport == LiveState.TRANSPORT_USB
        LiveState.transport = transport

        if (resultData == null || (!usb && clientIp.isNullOrBlank())) {
            fail("Missing screen-capture permission")
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (t: Throwable) {
            null
        }
        if (mediaProjection == null) {
            fail("Could not start screen capture")
            return START_NOT_STICKY
        }
        projection = mediaProjection

        val capture = LiveCapturePipeline(
            this,
            mediaProjection,
            clientIp,
            videoPort,
            audioPort,
            bitrate,
            transport
        ) {
            handlePipelineFinished(captureGeneration)
        }
        pipeline = capture
        updateNotification(
            if (usb) "USB mirror ready" else "Mirroring to $clientIp"
        )
        capture.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopCapture() {
        LiveState.trace("svc.stopCapture")
        // Invalidate this capture's finish callback before stopping it.
        generation++
        val wasError = LiveState.state == LiveState.ERROR
        val previousMessage = LiveState.message
        val capture = pipeline
        pipeline = null
        try {
            capture?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        // Only clear the shared state if no newer capture has claimed it.
        if (LiveState.session == null || LiveState.session === capture) {
            LiveState.session = null
            LiveState.reset()
            if (wasError) {
                LiveState.state = LiveState.ERROR
                LiveState.message = previousMessage
            }
        }
        try {
            stopForeground(true)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    private fun handlePipelineFinished(finishedGeneration: Int) {
        if (finishedGeneration != generation) {
            LiveState.trace("svc.staleFinish:$finishedGeneration")
            return
        }
        stopSelf()
    }

    private fun fail(message: String) {
        LiveState.trace("svc.fail:$message")
        LiveState.state = LiveState.ERROR
        LiveState.message = message
        stopCapture()
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Videos is mirroring your screen")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stop
            )
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live screen mirror",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while the phone screen is being streamed to Linux"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
