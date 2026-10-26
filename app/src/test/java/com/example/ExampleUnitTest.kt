package com.example

import android.net.Uri
import com.example.model.VideoMetadata
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ExampleUnitTest {
    @Test
    fun testVideoMetadata_formatting() {
        val metadata = VideoMetadata(
            uri = Uri.EMPTY,
            fileName = "sample_animation.mp4",
            durationMs = 65000L,
            width = 1920,
            height = 1080,
            fps = 29.97,
            fileSizeBytes = 15 * 1024 * 1024L
        )

        assertEquals("01:05", metadata.formattedDuration)
        assertEquals("65.0s", metadata.formattedDurationWithMillis)
        assertEquals("30.0 FPS", metadata.formattedFps)
        assertEquals(29, metadata.fpsRounded)
        assertEquals("15.0 MB", metadata.formattedSize)
        assertEquals("1920 × 1080 (1080p FHD)", metadata.formattedResolution)
    }

    @Test
    fun testVideoMetadata_lowFps() {
        val metadata = VideoMetadata(
            uri = Uri.EMPTY,
            fileName = "claymation.mp4",
            durationMs = 8000L,
            width = 1280,
            height = 720,
            fps = 12.0,
            fileSizeBytes = 2 * 1024 * 1024L
        )

        assertEquals("00:08", metadata.formattedDuration)
        assertEquals("12.0 FPS", metadata.formattedFps)
        assertEquals(12, metadata.fpsRounded)
    }
}
