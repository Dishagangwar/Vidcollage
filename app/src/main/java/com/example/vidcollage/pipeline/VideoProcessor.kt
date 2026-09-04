package com.example.vidcollage.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import com.example.vidcollage.collage.CollageRenderer
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext

/**
 * The whole on-device pipeline for one video:
 * sample frames -> detect faces -> embed them -> stitch detections into appearances ->
 * cluster appearances into people -> pick each person's best shot -> render the collage.
 */
class VideoProcessor(private val context: Context) : Closeable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(MIN_FACE_SIZE)
            .build(),
    )

    private val embedder by lazy { FaceEmbedder(context) }

    suspend fun process(
        uri: Uri,
        displayName: String,
        onProgress: (ProcessingState.Stage, Float?) -> Unit,
    ): VideoResult {
        onProgress(ProcessingState.Stage.OPENING, null)

        val tracker = AppearanceTracker()
        var framesAnalysed = 0
        var durationMs = 0L

        FrameSampler(context, uri).use { sampler ->
            durationMs = sampler.durationMs
            val timestamps = sampler.timestamps()
            onProgress(ProcessingState.Stage.SCANNING, 0f)

            timestamps.forEachIndexed { index, timestampMs ->
                currentCoroutineContext().ensureActive()
                val frame = sampler.frameAt(timestampMs)
                if (frame != null) {
                    framesAnalysed++
                    scanFrame(frame, timestampMs, tracker)
                    frame.recycle()
                }
                onProgress(ProcessingState.Stage.SCANNING, (index + 1f) / timestamps.size)
            }
        }

        onProgress(ProcessingState.Stage.CLUSTERING, null)
        val appearances = tracker.finish()
        val clusters = FaceClusterer.cluster(appearances)
        val people = clusters.mapIndexedNotNull { index, members -> toPerson(index, members) }

        onProgress(ProcessingState.Stage.COLLAGE, null)
        val collage = CollageRenderer.render(displayName, people)

        return VideoResult(
            uri = uri,
            displayName = displayName,
            durationMs = durationMs,
            framesAnalysed = framesAnalysed,
            people = people,
            collage = collage,
        )
    }

    private suspend fun scanFrame(frame: Bitmap, timestampMs: Long, tracker: AppearanceTracker) {
        val faces = detector.process(InputImage.fromBitmap(frame, 0)).await()
        if (faces.isEmpty()) return

        val kept = deduplicate(faces)
        val boxes = kept.map { RectF(it.boundingBox) }
        val observations = kept.mapIndexedNotNull { index, face ->
            observe(frame, face, timestampMs, boxes.filterIndexed { other, _ -> other != index })
        }
        if (observations.isEmpty()) return

        for ((observation, appearance) in tracker.update(timestampMs, observations)) {
            val incumbent = appearance.best
            if (incumbent != null && incumbent.score >= observation.quality.score) continue
            val bounds = FaceCrops.tileBounds(frame, observation.box, observation.neighbours)
            val tile = FaceCrops.tile(frame, bounds, TILE_MAX_SIZE)
            appearance.offerShot(RepresentativeShot(tile, timestampMs, observation.quality))
        }
    }

    /**
     * ML Kit occasionally reports one face several times over: the whole head plus a couple of
     * boxes nested inside it. Left alone those duplicates become simultaneous appearances, which
     * the clusterer is then forbidden to merge, so one person turns into three. Keep the outermost
     * box of each nest.
     */
    private fun deduplicate(faces: List<Face>): List<Face> {
        if (faces.size < 2) return faces
        val kept = mutableListOf<Face>()
        for (face in faces.sortedByDescending { it.boundingBox.area() }) {
            if (kept.none { containment(it.boundingBox, face.boundingBox) > MAX_BOX_CONTAINMENT }) {
                kept += face
            }
        }
        return kept
    }

    /** Measures one detected face and embeds it, or returns null if it is too blurred to trust. */
    private fun observe(
        frame: Bitmap,
        face: Face,
        timestampMs: Long,
        neighbours: List<RectF>,
    ): FaceObservation? {
        val aligned = FaceCrops.aligned(frame, face, embedder.inputSize)
        val sharpness = FaceCrops.sharpness(aligned)
        // A whip-pan smears every face in the frame; those detections belong to nobody.
        if (sharpness < BLUR_FLOOR) {
            aligned.recycle()
            return null
        }
        val embedding = embedder.embed(aligned)
        aligned.recycle()

        val box = RectF(face.boundingBox)
        val tile = FaceCrops.tileBounds(frame, box, neighbours)
        return FaceObservation(
            timestampMs = timestampMs,
            box = box,
            frameWidth = frame.width,
            frameHeight = frame.height,
            embedding = embedding,
            quality = FaceQuality(
                frontality = frontality(face),
                sharpness = sharpness / (sharpness + SHARPNESS_MIDPOINT),
                eyesOpen = eyesOpen(face),
                smiling = face.smilingProbability ?: NEUTRAL_PROBABILITY,
                size = min(1f, box.height() / frame.height / IDEAL_FACE_FRACTION),
                clipped = isClipped(box, frame.width, frame.height),
                crowded = neighbours.any { RectF.intersects(it, tile) },
            ),
            neighbours = neighbours,
        )
    }

    private fun toPerson(index: Int, members: List<Appearance>): Person? {
        val shots = members.mapNotNull { it.best }
        val best = shots.maxByOrNull { it.score } ?: return null
        shots.filter { it !== best }.forEach { it.bitmap.recycle() }
        return Person(
            index = index,
            appearanceCount = members.size,
            appearances = members.map { it.timeRange },
            shot = best,
        )
    }

    override fun close() {
        detector.close()
        embedder.close()
    }

    /** Tuning knobs, public so the instrumented tests can assert against the real numbers. */
    companion object {
        /** Longest side of a kept representative crop: enough for a collage tile, cheap to hold. */
        const val TILE_MAX_SIZE = 480

        /** Ignore faces smaller than this fraction of the frame; they carry no usable identity. */
        const val MIN_FACE_SIZE = 0.09f

        /**
         * Laplacian variance below which a face is treated as motion blur.
         *
         * Measured on device (see BlurGateTest): crisp portraits land above 1000, while a 40 px
         * horizontal smear — roughly one frame of a fast pan — drops to the low tens.
         */
        const val BLUR_FLOOR = 80f

        /** Sharpness that maps to a score of 0.5. */
        const val SHARPNESS_MIDPOINT = 140f

        /** A face this tall relative to the frame already scores full marks for size. */
        const val IDEAL_FACE_FRACTION = 0.28f

        /** Used when ML Kit cannot classify eyes or a smile. */
        const val NEUTRAL_PROBABILITY = 0.5f

        /** Two boxes overlapping this much of the smaller one are the same face reported twice. */
        const val MAX_BOX_CONTAINMENT = 0.6f

        /** How close to the border counts as "cut off", as a fraction of the frame. */
        const val EDGE_TOLERANCE = 0.012f

        private fun Rect.area(): Long = width().toLong() * height()

        /** Overlap of two boxes as a fraction of the smaller one, so nesting scores 1. */
        private fun containment(a: Rect, b: Rect): Float {
            val left = maxOf(a.left, b.left)
            val top = maxOf(a.top, b.top)
            val right = minOf(a.right, b.right)
            val bottom = minOf(a.bottom, b.bottom)
            if (right <= left || bottom <= top) return 0f
            val intersection = (right - left).toLong() * (bottom - top)
            val smaller = minOf(a.area(), b.area())
            return if (smaller <= 0L) 0f else intersection.toFloat() / smaller
        }

        private fun frontality(face: Face): Float {
            val yaw = 1f - min(1f, abs(face.headEulerAngleY) / 45f)
            val pitch = 1f - min(1f, abs(face.headEulerAngleX) / 35f)
            val roll = 1f - min(1f, abs(face.headEulerAngleZ) / 30f)
            return 0.5f * yaw + 0.3f * pitch + 0.2f * roll
        }

        private fun eyesOpen(face: Face): Float {
            val left = face.leftEyeOpenProbability ?: return NEUTRAL_PROBABILITY
            val right = face.rightEyeOpenProbability ?: return NEUTRAL_PROBABILITY
            return min(left, right)
        }

        private fun isClipped(box: RectF, width: Int, height: Int): Boolean {
            val marginX = width * EDGE_TOLERANCE
            val marginY = height * EDGE_TOLERANCE
            return box.left <= marginX ||
                box.top <= marginY ||
                box.right >= width - marginX ||
                box.bottom >= height - marginY
        }
    }
}

/** Bridges a Play services [Task] into a cancellable coroutine. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
