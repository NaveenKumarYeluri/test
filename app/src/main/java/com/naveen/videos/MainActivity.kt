package com.naveen.videos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val ACTION_REQUEST_LIVE = "com.naveen.videos.action.REQUEST_LIVE"
        const val EXTRA_CLIENT_IP = "client_ip"
        const val EXTRA_VIDEO_PORT = "video_port"
        const val EXTRA_AUDIO_PORT = "audio_port"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_TRANSPORT = "transport"

        private const val REQUEST_PROJECTION = 1001
        private const val REQUEST_PERMISSIONS = 1002
    }

    private lateinit var serverStatus: TextView
    private lateinit var serverUrl: TextView
    private lateinit var serverButton: Button
    private lateinit var copyUrlButton: Button
    private lateinit var liveButton: Button
    private lateinit var liveStatus: TextView
    private lateinit var permissionsButton: Button
    private lateinit var deviceName: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pendingBitrate = 8_000_000
    /**
     * The request which owns the currently displayed screen-capture consent
     * dialog.  Global LiveState can be reset by a pipeline winding down, so the
     * callback must not read the transport from it.
     */
    private var pendingLive: LiveRequestParams? = null
    private val preferences by lazy { getSharedPreferences("videos", MODE_PRIVATE) }

    private data class LiveRequestParams(
        val ip: String,
        val videoPort: Int,
        val audioPort: Int,
        val bitrate: Int,
        val transport: String
    )

    private val poller = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        LiveState.trace("onCreate")

        serverStatus = findViewById(R.id.server_status)
        serverUrl = findViewById(R.id.server_url)
        serverButton = findViewById(R.id.server_button)
        copyUrlButton = findViewById(R.id.copy_url_button)
        liveButton = findViewById(R.id.live_button)
        liveStatus = findViewById(R.id.live_status)
        permissionsButton = findViewById(R.id.permissions_button)
        deviceName = findViewById(R.id.device_name)

        deviceName.text = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

        serverButton.setOnClickListener { toggleServer() }
        copyUrlButton.setOnClickListener { copyUrl() }
        liveButton.setOnClickListener { toggleLive() }
        permissionsButton.setOnClickListener { requestAppPermissions() }

        PhoneServerService.start(this)
        requestAppPermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        LiveState.trace("onNewIntent:${intent?.action}")
        if (intent != null) {
            setIntent(intent)
            handleIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(poller)
    }

    private fun toggleServer() {
        if (PhoneServerService.isRunning) {
            PhoneServerService.stop(this)
        } else {
            PhoneServerService.start(this)
        }
        handler.postDelayed({ updateUi() }, 500)
    }

    private fun copyUrl() {
        val ip = NetworkUtils.localIpv4() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Videos URL", "http://$ip:${PhoneServerService.PORT}")
        )
        Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show()
    }

    private fun toggleLive() {
        if (LiveState.isActive) {
            LiveState.state = LiveState.STOPPING
            LiveState.message = "Stopping live mirror…"
            LiveStreamService.stop(this)
            updateUi()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Start live mirror")
            .setItems(
                arrayOf("USB cable (recommended)", "Wi-Fi (same network)")
            ) { _, which ->
                if (which == 0) {
                    beginLive("", 5004, 5006, 8_000_000, LiveState.TRANSPORT_USB)
                } else {
                    promptWifi()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptWifi() {
        val input = EditText(this).apply {
            hint = "Linux IP address"
            setText(preferences.getString("client_ip", LiveState.clientIp ?: ""))
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Wi-Fi live mirror")
            .setMessage("Enter the Linux machine's IP address. Its Videos app should be open.")
            .setView(input)
            .setPositiveButton("Start") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, "Enter the Linux IP", Toast.LENGTH_SHORT).show()
                } else {
                    preferences.edit().putString("client_ip", ip).apply()
                    beginLive(ip, 5004, 5006, 8_000_000, LiveState.TRANSPORT_WIFI)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleIntent(intent: Intent?) {
        LiveState.trace("handle:${intent?.action}/${intent?.getStringExtra(EXTRA_CLIENT_IP)}")
        if (intent?.action != ACTION_REQUEST_LIVE) return
        val transport = intent.getStringExtra(EXTRA_TRANSPORT)
            ?: LiveState.TRANSPORT_WIFI
        val ip = intent.getStringExtra(EXTRA_CLIENT_IP).orEmpty()
        if (transport != LiveState.TRANSPORT_USB && ip.isBlank()) return
        val videoPort = intent.getIntExtra(EXTRA_VIDEO_PORT, 5004)
        val audioPort = intent.getIntExtra(EXTRA_AUDIO_PORT, 5006)
        pendingBitrate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000)
        if (ip.isNotBlank()) preferences.edit().putString("client_ip", ip).apply()
        // A request arriving from the phone server is already in REQUESTING
        // state; only skip intents once a capture session is really running.
        if (LiveState.state == LiveState.STREAMING || LiveState.state == LiveState.STOPPING) {
            return
        }
        beginLive(ip, videoPort, audioPort, pendingBitrate, transport)
    }

    private fun beginLive(
        ip: String,
        videoPort: Int,
        audioPort: Int,
        bitrate: Int,
        transport: String
    ) {
        val usb = transport == LiveState.TRANSPORT_USB
        LiveState.trace("beginLive:$transport:$ip:$videoPort/$audioPort")
        PhoneServerService.dismissApproval(this)
        LiveState.clientIp = ip.ifBlank { null }
        LiveState.transport = transport
        LiveState.videoPort = videoPort
        LiveState.audioPort = audioPort
        LiveState.state = LiveState.AWAITING_PERMISSION
        LiveState.message = if (usb) {
            "Approve the screen-capture prompt to mirror over USB"
        } else {
            "Approve the screen-capture prompt to mirror to $ip"
        }
        LiveState.sdp = null
        pendingBitrate = bitrate
        pendingLive = LiveRequestParams(ip, videoPort, audioPort, bitrate, transport)
        if (!PhoneServerService.isRunning) PhoneServerService.start(this)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
        } catch (t: Throwable) {
            LiveState.state = LiveState.ERROR
            LiveState.message = "Could not request screen capture: ${t.message}"
            LiveState.trace("startCaptureIntentFailed:${t.javaClass.simpleName}")
        }
        updateUi()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        LiveState.trace("result:$requestCode/$resultCode")
        if (requestCode != REQUEST_PROJECTION) return
        val request = pendingLive
        pendingLive = null
        if (resultCode != RESULT_OK || data == null) {
            LiveState.state = LiveState.ERROR
            LiveState.message = "Screen-capture permission was denied"
            updateUi()
            return
        }
        if (request == null) {
            LiveState.trace("result:stale")
            return
        }
        val ip = request.ip
        if (request.transport != LiveState.TRANSPORT_USB && ip.isBlank()) {
            LiveState.state = LiveState.ERROR
            LiveState.message = "No Linux client address was provided"
            updateUi()
            return
        }
        val serviceIntent = Intent(this, LiveStreamService::class.java).apply {
            action = LiveStreamService.ACTION_START
            putExtra(LiveStreamService.EXTRA_RESULT_CODE, resultCode)
            putExtra(LiveStreamService.EXTRA_RESULT_DATA, data)
            putExtra(EXTRA_CLIENT_IP, ip)
            putExtra(EXTRA_VIDEO_PORT, request.videoPort)
            putExtra(EXTRA_AUDIO_PORT, request.audioPort)
            putExtra(EXTRA_BITRATE, request.bitrate)
            putExtra(EXTRA_TRANSPORT, request.transport)
        }
        LiveState.state = LiveState.REQUESTING
        LiveState.message = "Starting capture…"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (t: Throwable) {
            LiveState.state = LiveState.ERROR
            LiveState.message = "Could not start capture: ${t.message}"
        }
        updateUi()
    }

    private fun updateUi() {
        val running = PhoneServerService.isRunning
        val ip = NetworkUtils.localIpv4()
        serverStatus.text = if (running) "Running" else "Stopped"
        serverStatus.setTextColor(getColor(if (running) R.color.success else R.color.text_secondary))
        serverUrl.text = if (running && ip != null) {
            "http://$ip:${PhoneServerService.PORT}"
        } else {
            "—"
        }
        serverButton.text = if (running) "Stop server" else "Start server"
        copyUrlButton.isEnabled = running && ip != null

        liveStatus.text = if (LiveState.message.isNotBlank()) {
            LiveState.message
        } else {
            "Idle"
        }
        liveStatus.setTextColor(
            getColor(
                when (LiveState.state) {
                    LiveState.STREAMING -> R.color.success
                    LiveState.ERROR -> R.color.danger
                    LiveState.IDLE -> R.color.text_secondary
                    else -> R.color.warning
                }
            )
        )
        liveButton.text = when {
            LiveState.isActive && LiveState.isUsb -> "Stop USB mirror"
            LiveState.isActive -> "Stop Wi-Fi mirror"
            else -> "Start live mirror"
        }
        liveButton.setBackgroundColor(
            getColor(if (LiveState.isActive) R.color.danger else R.color.primary)
        )
    }

    private fun requestAppPermissions() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                wanted.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                wanted.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(Manifest.permission.RECORD_AUDIO)
        }
        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }
}
