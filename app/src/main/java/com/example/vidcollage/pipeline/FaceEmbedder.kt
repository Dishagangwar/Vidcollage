package com.example.vidcollage.pipeline

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt
import org.tensorflow.lite.Interpreter

/**
 * Turns an aligned face crop into a fixed-length embedding using the bundled FaceNet model
 * (`assets/facenet.tflite`, 160x160 RGB in, 128-D out).
 *
 * The interpreter is not thread safe, so a single instance belongs to a single processing job.
 */
class FaceEmbedder(context: Context) : Closeable {

    private val interpreter: Interpreter
    private val input: ByteBuffer

    /** Side length of the square crop the model expects. */
    val inputSize: Int

    /** Length of the embedding vector the model produces. */
    val embeddingSize: Int

    init {
        val model = context.assets.openFd(MODEL_ASSET).use { fd ->
            fd.createInputStream().use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(NUM_THREADS))
        // Read the geometry off the model rather than hardcoding it, so swapping in a different
        // embedding network only means replacing the asset.
        inputSize = interpreter.getInputTensor(0).shape().let { it[1] }
        embeddingSize = interpreter.getOutputTensor(0).shape().last()
        input = ByteBuffer.allocateDirect(inputSize * inputSize * CHANNELS * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
    }

    /**
     * @param face a square, eye-levelled crop of [inputSize] pixels.
     * @return the L2-normalised embedding, so similarity is a plain dot product.
     */
    fun embed(face: Bitmap): FloatArray {
        require(face.width == inputSize && face.height == inputSize) {
            "Expected a ${inputSize}x$inputSize crop but got ${face.width}x${face.height}"
        }
        val pixels = IntArray(inputSize * inputSize)
        face.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // FaceNet expects per-image standardisation ("prewhitening"), not a fixed mean/scale.
        var sum = 0.0
        var sumSq = 0.0
        val channels = FloatArray(pixels.size * CHANNELS)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            channels[i * 3] = r
            channels[i * 3 + 1] = g
            channels[i * 3 + 2] = b
            sum += r + g + b
            sumSq += r * r + g * g + b * b
        }
        val n = channels.size
        val mean = (sum / n).toFloat()
        val variance = (sumSq / n - mean.toDouble() * mean).toFloat()
        val std = max(sqrt(max(variance, 0f)), 1f / sqrt(n.toFloat()))

        input.rewind()
        for (value in channels) {
            input.putFloat((value - mean) / std)
        }
        input.rewind()

        val output = Array(1) { FloatArray(embeddingSize) }
        interpreter.run(input, output)
        return Embeddings.normalise(output[0])
    }

    override fun close() {
        interpreter.close()
    }

    private companion object {
        const val MODEL_ASSET = "facenet.tflite"
        const val CHANNELS = 3
        const val NUM_THREADS = 4
    }
}

/** Vector helpers shared by the tracker and the clusterer. */
object Embeddings {

    /** Returns a unit-length copy of [vector]. */
    fun normalise(vector: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in vector) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm <= 1e-6f) return vector.copyOf()
        return FloatArray(vector.size) { vector[it] / norm }
    }

    /** Cosine similarity of two unit-length vectors, in -1..1. */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding sizes differ: ${a.size} vs ${b.size}" }
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    /** Element-wise mean of [vectors], renormalised to unit length. */
    fun mean(vectors: List<FloatArray>): FloatArray {
        require(vectors.isNotEmpty()) { "Cannot average an empty list of embeddings" }
        val acc = FloatArray(vectors[0].size)
        for (v in vectors) {
            for (i in acc.indices) acc[i] += v[i]
        }
        for (i in acc.indices) acc[i] /= vectors.size
        return normalise(acc)
    }
}
