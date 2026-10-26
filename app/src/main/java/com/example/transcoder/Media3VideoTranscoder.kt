package com.example.transcoder

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

@OptIn(UnstableApi::class)
class Media3VideoTranscoder(private val context: Context) {
    companion object {
        private const val TAG = "Media3VideoTranscoder"
    }

    private var currentTransformer: Transformer? = null

    fun cancel() {
        try {
            currentTransformer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling transformer: ${e.message}")
        }
        currentTransformer = null
    }

    fun transcodeVideo(
        inputFile: File,
        targetFps: Int,
        totalDurationMs: Long,
        keepAudio: Boolean = true
    ): Flow<TranscodeProgress> = callbackFlow {
        val outputDir = File(context.cacheDir, "output").apply { mkdirs() }
        val outputFile = File(outputDir, "fps_${targetFps}_${System.currentTimeMillis()}.mp4")
        val totalDurationSec = if (totalDurationMs > 0) totalDurationMs / 1000f else 1f

        val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))

        // Create the Google Media3 FrameDropEffect for exact frame rate lowering
        val frameDropEffect = FrameDropEffect.createDefaultFrameDropEffect(targetFps.toFloat())

        val effects = Effects(
            /* audioProcessors = */ emptyList(),
            /* videoEffects = */ listOf(frameDropEffect)
        )

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(effects)
            .setRemoveAudio(!keepAudio)
            .build()

        val mainHandler = Handler(Looper.getMainLooper())
        var pollJob: Job? = null

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                pollJob?.cancel()
                Log.d(TAG, "Media3 export completed successfully: ${outputFile.length()} bytes")
                trySend(TranscodeProgress.Success(outputFile))
                close()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                pollJob?.cancel()
                Log.e(TAG, "Media3 export error: ${exportException.message}", exportException)
                if (outputFile.exists()) outputFile.delete()
                trySend(TranscodeProgress.Error("Conversion error: ${exportException.localizedMessage ?: "Encoding failure"}"))
                close()
            }
        }

        val transformer = Transformer.Builder(context)
            .addListener(listener)
            .build()

        currentTransformer = transformer

        try {
            // Start transcoding on main thread as required by Media3 Transformer
            mainHandler.post {
                try {
                    transformer.start(editedMediaItem, outputFile.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start transformer: ${e.message}", e)
                    trySend(TranscodeProgress.Error("Failed to start encoder: ${e.localizedMessage}"))
                    close()
                }
            }

            // Progress tracking loop
            val progressHolder = ProgressHolder()
            pollJob = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    delay(200)
                    var state = Transformer.PROGRESS_STATE_NOT_STARTED
                    mainHandler.post {
                        try {
                            state = transformer.getProgress(progressHolder)
                        } catch (ignored: Exception) {}
                    }
                    delay(50)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val progressRatio = (progressHolder.progress / 100f).coerceIn(0f, 0.99f)
                        val currentSec = progressRatio * totalDurationSec
                        trySend(
                            TranscodeProgress.Progress(
                                progressPercent = progressRatio,
                                currentSeconds = currentSec,
                                totalSeconds = totalDurationSec,
                                speed = 1.0,
                                encodingFps = targetFps.toDouble(),
                                outputSizeBytes = outputFile.length()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            pollJob?.cancel()
            Log.e(TAG, "Transcode launch error: ${e.message}", e)
            trySend(TranscodeProgress.Error("Could not launch conversion: ${e.localizedMessage}"))
            close()
        }

        awaitClose {
            pollJob?.cancel()
            try {
                mainHandler.post {
                    transformer.cancel()
                }
            } catch (ignored: Exception) {}
            currentTransformer = null
        }
    }
}
