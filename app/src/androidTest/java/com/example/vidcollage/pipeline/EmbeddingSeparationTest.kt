package com.example.vidcollage.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures how far apart FaceNet puts two photos of the same person versus two photos of different
 * people, which is what [FaceClusterer.MERGE_SIMILARITY] has to sit between.
 *
 * The fixtures are public-domain NASA portraits: two different photographs of each of three people.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingSeparationTest {

    /** The app under test, which is where facenet.tflite lives. */
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The test APK, which is where the calibration photos live. */
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private val fixtures = listOf(
        "a1" to "A", "a2" to "A",
        "b1" to "B", "b2" to "B",
        "c1" to "C", "c2" to "C",
    )

    @Test
    fun sameFaceScoresHigherThanDifferentFaces() {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build(),
        )

        FaceEmbedder(context).use { embedder ->
            assertEquals(160, embedder.inputSize)
            assertEquals(128, embedder.embeddingSize)

            val embeddings = fixtures.map { (name, person) ->
                val bitmap = loadAsset(name)
                val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
                assertTrue("No face found in $name", faces.isNotEmpty())
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                val aligned = FaceCrops.aligned(bitmap, face, embedder.inputSize)
                val embedding = embedder.embed(aligned)

                Log.i(TAG, "$name sharpness=${FaceCrops.sharpness(aligned)}")
                aligned.recycle()
                bitmap.recycle()
                Triple(name, person, embedding)
            }

            embeddings.forEach { (_, _, vector) ->
                assertEquals(1f, vector.fold(0f) { acc, v -> acc + v * v }, 1e-3f)
            }

            val same = mutableListOf<Float>()
            val different = mutableListOf<Float>()
            for (i in embeddings.indices) {
                for (j in i + 1 until embeddings.size) {
                    val (nameA, personA, a) = embeddings[i]
                    val (nameB, personB, b) = embeddings[j]
                    val similarity = Embeddings.cosineSimilarity(a, b)
                    val bucket = if (personA == personB) same else different
                    bucket += similarity
                    Log.i(TAG, "sim $nameA/$nameB (${if (personA == personB) "same" else "diff"}) = $similarity")
                }
            }

            val worstSame = same.min()
            val bestDifferent = different.max()
            Log.i(TAG, "worst same-person=$worstSame  best different-person=$bestDifferent")
            Log.i(TAG, "MERGE_SIMILARITY=${FaceClusterer.MERGE_SIMILARITY}")

            assertTrue(
                "Same-person similarity ($worstSame) must beat different-person ($bestDifferent)",
                worstSame > bestDifferent,
            )
            assertTrue(
                "MERGE_SIMILARITY (${FaceClusterer.MERGE_SIMILARITY}) must separate $bestDifferent from $worstSame",
                FaceClusterer.MERGE_SIMILARITY in bestDifferent..worstSame,
            )
        }
    }

    private fun loadAsset(name: String): Bitmap =
        testContext.assets.open("calibration/$name.jpg").use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })!!
        }

    private companion object {
        const val TAG = "EmbeddingSeparation"
    }
}
