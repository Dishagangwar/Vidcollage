package com.example.vidcollage.pipeline

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingsTest {

    @Test
    fun `normalise returns a unit vector`() {
        val normalised = Embeddings.normalise(floatArrayOf(3f, 4f))
        assertEquals(0.6f, normalised[0], 1e-5f)
        assertEquals(0.8f, normalised[1], 1e-5f)
    }

    @Test
    fun `normalise leaves a zero vector alone instead of dividing by zero`() {
        val normalised = Embeddings.normalise(floatArrayOf(0f, 0f, 0f))
        assertTrue(normalised.all { it == 0f })
    }

    @Test
    fun `cosine similarity is 1 for identical directions and 0 for orthogonal ones`() {
        val a = Embeddings.normalise(floatArrayOf(1f, 2f, 3f))
        val b = Embeddings.normalise(floatArrayOf(2f, 4f, 6f))
        val c = Embeddings.normalise(floatArrayOf(-2f, 1f, 0f))

        assertEquals(1f, Embeddings.cosineSimilarity(a, b), 1e-5f)
        assertEquals(0f, Embeddings.cosineSimilarity(a, c), 1e-5f)
    }

    @Test
    fun `mean lands between its inputs and stays unit length`() {
        val a = Embeddings.normalise(floatArrayOf(1f, 0f))
        val b = Embeddings.normalise(floatArrayOf(0f, 1f))
        val mean = Embeddings.mean(listOf(a, b))

        assertEquals(1f, mean.fold(0f) { acc, v -> acc + v * v }, 1e-5f)
        assertTrue(abs(mean[0] - mean[1]) < 1e-5f)
    }
}
