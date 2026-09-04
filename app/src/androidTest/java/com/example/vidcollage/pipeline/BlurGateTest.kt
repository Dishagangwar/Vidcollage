package com.example.vidcollage.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that [VideoProcessor.BLUR_FLOOR] actually sits between a crisp face and a whip-panned one.
 * The blur is synthesised by smearing the photo sideways, which is what a fast pan does to a frame.
 */
@RunWith(AndroidJUnit4::class)
class BlurGateTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun theBlurFloorSeparatesCrispFacesFromSmearedOnes() {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build(),
        )

        FaceEmbedder(context).use { embedder ->
            for (name in listOf("a1", "b1", "c1")) {
                val sharp = loadAsset(name)
                val smeared = motionBlurred(sharp, SMEAR_PIXELS)

                val sharpScore = sharpnessOfLargestFace(detector, embedder, sharp, name)
                val smearedScore = sharpnessOfLargestFace(detector, embedder, smeared, "$name-blurred")

                Log.i(TAG, "$name crisp=$sharpScore smeared=$smearedScore floor=${VideoProcessor.BLUR_FLOOR}")
                assertTrue(
                    "A crisp $name should clear the blur floor",
                    sharpScore > VideoProcessor.BLUR_FLOOR,
                )
                assertTrue(
                    "A ${SMEAR_PIXELS}px smear of $name should be rejected ($smearedScore)",
                    smearedScore < VideoProcessor.BLUR_FLOOR,
                )

                sharp.recycle()
                smeared.recycle()
            }
        }
    }

    /** Returns the sharpness of the biggest face, or 0 when the blur destroyed the detection. */
    private fun sharpnessOfLargestFace(
        detector: com.google.mlkit.vision.face.FaceDetector,
        embedder: FaceEmbedder,
        bitmap: Bitmap,
        label: String,
    ): Float {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        val face: Face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: run {
                Log.i(TAG, "$label: no face detected at all")
                return 0f
            }
        val aligned = FaceCrops.aligned(bitmap, face, embedder.inputSize)
        val sharpness = FaceCrops.sharpness(aligned)
        aligned.recycle()
        return sharpness
    }

    /** Stacks translated copies of the frame to imitate a horizontal camera whip. */
    private fun motionBlurred(source: Bitmap, pixels: Int): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val steps = 16
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 255 / steps }
        canvas.drawColor(android.graphics.Color.BLACK)
        for (step in 0 until steps) {
            val offset = (step - steps / 2f) * (pixels / steps.toFloat())
            canvas.drawBitmap(source, offset, 0f, paint)
        }
        return output
    }

    private fun loadAsset(name: String): Bitmap =
        testContext.assets.open("calibration/$name.jpg").use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })!!
        }

    private companion object {
        const val TAG = "BlurGate"

        /** Roughly what a 1/30 s frame looks like during a fast pan across a 900 px wide photo. */
        const val SMEAR_PIXELS = 40
    }
}
