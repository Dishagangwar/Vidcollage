package com.example.vidcollage.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pulls evenly spaced, display-oriented frames out of a video.
 *
 * Frames are decoded one at a time and downscaled on the way out, so a 30-second 1080p clip never
 * needs more than a single frame in memory.
 */
class FrameSampler(context: Context, uri: Uri) : Closeable {

    private val retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }

    val durationMs: Long = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

    /** Width/height of the decoded frames, after rotation metadata and downscaling. */
    val frameWidth: Int
    val frameHeight: Int

    init {
        val storedWidth = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val storedHeight = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val upright = rotation == 90 || rotation == 270
        val displayWidth = if (upright) storedHeight else storedWidth
        val displayHeight = if (upright) storedWidth else storedHeight

        val longSide = max(displayWidth, displayHeight)
        val scale = if (longSide > MAX_LONG_SIDE) MAX_LONG_SIDE.toFloat() / longSide else 1f
        frameWidth = max(1, (displayWidth * scale).roundToInt())
        frameHeight = max(1, (displayHeight * scale).roundToInt())
    }

    /** Sample timestamps covering the whole clip at [intervalMs] spacing. */
    fun timestamps(intervalMs: Long = SAMPLE_INTERVAL_MS): List<Long> {
        if (durationMs <= 0L) return listOf(0L)
        val count = max(1, (durationMs / intervalMs).toInt())
        return List(count) { min(it * intervalMs, durationMs) }
    }

    /** The frame nearest [timestampMs], or null when that position cannot be decoded. */
    fun frameAt(timestampMs: Long): Bitmap? {
        val timeUs = timestampMs * 1_000L
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                frameWidth,
                frameHeight,
            )
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let { full ->
                val scaled = Bitmap.createScaledBitmap(full, frameWidth, frameHeight, true)
                if (scaled !== full) full.recycle()
                scaled
            }
        } ?: return null

        // ML Kit and the crop helpers both want a readable ARGB_8888 buffer.
        if (frame.config == Bitmap.Config.ARGB_8888) return frame
        val converted = frame.copy(Bitmap.Config.ARGB_8888, false) ?: return frame
        frame.recycle()
        return converted
    }

    override fun close() {
        retriever.release()
    }

    private fun MediaMetadataRetriever.metadata(key: Int): String? = extractMetadata(key)

    companion object {
        /** 5 samples a second: short enough to catch a one-second appearance, cheap enough to stay quick. */
        const val SAMPLE_INTERVAL_MS = 200L

        /**
         * Frames are downscaled to this before detection. Measured on a Pixel 8a emulator, dropping
         * from 1280 to 960 cut ML Kit's per-frame cost from ~350 ms to ~120 ms with no change in the
         * people or appearance counts, and a 960-tall frame still leaves a generous tile crop.
         */
        const val MAX_LONG_SIDE = 960
    }
}
