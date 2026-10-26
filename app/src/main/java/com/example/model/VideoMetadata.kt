package com.example.model

import android.graphics.Bitmap
import android.net.Uri
import java.util.Locale

data class VideoMetadata(
    val uri: Uri,
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
    val fileSizeBytes: Long,
    val bitrateBps: Long = 0L,
    val mimeType: String = "video/mp4",
    val thumbnailBitmap: Bitmap? = null
) {
    val formattedDuration: String
        get() {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }

    val formattedDurationWithMillis: String
        get() {
            val totalSeconds = durationMs.toDouble() / 1000.0
            return String.format(Locale.US, "%.1fs", totalSeconds)
        }

    val formattedResolution: String
        get() {
            if (width <= 0 || height <= 0) return "Unknown"
            val minDim = minOf(width, height)
            val tag = when {
                minDim >= 2160 -> "4K UHD"
                minDim >= 1440 -> "1440p QHD"
                minDim >= 1080 -> "1080p FHD"
                minDim >= 720 -> "720p HD"
                minDim >= 480 -> "480p SD"
                else -> "${minDim}p"
            }
            return "$width × $height ($tag)"
        }

    val formattedFps: String
        get() = if (fps > 0) String.format(Locale.US, "%.1f FPS", fps) else "30.0 FPS"

    val fpsRounded: Int
        get() = if (fps > 0) fps.toInt() else 30

    val formattedSize: String
        get() {
            if (fileSizeBytes <= 0) return "Unknown size"
            val kb = fileSizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                else -> String.format(Locale.US, "%.0f KB", kb)
            }
        }
}
