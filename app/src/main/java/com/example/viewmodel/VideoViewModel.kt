package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.VideoMetadata
import com.example.transcoder.TranscodeProgress
import com.example.transcoder.VideoTranscoder
import com.example.util.MediaStoreUtils
import com.example.util.VideoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface VideoUiState {
    object Idle : VideoUiState
    data class Analyzing(val message: String) : VideoUiState
    data class Configuring(val metadata: VideoMetadata) : VideoUiState
    data class Processing(
        val originalMetadata: VideoMetadata,
        val progress: TranscodeProgress.Progress
    ) : VideoUiState
    data class Completed(
        val outputFile: File,
        val outputUri: Uri,
        val originalMetadata: VideoMetadata,
        val outputMetadata: VideoMetadata
    ) : VideoUiState
    data class Error(val message: String, val lastMetadata: VideoMetadata? = null) : VideoUiState
}

class VideoViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "VideoViewModel"
    }

    private val transcoder = VideoTranscoder(application)

    private val _uiState = MutableStateFlow<VideoUiState>(VideoUiState.Idle)
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()

    private val _targetFps = MutableStateFlow(12)
    val targetFps: StateFlow<Int> = _targetFps.asStateFlow()

    private val _keepAudio = MutableStateFlow(true)
    val keepAudio: StateFlow<Boolean> = _keepAudio.asStateFlow()

    private val _crfQuality = MutableStateFlow(22)
    val crfQuality: StateFlow<Int> = _crfQuality.asStateFlow()

    private var activeJob: Job? = null
    private var cachedInputFile: File? = null
    private var currentMetadata: VideoMetadata? = null

    fun loadVideo(uri: Uri) {
        activeJob?.cancel()
        _uiState.value = VideoUiState.Analyzing("Inspecting video and preparing media...")

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Pre-cache input file immediately so URI permissions are not lost
                val tempFile = VideoUtils.copyUriToTempFile(
                    getApplication(),
                    uri,
                    "input_${System.currentTimeMillis()}.mp4"
                )
                cachedInputFile?.delete()
                cachedInputFile = tempFile

                // Extract metadata from cached file
                val metadata = VideoUtils.extractMetadata(getApplication(), Uri.fromFile(tempFile))
                currentMetadata = metadata

                // Choose a smart default target FPS based on original
                val originalRounded = metadata.fpsRounded
                val defaultTarget = when {
                    originalRounded >= 60 -> 24
                    originalRounded >= 30 -> 12
                    originalRounded >= 24 -> 12
                    originalRounded >= 15 -> 8
                    else -> (originalRounded / 2).coerceAtLeast(1)
                }
                _targetFps.value = defaultTarget

                withContext(Dispatchers.Main) {
                    _uiState.value = VideoUiState.Configuring(metadata)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load video: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    _uiState.value = VideoUiState.Error("Failed to load video: ${t.localizedMessage ?: "Unknown error"}")
                }
            }
        }
    }

    fun setTargetFps(fps: Int) {
        _targetFps.value = fps.coerceIn(1, 60)
    }

    fun setKeepAudio(keep: Boolean) {
        _keepAudio.value = keep
    }

    fun setCrfQuality(crf: Int) {
        _crfQuality.value = crf
    }

    fun startProcessing() {
        val meta = currentMetadata ?: return
        val fps = _targetFps.value
        val keepAud = _keepAudio.value
        val crf = _crfQuality.value

        activeJob?.cancel()
        activeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Ensure cached file is available
                val inputFile = cachedInputFile ?: run {
                    withContext(Dispatchers.Main) {
                        _uiState.value = VideoUiState.Analyzing("Preparing media file for FFmpeg...")
                    }
                    val file = VideoUtils.copyUriToTempFile(
                        getApplication(),
                        meta.uri,
                        "input_${System.currentTimeMillis()}.mp4"
                    )
                    cachedInputFile = file
                    file
                }

                if (!inputFile.exists() || inputFile.length() == 0L) {
                    throw IllegalStateException("Video file is empty or inaccessible")
                }

                // Initial processing state
                val initialProgress = TranscodeProgress.Progress(
                    progressPercent = 0.01f,
                    currentSeconds = 0f,
                    totalSeconds = (meta.durationMs / 1000f).coerceAtLeast(1f),
                    speed = 1.0,
                    encodingFps = 0.0,
                    outputSizeBytes = 0L
                )
                withContext(Dispatchers.Main) {
                    _uiState.value = VideoUiState.Processing(meta, initialProgress)
                }

                transcoder.transcodeVideo(
                    inputFile = inputFile,
                    targetFps = fps,
                    totalDurationMs = meta.durationMs,
                    keepAudio = keepAud,
                    crfQuality = crf
                ).collect { progress ->
                    when (progress) {
                        is TranscodeProgress.Progress -> {
                            withContext(Dispatchers.Main) {
                                _uiState.value = VideoUiState.Processing(meta, progress)
                            }
                        }
                        is TranscodeProgress.Success -> {
                            val outUri = Uri.fromFile(progress.outputFile)
                            val outMeta = VideoUtils.extractMetadata(getApplication(), outUri)
                            withContext(Dispatchers.Main) {
                                _uiState.value = VideoUiState.Completed(
                                    outputFile = progress.outputFile,
                                    outputUri = outUri,
                                    originalMetadata = meta,
                                    outputMetadata = outMeta
                                )
                            }
                        }
                        is TranscodeProgress.Cancelled -> {
                            withContext(Dispatchers.Main) {
                                _uiState.value = VideoUiState.Configuring(meta)
                            }
                        }
                        is TranscodeProgress.Error -> {
                            withContext(Dispatchers.Main) {
                                _uiState.value = VideoUiState.Error(
                                    message = progress.message,
                                    lastMetadata = meta
                                )
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Transcoding exception: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    _uiState.value = VideoUiState.Error(
                        message = "Error during transcoding: ${t.localizedMessage ?: "Unknown error"}",
                        lastMetadata = meta
                    )
                }
            }
        }
    }

    fun cancelProcessing() {
        transcoder.cancel()
        activeJob?.cancel()
        currentMetadata?.let { meta ->
            _uiState.value = VideoUiState.Configuring(meta)
        } ?: run {
            _uiState.value = VideoUiState.Idle
        }
    }

    fun saveToGallery(onResult: (Boolean, String) -> Unit) {
        val state = _uiState.value
        if (state !is VideoUiState.Completed) {
            onResult(false, "No completed video available to save")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val savedUri = MediaStoreUtils.saveVideoToGallery(
                context = getApplication(),
                sourceFile = state.outputFile,
                baseName = state.originalMetadata.fileName,
                targetFps = _targetFps.value
            )
            withContext(Dispatchers.Main) {
                if (savedUri != null) {
                    onResult(true, "Video saved successfully to Movies/FPSReducer")
                } else {
                    onResult(false, "Failed to save video to device storage")
                }
            }
        }
    }

    fun getShareIntent(): Intent? {
        val state = _uiState.value as? VideoUiState.Completed ?: return null
        return MediaStoreUtils.createShareIntent(getApplication(), state.outputFile)
    }

    fun backToConfigure() {
        currentMetadata?.let {
            _uiState.value = VideoUiState.Configuring(it)
        }
    }

    fun reset() {
        activeJob?.cancel()
        transcoder.cancel()
        cachedInputFile?.delete()
        cachedInputFile = null
        currentMetadata = null
        _uiState.value = VideoUiState.Idle
        VideoUtils.cleanCache(getApplication())
    }

    override fun onCleared() {
        super.onCleared()
        activeJob?.cancel()
        transcoder.cancel()
        cachedInputFile?.delete()
    }
}
