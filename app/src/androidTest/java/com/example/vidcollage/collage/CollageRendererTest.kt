package com.example.vidcollage.collage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vidcollage.pipeline.FaceQuality
import com.example.vidcollage.pipeline.Person
import com.example.vidcollage.pipeline.RepresentativeShot
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the collage for every group size the grid has a case for and checks the result is a
 * full-size, fully painted card. The PNGs are also written to the app cache so the layouts can be
 * inspected by eye:
 *
 *   adb exec-out run-as com.example.vidcollage cat cache/collage_5.png > collage_5.png
 */
@RunWith(AndroidJUnit4::class)
class CollageRendererTest {

    private val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun everyGroupSizeFillsTheCard() {
        for (count in listOf(0, 1, 2, 3, 5, 8, 12)) {
            val people = List(count) { person(it) }
            val collage = CollageRenderer.render("holiday_clip.mp4", people)

            assertEquals(CollageRenderer.WIDTH, collage.width)
            assertEquals(CollageRenderer.HEIGHT, collage.height)
            assertTrue("Corner pixel should be painted", Color.alpha(collage.getPixel(4, 4)) == 255)
            assertTrue(
                "Tiles should be visible in the middle of the card",
                collage.getPixel(CollageRenderer.WIDTH / 2, CollageRenderer.HEIGHT / 2) !=
                    collage.getPixel(4, 4) || count == 0,
            )

            FileOutputStream(File(cacheDir, "collage_$count.png")).use {
                collage.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            collage.recycle()
            people.forEach { it.shot.bitmap.recycle() }
        }
    }

    private fun person(index: Int): Person {
        val hue = (index * 47f) % 360f
        val bitmap = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.75f)))
            drawCircle(240f, 200f, 120f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.HSVToColor(floatArrayOf(hue, 0.25f, 0.95f))
            })
        }
        val quality = FaceQuality(0.9f, 0.8f, 0.9f, 0.6f, 0.7f, clipped = false)
        return Person(
            index = index,
            appearanceCount = index % 5 + 1,
            appearances = listOf(0L..1_000L),
            shot = RepresentativeShot(bitmap, 0L, quality),
        )
    }
}
