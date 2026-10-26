package com.example.transcoder

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import com.arthenica.ffmpegkit.Signal
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface TranscodeProgress {
    data class Progress(
        val progressPercent: Float, // 0.0 to 1.0
        val currentSeconds: Float,
        val totalSeconds: Float,
        val speed: Double,
        val encodingFps: Double,
        val outputSizeBytes: Long
    ) : TranscodeProgress

    data class Success(val outputFile: File) : TranscodeProgress
    data class Cancelled(val reason: String) : TranscodeProgress
    data class Error(val message: String, val logs: String? = null) : TranscodeProgress
}

class VideoTranscoder(private val context: Context) {
    companion object {
        private const val TAG = "VideoTranscoder"
    }

    private var currentSession: FFmpegSession? = null
    private val media3Transcoder = Media3VideoTranscoder(context)

    fun cancel() {
        try {
            media3Transcoder.cancel()
            currentSession?.let { session ->
                Log.d(TAG, "Cancelling FFmpeg session ${session.sessionId}")
                session.cancel()
                FFmpegKit.cancel(session.sessionId)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error cancelling session: ${e.message}")
        }
    }

    private fun isFFmpegUsable(): Boolean {
        return try {
            FFmpegKitConfig.ignoreSignal(Signal.SIGXCPU)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "FFmpeg native libraries unavailable (${t.message}), defaulting to Google Media3 engine.")
            false
        }
    }

    fun transcodeVideo(
        inputFile: File,
        targetFps: Int,
        totalDurationMs: Long,
        keepAudio: Boolean = true,
        crfQuality: Int = 22
    ): Flow<TranscodeProgress> = callbackFlow {
        if (!isFFmpegUsable()) {
            // Use Media3 FrameDropEffect directly when native binaries are unavailable
            val job = launch {
                media3Transcoder.transcodeVideo(inputFile, targetFps, totalDurationMs, keepAudio).collect {
                    trySend(it)
                    if (it is TranscodeProgress.Success || it is TranscodeProgress.Error || it is TranscodeProgress.Cancelled) {
                        close()
                    }
                }
            }
            awaitClose {
                job.cancel()
                media3Transcoder.cancel()
            }
            return@callbackFlow
        }

        val outputDir = File(context.cacheDir, "output").apply { mkdirs() }
        val outputFile = File(outputDir, "fps_${targetFps}_${System.currentTimeMillis()}.mp4")
        val totalDurationSec = if (totalDurationMs > 0) totalDurationMs / 1000f else 1f

        val arguments = mutableListOf<String>().apply {
            add("-y")
            add("-i")
            add(inputFile.absolutePath)
            add("-vf")
            add("fps=$targetFps")
            add("-c:v")
            add("libx264")
            add("-preset")
            add("veryfast")
            add("-crf")
            add(crfQuality.toString())
            add("-pix_fmt")
            add("yuv420p")
            if (keepAudio) {
                add("-c:a")
                add("aac")
                add("-b:a")
                add("128k")
            } else {
                add("-an")
            }
            add("-movflags")
            add("+faststart")
            add(outputFile.absolutePath)
        }.toTypedArray()

        Log.d(TAG, "Executing FFmpeg with arguments: ${arguments.joinToString(" ")}")
        var lastReportedTime = 0L

        try {
            val session = FFmpegKit.executeWithArgumentsAsync(
                arguments,
                { completedSession ->
                    try {
                        val returnCode = completedSession.returnCode
                        when {
                            ReturnCode.isSuccess(returnCode) -> {
                                Log.d(TAG, "FFmpeg transcoding completed successfully: ${outputFile.length()} bytes")
                                trySend(TranscodeProgress.Success(outputFile))
                                close()
                            }
                            ReturnCode.isCancel(returnCode) -> {
                                Log.d(TAG, "FFmpeg transcoding cancelled by user")
                                if (outputFile.exists()) outputFile.delete()
                                trySend(TranscodeProgress.Cancelled("Transcoding cancelled"))
                                close()
                            }
                            else -> {
                                Log.w(TAG, "FFmpeg returned code $returnCode, falling back to Media3 FrameDropEffect")
                                launch {
                                    media3Transcoder.transcodeVideo(inputFile, targetFps, totalDurationMs, keepAudio).collect {
                                        trySend(it)
                                        if (it is TranscodeProgress.Success || it is TranscodeProgress.Error || it is TranscodeProgress.Cancelled) {
                                            close()
                                        }
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Exception in FFmpeg callback, falling back to Media3: ${t.message}")
                        launch {
                            media3Transcoder.transcodeVideo(inputFile, targetFps, totalDurationMs, keepAudio).collect {
                                trySend(it)
                                if (it is TranscodeProgress.Success || it is TranscodeProgress.Error || it is TranscodeProgress.Cancelled) {
                                    close()
                                }
                            }
                        }
                    }
                },
                { log ->
                    if (log.message.contains("Error", ignoreCase = true) || log.message.contains("fatal", ignoreCase = true)) {
                        Log.w(TAG, "FFmpeg Log: ${log.message}")
                    }
                },
                { stats ->
                    try {
                        val timeMs = stats.time.toLong()
                        if (timeMs > 0 && totalDurationMs > 0) {
                            val progressRatio = (timeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 0.99f)
                            val currentSec = (timeMs / 1000f)

                            val now = System.currentTimeMillis()
                            if (now - lastReportedTime > 150) {
                                lastReportedTime = now
                                trySend(
                                    TranscodeProgress.Progress(
                                        progressPercent = progressRatio,
                                        currentSeconds = currentSec,
                                        totalSeconds = totalDurationSec,
                                        speed = stats.speed.toDouble(),
                                        encodingFps = stats.videoFps.toDouble(),
                                        outputSizeBytes = stats.size
                                    )
                                )
                            }
                        }
                    } catch (ignored: Throwable) {}
                }
            )

            currentSession = session
        } catch (t: Throwable) {
            Log.w(TAG, "FFmpeg execution failed, falling back to Media3 FrameDropEffect: ${t.message}")
            launch {
                media3Transcoder.transcodeVideo(inputFile, targetFps, totalDurationMs, keepAudio).collect {
                    trySend(it)
                    if (it is TranscodeProgress.Success || it is TranscodeProgress.Error || it is TranscodeProgress.Cancelled) {
                        close()
                    }
                }
            }
        }

        awaitClose {
            media3Transcoder.cancel()
            try {
                if (currentSession?.state == SessionState.RUNNING) {
                    currentSession?.cancel()
                }
            } catch (ignored: Throwable) {}
            currentSession = null
        }
    }
}
