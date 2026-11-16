package com.naveen.videos

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

data class VideoItem(
    val id: Long,
    val title: String,
    val durationMs: Long,
    val size: Long,
    val mimeType: String,
    val dateModified: Long,
    val relativePath: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("durationMs", durationMs)
        put("size", size)
        put("mime", mimeType)
        put("dateModified", dateModified)
        put("path", relativePath ?: JSONObject.NULL)
    }
}

object MediaLibrary {
    fun contentUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

    fun query(context: Context, limit: Int = 3000): List<VideoItem> {
        val items = ArrayList<VideoItem>()
        val projection = arrayListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Video.Media.RELATIVE_PATH)
        }
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                "${MediaStore.Video.Media.SIZE} > 0",
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val titleIndex = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
                val durationIndex = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeIndex = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val mimeIndex = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                while (cursor.moveToNext() && items.size < limit) {
                    val id = cursor.getLong(idIndex)
                    val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val title = (if (titleIndex >= 0) cursor.getString(titleIndex) else null)
                        ?: displayName ?: "Video $id"
                    items.add(
                        VideoItem(
                            id = id,
                            title = title,
                            durationMs = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L,
                            size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L,
                            mimeType = (if (mimeIndex >= 0) cursor.getString(mimeIndex) else null)
                                ?: "video/mp4",
                            dateModified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else 0L,
                            relativePath = if (pathIndex >= 0) cursor.getString(pathIndex) else null
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            // Missing permission or an unavailable MediaStore: return what we have.
        }
        return items
    }

    fun thumbnail(context: Context, item: VideoItem): ByteArray? {
        val cacheDir = File(context.cacheDir, "thumbs")
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return null
        val cacheFile = File(cacheDir, "${item.id}-${item.dateModified}.jpg")
        if (cacheFile.isFile && cacheFile.length() > 0L) {
            return try {
                cacheFile.readBytes()
            } catch (_: Throwable) {
                null
            }
        }
        val retriever = MediaMetadataRetriever()
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            retriever.setDataSource(context, contentUri(item.id))
            bitmap = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            scaled = if (bitmap.width > 320) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    320,
                    (bitmap.height * 320f / bitmap.width).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, output)
            val bytes = output.toByteArray()
            try {
                cacheFile.writeBytes(bytes)
            } catch (_: Throwable) {
            }
            bytes
        } catch (_: Throwable) {
            null
        } finally {
            if (scaled != null && scaled !== bitmap) bitmap?.recycle()
            scaled?.recycle()
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }
}
