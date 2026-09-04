package com.example.vidcollage.collage

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.example.vidcollage.pipeline.Person
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the shareable collage: a 9:16 story card with one tile per person, their label and how many
 * times they appeared in the clip.
 */
object CollageRenderer {

    const val WIDTH = 1080
    const val HEIGHT = 1920

    private const val PADDING = 72f
    private const val GAP = 24f
    private const val TILE_RADIUS = 36f

    /** Tallest a tile may get relative to its width. */
    private const val MAX_TILE_ASPECT = 1.35f

    private const val INK = Color.WHITE
    private const val INK_DIM = 0xB3FFFFFF.toInt()

    fun render(videoName: String, people: List<Person>): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawBackground(canvas)
        val headerBottom = drawHeader(canvas, videoName, people)
        val footerTop = drawFooter(canvas)

        val stage = RectF(PADDING, headerBottom + GAP * 2, WIDTH - PADDING, footerTop - GAP * 2)
        if (people.isEmpty()) drawEmptyState(canvas, stage) else drawGrid(canvas, stage, people)

        return bitmap
    }

    // ---------------------------------------------------------------- background & chrome

    private fun drawBackground(canvas: Canvas) {
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, WIDTH * 0.35f, HEIGHT.toFloat(),
                intArrayOf(0xFF1B1033.toInt(), 0xFF3A1B54.toInt(), 0xFF120C22.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), base)

        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                WIDTH * 0.86f, HEIGHT * 0.08f, WIDTH * 0.75f,
                intArrayOf(0x66FF5F8F, 0x00FF5F8F),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), glow)

        val underglow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                WIDTH * 0.1f, HEIGHT * 0.95f, WIDTH * 0.7f,
                intArrayOf(0x5522D3EE, 0x0022D3EE),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), underglow)
    }

    private fun drawHeader(canvas: Canvas, videoName: String, people: List<Person>): Float {
        val eyebrow = textPaint(28f, Typeface.NORMAL, INK_DIM).apply { letterSpacing = 0.22f }
        canvas.drawText("UNIQUE PEOPLE IN", PADDING, PADDING + 34f, eyebrow)

        val title = textPaint(66f, Typeface.BOLD, INK)
        val trimmed = videoName.substringBeforeLast('.', videoName)
        val ellipsised = TextUtils.ellipsize(
            trimmed, title, WIDTH - 2 * PADDING, TextUtils.TruncateAt.MIDDLE,
        ).toString()
        canvas.drawText(ellipsised, PADDING, PADDING + 116f, title)

        val appearances = people.sumOf { it.appearanceCount }
        val summary = "${count(people.size, "person", "people")}  ·  ${count(appearances, "appearance", "appearances")}"
        val summaryPaint = textPaint(34f, Typeface.NORMAL, INK_DIM)
        canvas.drawText(summary, PADDING, PADDING + 172f, summaryPaint)

        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
            strokeWidth = 2f
        }
        val ruleY = PADDING + 208f
        canvas.drawLine(PADDING, ruleY, WIDTH - PADDING, ruleY, rule)
        return ruleY
    }

    private fun drawFooter(canvas: Canvas): Float {
        val paint = textPaint(28f, Typeface.NORMAL, 0x80FFFFFF.toInt()).apply { letterSpacing = 0.14f }
        val baseline = HEIGHT - PADDING
        canvas.drawText("MADE ON DEVICE WITH VIDCOLLAGE", PADDING, baseline, paint)
        return baseline - 44f
    }

    private fun drawEmptyState(canvas: Canvas, stage: RectF) {
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
        canvas.drawRoundRect(stage, TILE_RADIUS, TILE_RADIUS, card)
        val paint = textPaint(40f, Typeface.NORMAL, INK_DIM).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("No faces detected in this clip", stage.centerX(), stage.centerY(), paint)
    }

    // ---------------------------------------------------------------- the grid

    private fun drawGrid(canvas: Canvas, stage: RectF, people: List<Person>) {
        val columns = columnsFor(people.size)
        val rows = ceil(people.size / columns.toFloat()).toInt()

        // Tiles are portrait where there is spare height, which suits both faces and a 9:16 card.
        val availableWidth = (stage.width() - GAP * (columns - 1)) / columns
        val availableHeight = (stage.height() - GAP * (rows - 1)) / rows
        val tileWidth = min(availableWidth, availableHeight)
        val tileHeight = min(availableHeight, tileWidth * MAX_TILE_ASPECT)

        val gridHeight = tileHeight * rows + GAP * (rows - 1)
        val top = stage.top + (stage.height() - gridHeight) / 2f

        people.forEachIndexed { index, person ->
            val row = index / columns
            val column = index % columns
            // Centre a short final row instead of leaving a hole on the right.
            val inRow = min(columns, people.size - row * columns)
            val rowWidth = tileWidth * inRow + GAP * (inRow - 1)
            val left = stage.left + (stage.width() - rowWidth) / 2f + column * (tileWidth + GAP)
            val rowTop = top + row * (tileHeight + GAP)
            drawTile(canvas, RectF(left, rowTop, left + tileWidth, rowTop + tileHeight), person)
        }
    }

    /**
     * Column counts tuned for a 9:16 card: fewer, larger tiles beat a wide grid that leaves the
     * top and bottom of the story empty.
     */
    private fun columnsFor(count: Int): Int = when {
        count <= 2 -> 1
        count <= 6 -> 2
        else -> 3
    }

    private fun drawTile(canvas: Canvas, bounds: RectF, person: Person) {
        val photo = person.shot.bitmap
        val scale = max(bounds.width() / photo.width, bounds.height() / photo.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                bounds.left + (bounds.width() - photo.width * scale) / 2f,
                bounds.top + (bounds.height() - photo.height * scale) / 2f,
            )
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(matrix)
            }
        }
        canvas.drawRoundRect(bounds, TILE_RADIUS, TILE_RADIUS, fill)

        // Dark scrim under the caption so the label stays readable over any photo.
        val scrimHeight = bounds.height() * 0.42f
        val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, bounds.bottom - scrimHeight, 0f, bounds.bottom,
                0x00000000, 0xE6000000.toInt(), Shader.TileMode.CLAMP,
            )
        }
        val clip = Path().apply { addRoundRect(bounds, TILE_RADIUS, TILE_RADIUS, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(bounds.left, bounds.bottom - scrimHeight, bounds.right, bounds.bottom, scrim)
        canvas.restore()

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0x4DFFFFFF
        }
        canvas.drawRoundRect(bounds, TILE_RADIUS, TILE_RADIUS, border)

        val inset = bounds.width() * 0.075f
        val labelSize = (bounds.width() * 0.105f).coerceIn(22f, 52f)
        val label = textPaint(labelSize, Typeface.BOLD, INK)
        canvas.drawText(person.label, bounds.left + inset, bounds.bottom - inset * 1.55f, label)

        val captionSize = labelSize * 0.62f
        val caption = textPaint(captionSize, Typeface.NORMAL, INK_DIM)
        val seen = if (person.appearanceCount == 1) "1 appearance" else "${person.appearanceCount} appearances"
        canvas.drawText(seen, bounds.left + inset, bounds.bottom - inset * 0.55f, caption)

        drawCountBadge(canvas, bounds, person.appearanceCount, labelSize)
    }

    private fun drawCountBadge(canvas: Canvas, bounds: RectF, count: Int, labelSize: Float) {
        val text = "×$count"
        val paint = textPaint(labelSize * 0.78f, Typeface.BOLD, Color.WHITE).apply {
            textAlign = Paint.Align.CENTER
        }
        val padding = labelSize * 0.52f
        val width = max(paint.measureText(text) + padding * 2, labelSize * 2.1f)
        val height = labelSize * 1.55f
        val inset = bounds.width() * 0.055f
        val pill = RectF(
            bounds.right - inset - width,
            bounds.top + inset,
            bounds.right - inset,
            bounds.top + inset + height,
        )
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                pill.left, pill.top, pill.right, pill.bottom,
                0xFFFF5F8F.toInt(), 0xFFFF9A5A.toInt(), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(pill, height / 2f, height / 2f, background)

        val metrics = paint.fontMetrics
        val baseline = pill.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, pill.centerX(), baseline, paint)
    }

    // ---------------------------------------------------------------- helpers

    private fun textPaint(size: Float, style: Int, colour: Int): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = colour
            typeface = Typeface.create(Typeface.SANS_SERIF, style)
        }

    private fun count(value: Int, singular: String, plural: String): String =
        "$value ${if (value == 1) singular else plural}"
}
