package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object MediaStoreUtils {
    private const val TAG = "MediaStoreUtils"

    fun saveVideoToGallery(
        context: Context,
        sourceFile: File,
        baseName: String,
        targetFps: Int
    ): Uri? {
        val resolver = context.contentResolver
        val cleanBaseName = baseName.substringBeforeLast(".")
        val displayName = "FPS_${targetFps}fps_${cleanBaseName}_${System.currentTimeMillis()}.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FPSReducer")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        var itemUri: Uri? = null
        try {
            itemUri = resolver.insert(collectionUri, values)
            if (itemUri != null) {
                resolver.openOutputStream(itemUri)?.use { outStream ->
                    FileInputStream(sourceFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                }
                Log.d(TAG, "Successfully exported video to MediaStore: $itemUri")
                return itemUri
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save video to MediaStore: ${e.message}", e)
            if (itemUri != null) {
                try {
                    resolver.delete(itemUri, null, null)
                } catch (delEx: Exception) {
                    // Ignore
                }
            }
        }

        // Fallback for older devices if MediaStore insert failed
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                @Suppress("DEPRECATION")
                val moviesDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "FPSReducer"
                ).apply { mkdirs() }
                val destFile = File(moviesDir, displayName)
                FileInputStream(sourceFile).use { inStream ->
                    FileOutputStream(destFile).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
                return Uri.fromFile(destFile)
            } catch (e: Exception) {
                Log.e(TAG, "Fallback save failed: ${e.message}", e)
            }
        }

        return null
    }

    fun createShareIntent(context: Context, videoFile: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            videoFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
