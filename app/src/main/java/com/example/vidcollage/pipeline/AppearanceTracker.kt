package com.example.vidcollage.pipeline

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One continuous visible segment of one person — an "appearance" in the assignment's wording.
 *
 * Built up frame by frame by [AppearanceTracker] and then handed to [FaceClusterer], which decides
 * which appearances belong to the same person.
 */
class Appearance(val id: Int) {

    var startMs: Long = 0L
        private set
    var endMs: Long = 0L
        private set
    var frameCount: Int = 0
        private set
    var lastBox: RectF = RectF()
        private set

    private val embeddings = mutableListOf<FloatArray>()
    private var cachedCentroid: FloatArray? = null

    var best: RepresentativeShot? = null
        private set

    /** Unit-length average of every face in this segment; far steadier than any single frame. */
    val centroid: FloatArray
        get() = cachedCentroid ?: Embeddings.mean(embeddings).also { cachedCentroid = it }

    val timeRange: LongRange get() = startMs..endMs

    fun add(observation: FaceObservation) {
        if (frameCount == 0) startMs = observation.timestampMs
        endMs = observation.timestampMs
        frameCount++
        lastBox = RectF(observation.box)
        embeddings += observation.embedding
        cachedCentroid = null
    }

    /** Keeps [shot] only if it beats what we already have, recycling the loser. */
    fun offerShot(shot: RepresentativeShot) {
        val current = best
        if (current == null || shot.score > current.score) {
            current?.bitmap?.recycle()
            best = shot
        } else {
            shot.bitmap.recycle()
        }
    }

    fun overlaps(other: Appearance): Boolean = startMs <= other.endMs && other.startMs <= endMs
}

/**
 * Groups per-frame detections into continuous appearances.
 *
 * ML Kit's own tracking ids only exist in stream mode, and we deliberately decode sampled frames
 * rather than a live stream, so the association is done here: a detection continues an appearance
 * when it looks like the same face (embedding) in roughly the same place (box overlap / proximity)
 * and the segment has not been silent for too long.
 */
class AppearanceTracker(
    private val maxGapMs: Long = MAX_GAP_MS,
    private val minFrames: Int = MIN_FRAMES,
) {

    private val open = mutableListOf<Appearance>()
    private val closed = mutableListOf<Appearance>()
    private var nextId = 0

    /**
     * Assigns every observation in one frame to an appearance, creating new ones as needed.
     * @return each observation paired with the appearance it landed in, in the input order.
     */
    fun update(timestampMs: Long, observations: List<FaceObservation>): List<Pair<FaceObservation, Appearance>> {
        retireStaleSegments(timestampMs)

        val candidates = ArrayList<Match>(open.size * observations.size)
        for (observation in observations) {
            for (appearance in open) {
                affinity(observation, appearance)?.let { candidates += Match(observation, appearance, it) }
            }
        }
        candidates.sortByDescending { it.affinity }

        val assigned = HashMap<FaceObservation, Appearance>(observations.size)
        val taken = HashSet<Appearance>()
        for (match in candidates) {
            if (match.observation in assigned || match.appearance in taken) continue
            assigned[match.observation] = match.appearance
            taken += match.appearance
        }

        return observations.map { observation ->
            val appearance = assigned[observation] ?: Appearance(nextId++).also { open += it }
            appearance.add(observation)
            observation to appearance
        }
    }

    /** Closes every open segment and returns the appearances worth keeping. */
    fun finish(): List<Appearance> {
        closed += open
        open.clear()
        val (kept, dropped) = closed.partition { it.frameCount >= minFrames }
        dropped.forEach { it.best?.bitmap?.recycle() }
        return kept.sortedBy { it.startMs }
    }

    private fun retireStaleSegments(timestampMs: Long) {
        val iterator = open.iterator()
        while (iterator.hasNext()) {
            val appearance = iterator.next()
            if (timestampMs - appearance.endMs > maxGapMs) {
                closed += appearance
                iterator.remove()
            }
        }
    }

    private fun affinity(observation: FaceObservation, appearance: Appearance): Float? {
        val similarity = Embeddings.cosineSimilarity(observation.embedding, appearance.centroid)
        if (similarity < MIN_SIMILARITY) return null

        val overlap = intersectionOverUnion(observation.box, appearance.lastBox)
        val diagonal = sqrt(
            (observation.frameWidth.toFloat() * observation.frameWidth) +
                (observation.frameHeight.toFloat() * observation.frameHeight),
        )
        val dx = observation.box.centerX() - appearance.lastBox.centerX()
        val dy = observation.box.centerY() - appearance.lastBox.centerY()
        val distance = sqrt(dx * dx + dy * dy) / diagonal
        val proximity = (1f - distance / MAX_TRAVEL).coerceIn(0f, 1f)

        val affinity = SIMILARITY_WEIGHT * similarity + OVERLAP_WEIGHT * overlap + PROXIMITY_WEIGHT * proximity
        return affinity.takeIf { it >= MIN_AFFINITY }
    }

    private class Match(val observation: FaceObservation, val appearance: Appearance, val affinity: Float)

    companion object {
        /** A face may vanish for this long (a blink, a blurred pan) and still be the same appearance. */
        const val MAX_GAP_MS = 700L

        /** One lone detection is more often a false positive than a real appearance. */
        const val MIN_FRAMES = 2

        /** Frames are ~200 ms apart, so the same face stays very close to its own centroid. */
        const val MIN_SIMILARITY = 0.45f
        const val MIN_AFFINITY = 0.45f

        /** Fraction of the frame diagonal a face may travel between samples before proximity is nil. */
        const val MAX_TRAVEL = 0.35f

        const val SIMILARITY_WEIGHT = 0.55f
        const val OVERLAP_WEIGHT = 0.25f
        const val PROXIMITY_WEIGHT = 0.20f

        fun intersectionOverUnion(a: RectF, b: RectF): Float {
            val left = max(a.left, b.left)
            val top = max(a.top, b.top)
            val right = min(a.right, b.right)
            val bottom = min(a.bottom, b.bottom)
            if (right <= left || bottom <= top) return 0f
            val intersection = (right - left) * (bottom - top)
            val union = a.width() * a.height() + b.width() * b.height() - intersection
            return if (union <= 0f) 0f else intersection / union
        }
    }
}
