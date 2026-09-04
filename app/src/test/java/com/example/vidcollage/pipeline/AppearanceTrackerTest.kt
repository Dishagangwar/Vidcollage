package com.example.vidcollage.pipeline

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the temporal half of the tracker. Box geometry is exercised on device rather than here,
 * because android.graphics.RectF is a stub on the JVM.
 */
class AppearanceTrackerTest {

    @Test
    fun `a face seen in consecutive frames is one appearance`() {
        val tracker = AppearanceTracker()
        val alice = unit(0)
        feed(tracker, from = 0L, until = 1_200L, embedding = alice)

        val appearances = tracker.finish()

        assertEquals(1, appearances.size)
        assertEquals(0L, appearances.single().startMs)
        assertEquals(1_200L, appearances.single().endMs)
        assertEquals(7, appearances.single().frameCount)
    }

    @Test
    fun `leaving the shot for longer than the gap starts a new appearance`() {
        val tracker = AppearanceTracker()
        val alice = unit(0)
        feed(tracker, from = 0L, until = 600L, embedding = alice)
        feed(tracker, from = 4_000L, until = 4_600L, embedding = alice)

        val appearances = tracker.finish()

        assertEquals(2, appearances.size)
        assertEquals(listOf(0L, 4_000L), appearances.map { it.startMs })
    }

    @Test
    fun `a blink-length gap does not split an appearance`() {
        val tracker = AppearanceTracker()
        val alice = unit(0)
        feed(tracker, from = 0L, until = 600L, embedding = alice)
        feed(tracker, from = 1_200L, until = 1_800L, embedding = alice)

        assertEquals(1, tracker.finish().size)
    }

    @Test
    fun `two people in the same frame are two appearances`() {
        val tracker = AppearanceTracker()
        val alice = unit(0)
        val bob = unit(1)
        for (timestamp in 0L..800L step 200L) {
            tracker.update(timestamp, listOf(observation(timestamp, alice), observation(timestamp, bob)))
        }

        val appearances = tracker.finish()

        assertEquals(2, appearances.size)
        appearances.forEach { assertEquals(5, it.frameCount) }
    }

    @Test
    fun `a one-frame flicker is discarded`() {
        val tracker = AppearanceTracker()
        tracker.update(0L, listOf(observation(0L, unit(0))))

        assertEquals(0, tracker.finish().size)
    }

    @Test
    fun `the centroid sits between the frames it was built from`() {
        val tracker = AppearanceTracker()
        val first = unit(0)
        val second = Embeddings.normalise(floatArrayOf(1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f))
        tracker.update(0L, listOf(observation(0L, first)))
        tracker.update(200L, listOf(observation(200L, second)))

        val centroid = tracker.finish().single().centroid

        assertEquals(1f, centroid.fold(0f) { acc, v -> acc + v * v }, 1e-5f)
        assertEquals(
            Embeddings.cosineSimilarity(centroid, first),
            Embeddings.cosineSimilarity(centroid, second),
            1e-5f,
        )
    }

    // ---------------------------------------------------------------- fixtures

    private fun feed(tracker: AppearanceTracker, from: Long, until: Long, embedding: FloatArray) {
        var timestamp = from
        while (timestamp <= until) {
            tracker.update(timestamp, listOf(observation(timestamp, embedding)))
            timestamp += 200L
        }
    }

    private fun unit(axis: Int): FloatArray = FloatArray(8) { if (it == axis) 1f else 0f }

    private fun observation(timestampMs: Long, embedding: FloatArray) = FaceObservation(
        timestampMs = timestampMs,
        box = RectF(),
        frameWidth = 720,
        frameHeight = 1280,
        embedding = embedding,
        quality = FaceQuality(0.8f, 0.7f, 0.9f, 0.5f, 0.6f, clipped = false),
    )
}
