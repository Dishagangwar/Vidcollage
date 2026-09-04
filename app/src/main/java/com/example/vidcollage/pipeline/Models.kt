package com.example.vidcollage.pipeline

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri

/**
 * How good a single detected face is as a candidate for the collage tile.
 *
 * Every component is normalised to 0..1 so the weights in [score] are directly comparable.
 */
data class FaceQuality(
    /** 1.0 when the head points straight at the camera, falling off with yaw/pitch/roll. */
    val frontality: Float,
    /** Variance-of-Laplacian on a size-normalised crop, mapped to 0..1. */
    val sharpness: Float,
    /** Lower of the two "eye open" probabilities reported by ML Kit. */
    val eyesOpen: Float,
    /** ML Kit smiling probability. */
    val smiling: Float,
    /** Face height as a fraction of frame height, mapped to 0..1. */
    val size: Float,
    /** True when the face box runs into the frame border, i.e. the face is cut off. */
    val clipped: Boolean,
    /** True when another face sits close enough to crowd into this one's tile. */
    val crowded: Boolean = false,
) {
    val score: Float
        get() {
            val base = FRONTALITY_WEIGHT * frontality +
                SHARPNESS_WEIGHT * sharpness +
                EYES_OPEN_WEIGHT * eyesOpen +
                SMILING_WEIGHT * smiling +
                SIZE_WEIGHT * size
            var score = base
            if (clipped) score *= CLIPPED_PENALTY
            if (crowded) score *= CROWDED_PENALTY
            return score
        }

    companion object {
        const val FRONTALITY_WEIGHT = 0.30f
        const val SHARPNESS_WEIGHT = 0.25f
        const val EYES_OPEN_WEIGHT = 0.20f
        const val SMILING_WEIGHT = 0.15f
        const val SIZE_WEIGHT = 0.10f

        /** A cut-off face is still usable, but only if nothing better ever shows up. */
        const val CLIPPED_PENALTY = 0.55f

        /** A frame shared with someone else makes a worse portrait than a solo one. */
        const val CROWDED_PENALTY = 0.80f
    }
}

/** One face found in one sampled frame. */
class FaceObservation(
    val timestampMs: Long,
    val box: RectF,
    val frameWidth: Int,
    val frameHeight: Int,
    /** L2-normalised face embedding. */
    val embedding: FloatArray,
    val quality: FaceQuality,
    /** The other faces in the same frame, so the collage tile can be cropped around them. */
    val neighbours: List<RectF> = emptyList(),
)

/** The best frame we saw for a person, already cropped generously around the face. */
class RepresentativeShot(
    val bitmap: Bitmap,
    val timestampMs: Long,
    val quality: FaceQuality,
) {
    val score: Float get() = quality.score
}

/** A person, as they came out of clustering. */
class Person(
    val index: Int,
    val appearanceCount: Int,
    /** Start/end of every appearance, in milliseconds, ordered by time. */
    val appearances: List<LongRange>,
    val shot: RepresentativeShot,
) {
    val label: String get() = "Person ${index + 1}"
}

/** Everything we learned about one video. */
class VideoResult(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val framesAnalysed: Int,
    val people: List<Person>,
    val collage: Bitmap,
) {
    val totalAppearances: Int get() = people.sumOf { it.appearanceCount }
}

/** Progress pushed to the UI while a batch of videos is being processed. */
sealed interface ProcessingState {
    data object Idle : ProcessingState

    data class Running(
        val videoDisplayName: String,
        val videoIndex: Int,
        val videoCount: Int,
        val stage: Stage,
        /** 0..1 within the current video, or null when the stage has no measurable length. */
        val fraction: Float?,
    ) : ProcessingState

    data class Done(val results: List<VideoResult>, val failures: List<Failure>) : ProcessingState

    data class Failure(val displayName: String, val message: String)

    enum class Stage(val label: String) {
        OPENING("Opening video"),
        SCANNING("Detecting and embedding faces"),
        CLUSTERING("Grouping people"),
        COLLAGE("Building collage"),
    }
}
