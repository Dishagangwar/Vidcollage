package com.example.vidcollage.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bitmap surgery: the tight aligned crop the embedder wants, and the generous crop the collage wants. */
object FaceCrops {

    /** How much wider than the detected box the collage tile is. */
    const val TILE_MARGIN = 2.2f

    /** The tightest a tile may ever get, even when another face is crowding in. */
    const val MIN_TILE_MARGIN = 1.35f

    /** The face box, padded a little, so the embedder sees some context around the features. */
    private const val ALIGN_MARGIN = 1.25f

    /** Nudges the crop window down so the head sits in the upper half of the tile. */
    private const val TILE_HEADROOM = 0.08f

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * Square crop centred on [face], rotated so the eyes are level and scaled to [size].
     * Rotating first means a tilted head still lands in the pose the embedding model was trained on.
     */
    fun aligned(frame: Bitmap, face: Face, size: Int): Bitmap {
        val box = face.boundingBox
        val centreX = box.exactCenterX()
        val centreY = box.exactCenterY()
        val side = max(box.width(), box.height()) * ALIGN_MARGIN
        val scale = size / side

        val matrix = Matrix().apply {
            postTranslate(-centreX, -centreY)
            postRotate(-rollDegrees(face))
            postScale(scale, scale)
            postTranslate(size / 2f, size / 2f)
        }

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(frame, matrix, filterPaint)
        return output
    }

    /**
     * The square region a collage tile is cut from: centred on the face, generously padded, pulled
     * back only far enough to keep [neighbours] — other faces in the same frame — out of the shot.
     */
    fun tileBounds(frame: Bitmap, box: RectF, neighbours: List<RectF> = emptyList()): RectF {
        val limit = min(frame.width, frame.height).toFloat()
        val face = max(box.width(), box.height())
        var side = min(face * TILE_MARGIN, limit)

        for (neighbour in neighbours) {
            val clearance = clearanceTo(box, neighbour)
            if (clearance > 0f) side = min(side, clearance * 2f)
        }
        // Never crop tighter than this: a tight box crop is exactly the low-resolution tile the
        // brief asks us to avoid, so a crowded frame keeps its neighbour rather than losing context.
        side = max(side, face * MIN_TILE_MARGIN).coerceAtMost(limit)

        val left = (box.centerX() - side / 2f).coerceIn(0f, frame.width - side)
        val top = (box.centerY() + side * TILE_HEADROOM - side / 2f)
            .coerceIn(0f, frame.height - side)
        return RectF(left, top, left + side, top + side)
    }

    /** Cuts [bounds] out of [frame] and scales it down to at most [maxSize] on a side. */
    fun tile(frame: Bitmap, bounds: RectF, maxSize: Int): Bitmap {
        val side = bounds.width().roundToInt().coerceIn(1, min(frame.width, frame.height))
        val left = bounds.left.roundToInt().coerceIn(0, frame.width - side)
        val top = bounds.top.roundToInt().coerceIn(0, frame.height - side)

        val cropped = Bitmap.createBitmap(frame, left, top, side, side)
        if (side <= maxSize) return cropped

        val scaled = Bitmap.createScaledBitmap(cropped, maxSize, maxSize, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    /**
     * Distance from the centre of [box] to the near edge of [neighbour] along whichever axis
     * actually separates them, or 0 when the two boxes overlap on both axes.
     */
    private fun clearanceTo(box: RectF, neighbour: RectF): Float {
        val horizontal = when {
            neighbour.left >= box.right -> neighbour.left - box.centerX()
            neighbour.right <= box.left -> box.centerX() - neighbour.right
            else -> Float.NEGATIVE_INFINITY
        }
        val vertical = when {
            neighbour.top >= box.bottom -> neighbour.top - box.centerY()
            neighbour.bottom <= box.top -> box.centerY() - neighbour.bottom
            else -> Float.NEGATIVE_INFINITY
        }
        val clearance = max(horizontal, vertical)
        return if (clearance == Float.NEGATIVE_INFINITY) 0f else max(clearance, 0f)
    }

    /**
     * Variance of the Laplacian over the middle of [alignedFace], which is where the eyes, nose and
     * mouth are. Because the crop is always the same pixel size, the number is comparable between
     * a face filling the frame and a face far away — that is what makes it usable as a blur gate.
     */
    fun sharpness(alignedFace: Bitmap): Float {
        val size = alignedFace.width
        val inset = size / 5
        val width = size - 2 * inset
        val pixels = IntArray(width * width)
        alignedFace.getPixels(pixels, 0, width, inset, inset, width, width)

        val luma = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            luma[i] = 0.299f * ((p shr 16) and 0xFF) +
                0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)
        }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until width - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val response = 4f * luma[i] -
                    luma[i - 1] - luma[i + 1] - luma[i - width] - luma[i + width]
                sum += response
                sumSq += response.toDouble() * response
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sum / count
        return max(0.0, sumSq / count - mean * mean).toFloat()
    }

    /** Image-plane roll of the head, measured from the eyes when ML Kit found them. */
    private fun rollDegrees(face: Face): Float {
        val eyeA = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val eyeB = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        if (eyeA == null || eyeB == null) return -face.headEulerAngleZ
        // Order the two points by x so the vector always points right in image space. That keeps the
        // result in -90..90 regardless of which eye ML Kit calls "left".
        val (from, to) = if (eyeA.x <= eyeB.x) eyeA to eyeB else eyeB to eyeA
        return Math.toDegrees(
            atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble()),
        ).toFloat()
    }
}
