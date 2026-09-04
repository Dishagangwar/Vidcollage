package com.example.vidcollage.pipeline

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceClustererTest {

    @Test
    fun `appearances of the same face collapse into one person`() {
        val alice = direction(0)
        val appearances = listOf(
            appearance(id = 0, startMs = 0, endMs = 1_000, embedding = alice),
            appearance(id = 1, startMs = 5_000, endMs = 6_000, embedding = jitter(alice, 0.10f)),
            appearance(id = 2, startMs = 9_000, endMs = 9_800, embedding = jitter(alice, -0.08f)),
        )

        val clusters = FaceClusterer.cluster(appearances)

        assertEquals(1, clusters.size)
        assertEquals(3, clusters.single().size)
    }

    @Test
    fun `different faces stay apart`() {
        val clusters = FaceClusterer.cluster(
            listOf(
                appearance(id = 0, startMs = 0, endMs = 1_000, embedding = direction(0)),
                appearance(id = 1, startMs = 2_000, endMs = 3_000, embedding = direction(1)),
                appearance(id = 2, startMs = 4_000, endMs = 5_000, embedding = direction(2)),
            ),
        )

        assertEquals(3, clusters.size)
    }

    @Test
    fun `two faces on screen together are never merged, however alike they look`() {
        val twin = direction(0)
        val clusters = FaceClusterer.cluster(
            listOf(
                appearance(id = 0, startMs = 10_100, endMs = 11_500, embedding = twin),
                appearance(id = 1, startMs = 10_100, endMs = 11_500, embedding = twin),
            ),
        )

        assertEquals(2, clusters.size)
    }

    @Test
    fun `an appearance that overlaps only one member still blocks the whole cluster`() {
        val face = direction(0)
        val clusters = FaceClusterer.cluster(
            listOf(
                appearance(id = 0, startMs = 0, endMs = 1_000, embedding = face),
                appearance(id = 1, startMs = 5_000, endMs = 6_000, embedding = face),
                // Simultaneous with appearance 1, so it cannot join the cluster those two form.
                appearance(id = 2, startMs = 5_500, endMs = 6_500, embedding = face),
            ),
        )

        assertEquals(2, clusters.size)
        assertEquals(listOf(2, 1), clusters.map { it.size })
    }

    @Test
    fun `people come back ordered by when they were first seen`() {
        val clusters = FaceClusterer.cluster(
            listOf(
                appearance(id = 0, startMs = 8_000, endMs = 9_000, embedding = direction(1)),
                appearance(id = 1, startMs = 1_000, endMs = 2_000, embedding = direction(0)),
            ),
        )

        assertEquals(listOf(1_000L, 8_000L), clusters.map { it.first().startMs })
    }

    @Test
    fun `an empty input yields no people`() {
        assertEquals(emptyList<List<Appearance>>(), FaceClusterer.cluster(emptyList()))
    }

    // ---------------------------------------------------------------- fixtures

    /** Mutually orthogonal unit vectors, i.e. maximally different "faces". */
    private fun direction(axis: Int): FloatArray =
        FloatArray(DIMENSIONS) { if (it == axis) 1f else 0f }

    /** Nudges [base] towards another axis, the way real frames of one person vary. */
    private fun jitter(base: FloatArray, amount: Float): FloatArray {
        val moved = base.copyOf()
        moved[DIMENSIONS - 1] += amount
        return Embeddings.normalise(moved)
    }

    private fun appearance(id: Int, startMs: Long, endMs: Long, embedding: FloatArray): Appearance =
        Appearance(id).apply {
            var timestamp = startMs
            while (timestamp <= endMs) {
                add(
                    FaceObservation(
                        timestampMs = timestamp,
                        box = RectF(),
                        frameWidth = 720,
                        frameHeight = 1280,
                        embedding = embedding,
                        quality = quality(),
                    ),
                )
                timestamp += 200L
            }
        }

    private fun quality() = FaceQuality(
        frontality = 0.8f,
        sharpness = 0.7f,
        eyesOpen = 0.9f,
        smiling = 0.5f,
        size = 0.6f,
        clipped = false,
    )

    private companion object {
        const val DIMENSIONS = 8
    }
}
