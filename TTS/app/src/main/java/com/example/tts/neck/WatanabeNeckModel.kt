package com.example.tts.neck

import android.content.Context
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Watanabe & Nakamura (OzCHI 2025) neck angle regressor: StandardScaler + Extra Trees (100 trees).
 * Inputs: ratio = (nose–neck / eye distance) / Norm_ref, phone tilt (0° vertical), face pitch (+ = up).
 * Output: neck angle in degrees (tragus–acromion line vs vertical).
 * Binary format: see tools/convert_watanabe_model.py. The asset is not in git (license unspecified).
 */
class WatanabeNeckModel private constructor(buf: ByteBuffer) {

    private val mean = DoubleArray(3)
    private val scale = DoubleArray(3)
    private val roots: IntArray
    private val feature: ByteArray
    private val value: FloatArray
    private val trueIdx: IntArray
    private val falseIdx: IntArray

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        require(String(magic, Charsets.US_ASCII) == "WNM1") { "Unknown model format" }
        for (i in 0 until 3) mean[i] = buf.getDouble()
        for (i in 0 until 3) scale[i] = buf.getDouble()
        val nTrees = buf.getInt()
        val nNodes = buf.getInt()
        roots = IntArray(nTrees).also { buf.asIntBuffer().get(it); buf.position(buf.position() + 4 * nTrees) }
        feature = ByteArray(nNodes).also { buf.get(it) }
        value = FloatArray(nNodes).also { buf.asFloatBuffer().get(it); buf.position(buf.position() + 4 * nNodes) }
        trueIdx = IntArray(nNodes).also { buf.asIntBuffer().get(it); buf.position(buf.position() + 4 * nNodes) }
        falseIdx = IntArray(nNodes).also { buf.asIntBuffer().get(it); buf.position(buf.position() + 4 * nNodes) }
    }

    fun predict(ratio: Double, phoneTilt: Double, facePitch: Double): Double {
        val x = floatArrayOf(
            ((ratio - mean[0]) / scale[0]).toFloat(),
            ((phoneTilt - mean[1]) / scale[1]).toFloat(),
            ((facePitch - mean[2]) / scale[2]).toFloat(),
        )
        var sum = 0.0
        for (root in roots) {
            var n = root
            while (feature[n] >= 0) {
                n = if (x[feature[n].toInt()] <= value[n]) trueIdx[n] else falseIdx[n]
            }
            sum += value[n]
        }
        return sum
    }

    companion object {
        const val ASSET_PATH = "neck/watanabe_extra_trees.bin"

        /** Returns null if the model asset has not been placed in the app (see the converter script). */
        fun load(context: Context): WatanabeNeckModel? = try {
            val bytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
            WatanabeNeckModel(ByteBuffer.wrap(bytes))
        } catch (_: FileNotFoundException) {
            null
        }
    }
}
