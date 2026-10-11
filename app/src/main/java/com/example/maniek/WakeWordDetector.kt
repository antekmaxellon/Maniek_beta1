package com.example.maniek

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
class WakeWordDetector(context: Context, classifierAsset: String = "maniek.onnx") : AutoCloseable {

    companion object {
        const val SAMPLE_RATE = 16000
        const val MEL_BINS = 32
        const val EMBEDDING_WINDOW = 76
        const val EMBEDDING_STRIDE = 8
        const val EMBEDDING_DIM = 96
        const val MIN_EMBEDDINGS = 16
    }

    private val env = OrtEnvironment.getEnvironment()
    private val mel = load(context, "melspectrogram.onnx")
    private val emb = load(context, "embedding_model.onnx")
    private val clf = load(context, classifierAsset)

    private val melIn = mel.inputNames.first()
    private val embIn = emb.inputNames.first()
    private val clfIn = clf.inputNames.first()

    private fun load(context: Context, asset: String): OrtSession {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            // CPU
            setIntraOpNumThreads(1)
        }
        return env.createSession(bytes, opts)
    }

    fun predict(pcm: ShortArray): Float {
        val audio = FloatArray(pcm.size) { pcm[it] / 32768f }

        val (melData, frames) = OnnxTensor.createTensor(env, arrayOf(audio)).use { input ->
            mel.run(mapOf(melIn to input)).use { res ->
                val out = res[0] as OnnxTensor
                val n = out.info.shape[2].toInt()
                val fb = out.floatBuffer
                val data = FloatArray(fb.remaining())
                fb.get(data)
                for (i in data.indices) data[i] = data[i] / 10f + 2f
                Pair(data, n)
            }
        }
        if (frames < EMBEDDING_WINDOW) return 0f

        val embeddings = ArrayList<FloatArray>()
        val window = FloatArray(EMBEDDING_WINDOW * MEL_BINS)
        var start = 0
        while (start + EMBEDDING_WINDOW <= frames) {
            System.arraycopy(melData, start * MEL_BINS, window, 0, window.size)
            val e = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(window),
                longArrayOf(1, EMBEDDING_WINDOW.toLong(), MEL_BINS.toLong(), 1)
            ).use { t ->
                emb.run(mapOf(embIn to t)).use { r ->
                    val fb = (r[0] as OnnxTensor).floatBuffer
                    val v = FloatArray(EMBEDDING_DIM)
                    fb.get(v)
                    v
                }
            }
            embeddings.add(e)
            start += EMBEDDING_STRIDE
        }
        if (embeddings.size < MIN_EMBEDDINGS) return 0f

        val seq = FloatArray(MIN_EMBEDDINGS * EMBEDDING_DIM)
        val last = embeddings.takeLast(MIN_EMBEDDINGS)
        for (i in 0 until MIN_EMBEDDINGS) {
            System.arraycopy(last[i], 0, seq, i * EMBEDDING_DIM, EMBEDDING_DIM)
        }
        return OnnxTensor.createTensor(
            env, FloatBuffer.wrap(seq),
            longArrayOf(1, MIN_EMBEDDINGS.toLong(), EMBEDDING_DIM.toLong())
        ).use { t ->
            clf.run(mapOf(clfIn to t)).use { r ->
                (r[0] as OnnxTensor).floatBuffer.get(0)
            }
        }
    }

    override fun close() {
        mel.close(); emb.close(); clf.close()
    }
}