package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.model.VideoMetadata
import java.io.File
import java.io.FileOutputStream

object VideoUtils {
    private const val TAG = "VideoUtils"

    fun extractMetadata(context: Context, uri: Uri): VideoMetadata {
        var durationMs = 0L
        var width = 0
        var height = 0
        var rotation = 0
        var bitrate = 0L
        var mimeType = "video/mp4"
        var thumbnail: Bitmap? = null
        var fps = 0.0

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durationStr?.toLongOrNull() ?: 0L

            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)

            val rawWidth = widthStr?.toIntOrNull() ?: 0
            val rawHeight = heightStr?.toIntOrNull() ?: 0
            rotation = rotationStr?.toIntOrNull() ?: 0

            // Adjust width & height for 90/270 degree rotation
            if (rotation == 90 || rotation == 270) {
                width = rawHeight
                height = rawWidth
            } else {
                width = rawWidth
                height = rawHeight
            }

            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            bitrate = bitrateStr?.toLongOrNull() ?: 0L

            val mimeStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            if (!mimeStr.isNullOrEmpty()) {
                mimeType = mimeStr
            }

            val captureFpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            if (!captureFpsStr.isNullOrEmpty()) {
                fps = captureFpsStr.toDoubleOrNull() ?: 0.0
            }

            // Extract thumbnail frame at 0.5s or 0s
            val frameTimeMicros = if (durationMs > 1000) 500_000L else 0L
            thumbnail = try {
                retriever.getFrameAtTime(frameTimeMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting metadata via MediaMetadataRetriever: ${e.message}", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore
            }
        }

        // If FPS was not found via retriever, probe via MediaExtractor
        if (fps <= 0.0) {
            fps = extractFpsViaMediaExtractor(context, uri)
        }

        // Fallback default if still not determined
        if (fps <= 0.0) {
            fps = 30.0
        }

        // Extract filename and size
        val (fileName, fileSize) = queryFileInfo(context, uri)

        return VideoMetadata(
            uri = uri,
            fileName = fileName,
            durationMs = durationMs,
            width = width,
            height = height,
            fps = fps,
            fileSizeBytes = fileSize,
            bitrateBps = bitrate,
            mimeType = mimeType,
            thumbnailBitmap = thumbnail
        )
    }

    private fun extractFpsViaMediaExtractor(context: Context, uri: Uri): Double {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val numTracks = extractor.trackCount
            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        return try {
                            format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                        } catch (e: Exception) {
                            try {
                                format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
                            } catch (e2: Exception) {
                                0.0
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor failed to read FPS: ${e.message}")
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
        return 0.0
    }

    private fun queryFileInfo(context: Context, uri: Uri): Pair<String, Long> {
        var name = "video.mp4"
        var size = 0L

        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1 && !cursor.isNull(nameIndex)) {
                            name = cursor.getString(nameIndex) ?: name
                        }
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query content resolver: ${e.message}")
            }
        } else if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            if (file.exists()) {
                name = file.name
                size = file.length()
            }
        }

        return Pair(name, size)
    }

    fun copyUriToTempFile(context: Context, uri: Uri, targetFileName: String): File {
        val cacheDir = File(context.cacheDir, "input_videos").apply { mkdirs() }
        val tempFile = File(cacheDir, targetFileName)

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: throw IllegalStateException("Could not open input stream from URI: $uri")

        return tempFile
    }

    fun cleanCache(context: Context) {
        try {
            File(context.cacheDir, "input_videos").deleteRecursively()
        } catch (e: Exception) {
            Log.w(TAG, "Cache clean error: ${e.message}")
        }
    }
}
