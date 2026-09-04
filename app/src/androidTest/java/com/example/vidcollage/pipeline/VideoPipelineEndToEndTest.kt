package com.example.vidcollage.pipeline

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the whole pipeline over a real clip with known ground truth.
 *
 * The clip is not committed — drop a video at the path below and the test runs, otherwise it skips:
 *
 *   adb push clip.mp4 /sdcard/Android/data/com.example.vidcollage/files/testclip.mp4
 *
 * The bundled fixture used during development is a 28 s portrait clip built from public-domain NASA
 * portraits: three people, four appearances each, with two of them sharing the frame at 10.1-11.7 s.
 * Adjust [EXPECTED_PEOPLE] / [EXPECTED_APPEARANCES_EACH] if you use a different clip.
 */
@RunWith(AndroidJUnit4::class)
class VideoPipelineEndToEndTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun findsEveryPersonExactlyOnce() {
        val clip = File(context.getExternalFilesDir(null), "testclip.mp4")
        assumeTrue("No test clip at ${clip.absolutePath}", clip.exists())

        val result = runBlocking {
            VideoProcessor(context).use { processor ->
                processor.process(Uri.fromFile(clip), clip.name) { stage, fraction ->
                    if (fraction == null) Log.i(TAG, "stage=${stage.label}")
                }
            }
        }

        Log.i(TAG, "duration=${result.durationMs}ms frames=${result.framesAnalysed} people=${result.people.size}")
        result.people.forEach { person ->
            val windows = person.appearances.joinToString { "${it.first}-${it.last}" }
            Log.i(TAG, "${person.label}: ${person.appearanceCount} appearances [$windows] score=${person.shot.score}")
        }

        // Keep the collage around so it can be pulled off the device and eyeballed:
        //   adb exec-out run-as com.example.vidcollage cat cache/collage.png > collage.png
        FileOutputStream(File(context.cacheDir, "collage.png")).use {
            result.collage.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        assertEquals(EXPECTED_PEOPLE, result.people.size)
        result.people.forEach {
            assertEquals("${it.label} appearance count", EXPECTED_APPEARANCES_EACH, it.appearanceCount)
        }
        assertTrue("Every person needs a representative shot", result.people.all { !it.shot.bitmap.isRecycled })
    }

    private companion object {
        const val TAG = "PipelineE2E"
        const val EXPECTED_PEOPLE = 3
        const val EXPECTED_APPEARANCES_EACH = 4
    }
}
