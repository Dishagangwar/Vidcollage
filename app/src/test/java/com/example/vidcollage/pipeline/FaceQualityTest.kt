package com.example.vidcollage.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

class FaceQualityTest {

    private val ideal = FaceQuality(
        frontality = 1f,
        sharpness = 1f,
        eyesOpen = 1f,
        smiling = 1f,
        size = 1f,
        clipped = false,
    )

    @Test
    fun `a perfect face scores 1`() {
        assertTrue(ideal.score > 0.999f)
    }

    @Test
    fun `blur, closed eyes and a turned head each cost points`() {
        assertTrue(ideal.copy(sharpness = 0.1f).score < ideal.score)
        assertTrue(ideal.copy(eyesOpen = 0f).score < ideal.score)
        assertTrue(ideal.copy(frontality = 0.2f).score < ideal.score)
    }

    @Test
    fun `frontality outweighs a smile`() {
        val frontalButSerious = ideal.copy(smiling = 0f)
        val grinningButSideways = ideal.copy(frontality = 0f)
        assertTrue(frontalButSerious.score > grinningButSideways.score)
    }

    @Test
    fun `a solo frame beats the same face sharing the shot`() {
        assertTrue(ideal.score > ideal.copy(crowded = true).score)
    }

    @Test
    fun `a clipped face loses to an otherwise worse full face`() {
        val clipped = ideal.copy(clipped = true)
        val plainButWhole = ideal.copy(smiling = 0.4f, eyesOpen = 0.7f, sharpness = 0.7f)
        assertTrue(plainButWhole.score > clipped.score)
    }
}
