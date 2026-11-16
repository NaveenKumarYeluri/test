package com.naveen.videos

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optional recorder that muxes the same encoded frames which are being sent
 * over RTP into an MP4 in the phone's Movies collection.
 */
class LiveRecording(private val context: Context) {
    private val lock = Any()
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var descriptor: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var file: File? = null
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var requested = false
    private var started = false

    @Volatile
    var isRecording = false
        private set

    fun setVideoFormat(format: MediaFormat) {
        synchronized(lock) {
            videoFormat = format
            maybeStart()
        }
    }

    fun setAudioFormat(format: MediaFormat) {
        synchronized(lock) {
            audioFormat = format
            maybeStart()
        }
    }

    fun start() {
        synchronized(lock) {
            requested = true
            maybeStart()
        }
    }

    fun writeVideo(data: ByteArray, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val mux = muxer ?: return
            if (!started || videoTrack < 0) return
            val sample = MediaCodec.BufferInfo().apply {
                set(0, data.size, info.presentationTimeUs, info.flags)
            }
            try {
                mux.writeSampleData(videoTrack, ByteBuffer.wrap(data), sample)
            } catch (_: Throwable) {
            }
        }
    }

    fun writeAudio(data: ByteArray, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val mux = muxer ?: return
            if (!started || audioTrack < 0) return
            val sample = MediaCodec.BufferInfo().apply {
                set(0, data.size, info.presentationTimeUs, info.flags)
            }
            try {
                mux.writeSampleData(audioTrack, ByteBuffer.wrap(data), sample)
            } catch (_: Throwable) {
            }
        }
    }

    /** Returns true when a playable file was finished. */
    fun stop(): Boolean {
        synchronized(lock) {
            requested = false
            val mux = muxer ?: return false
            muxer = null
            var saved = false
            try {
                mux.stop()
                saved = true
            } catch (_: Throwable) {
                saved = false
            }
            try {
                mux.release()
            } catch (_: Throwable) {
            }
            try {
                descriptor?.close()
            } catch (_: Throwable) {
            }
            descriptor = null
            started = false
            isRecording = false
            val targetUri = uri
            val targetFile = file
            uri = null
            file = null
            if (saved) {
                if (targetUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        context.contentResolver.update(
                            targetUri,
                            ContentValues().apply {
                                put(MediaStore.Video.Media.IS_PENDING, 0)
                            },
                            null,
                            null
                        )
                    } catch (_: Throwable) {
                    }
                }
                if (targetFile != null) {
                    try {
                        MediaScannerConnection.scanFile(
                            context, arrayOf(targetFile.absolutePath), arrayOf("video/mp4"), null
                        )
                    } catch (_: Throwable) {
                    }
                }
            } else {
                if (targetUri != null) {
                    try {
                        context.contentResolver.delete(targetUri, null, null)
                    } catch (_: Throwable) {
                    }
                }
                targetFile?.delete()
            }
            return saved
        }
    }

    private fun maybeStart() {
        if (!requested || muxer != null) return
        val video = videoFormat ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val name = "videos-live-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/Videos"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val created = context.contentResolver.insert(collection, values) ?: return
                val pfd = context.contentResolver.openFileDescriptor(created, "w") ?: return
                val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                uri = created
                descriptor = pfd
                muxer = mux
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "Videos"
                )
                if (!directory.exists() && !directory.mkdirs()) return
                val target = File(directory, name)
                val mux = MediaMuxer(
                    target.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                file = target
                muxer = mux
            }
            val mux = muxer ?: return
            videoTrack = mux.addTrack(video)
            val audio = audioFormat
            if (audio != null) audioTrack = mux.addTrack(audio)
            mux.start()
            started = true
            isRecording = true
            LiveState.recording = true
        } catch (_: Throwable) {
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            muxer = null
            started = false
            isRecording = false
            LiveState.recording = false
        }
    }
}
