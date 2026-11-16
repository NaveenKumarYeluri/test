package com.naveen.videos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the phone's HTTP server and discovery beacon alive in the background
 * and shows a persistent notification while the phone is sharing media.
 */
class PhoneServerService : Service() {

    companion object {
        const val PORT = 8080
        private const val CHANNEL_ID = "videos_server"
        private const val NOTIFICATION_ID = 41
        const val APPROVAL_CHANNEL_ID = "videos_live_request"
        const val APPROVAL_NOTIFICATION_ID = 43

        @Volatile
        var isRunning: Boolean = false
            private set

        fun dismissApproval(context: Context) {
            try {
                val manager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(APPROVAL_NOTIFICATION_ID)
            } catch (_: Throwable) {
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, PhoneServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, PhoneServerService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    private var server: PhoneServer? = null
    private var beacon: BeaconBroadcaster? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        createApprovalChannel()
        startForegroundCompat()
        isRunning = true

        val phoneServer = PhoneServer(
            this,
            PORT,
            onLiveStart = { request -> requestLive(request) },
            onLiveStop = { stopLive() },
            onLiveRecord = { on -> setRecording(on) }
        )
        try {
            phoneServer.start()
            server = phoneServer
        } catch (t: Throwable) {
            LiveState.state = LiveState.ERROR
            LiveState.message = "Could not start HTTP server: ${t.message}"
        }
        beacon = BeaconBroadcaster(PORT).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.naveen.videos.action.SERVER_STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        beacon?.stop()
        beacon = null
        isRunning = false
        try {
            stopForeground(true)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestLive(request: PhoneServer.LiveRequest) {
        LiveState.trace("svc.requestLive:${request.transport}:${request.clientIp}")
        LiveState.transport = request.transport
        LiveState.clientIp = request.clientIp
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_REQUEST_LIVE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_CLIENT_IP, request.clientIp)
            putExtra(MainActivity.EXTRA_VIDEO_PORT, request.videoPort)
            putExtra(MainActivity.EXTRA_AUDIO_PORT, request.audioPort)
            putExtra(MainActivity.EXTRA_BITRATE, request.videoBitrate)
            putExtra(MainActivity.EXTRA_TRANSPORT, request.transport)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            LiveState.trace("svc.requestLiveStartFailed:${t.javaClass.simpleName}")
        }
        // ColorOS frequently blocks background activity launches. The
        // notification gives the user a reliable way to open the consent
        // prompt from the Linux app.
        val target = if (request.transport == LiveState.TRANSPORT_USB) {
            "the Linux machine over USB"
        } else {
            request.clientIp
        }
        postApprovalNotification(intent, target)
    }

    private fun postApprovalNotification(intent: Intent, target: String) {
        try {
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val pending = PendingIntent.getActivity(
                this,
                20,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, APPROVAL_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notification = builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Approve screen mirroring")
                .setContentText("Tap to allow Videos to mirror this phone to $target")
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setOngoing(false)
                .build()
            manager.notify(APPROVAL_NOTIFICATION_ID, notification)
        } catch (_: Throwable) {
        }
    }

    private fun stopLive() {
        LiveStreamService.stop(this)
    }

    private fun setRecording(on: Boolean) {
        try {
            val intent = Intent(this, LiveStreamService::class.java).setAction(
                if (on) LiveStreamService.ACTION_START_RECORDING
                else LiveStreamService.ACTION_STOP_RECORDING
            )
            startService(intent)
        } catch (_: Throwable) {
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PhoneServerService::class.java)
                .setAction("com.naveen.videos.action.SERVER_STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ip = NetworkUtils.localIpv4() ?: "your phone"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Videos server running")
            .setContentText("Open the Linux app to connect · http://$ip:$PORT")
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
            "Phone streaming server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while the Linux app can stream from this phone"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun createApprovalChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            APPROVAL_CHANNEL_ID,
            "Live mirror requests",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Asks you to approve screen mirroring requested by the Linux app"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }
}
