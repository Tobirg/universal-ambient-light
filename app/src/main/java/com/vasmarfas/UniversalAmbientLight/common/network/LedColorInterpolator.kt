package com.vasmarfas.UniversalAmbientLight.common.network

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Дробное состояние LED между входными кадрами. Время передаётся вызывающим кодом,
 * чтобы переходы не зависели от системных часов и проверялись без Android-таймера.
 * RGB/YUV и инерция используют подходы InfiniteSmoothing HyperHDR.
 */
internal class LedColorInterpolator(
    algorithm: String = "linear",
    private val mLuminanceLimit: Float = 0f,
) {
    private val mAlgorithm = algorithm.takeIf { it in setOf("linear", "exponential", "yuv", "hybrid") } ?: "linear"
    private var mCurrent = FloatArray(0)
    private var mStart = FloatArray(0)
    private var mTarget = FloatArray(0)
    private var mRgbTarget = FloatArray(0)
    private var mVelocity = FloatArray(0)
    private var mStartTime = 0L
    private var mLastTime = 0L
    private var mDuration = 0
    var settled = true
        private set

    fun clear() {
        mCurrent = FloatArray(0)
        mStart = FloatArray(0)
        mTarget = FloatArray(0)
        mRgbTarget = FloatArray(0)
        mVelocity = FloatArray(0)
        settled = true
    }

    fun setTarget(colors: Array<ColorRgb>, now: Long, durationMs: Int): Boolean {
        val same = mRgbTarget.size == colors.size * 3 && colors.indices.all { i ->
            val offset = i * 3
            mRgbTarget[offset] == colors[i].red.toFloat() &&
                mRgbTarget[offset + 1] == colors[i].green.toFloat() &&
                mRgbTarget[offset + 2] == colors[i].blue.toFloat()
        }
        if (same) {
            if (durationMs == 0 && !settled) {
                mTarget.copyInto(mCurrent)
                mVelocity.fill(0f)
                settled = true
                return true
            }
            return false
        }
        advance(now)
        val resized = mCurrent.size != colors.size * 3
        if (resized) {
            mCurrent = FloatArray(colors.size * 3)
            mStart = FloatArray(mCurrent.size)
            mTarget = FloatArray(mCurrent.size)
            mRgbTarget = FloatArray(mCurrent.size)
            mVelocity = FloatArray(mCurrent.size)
        }
        for (i in colors.indices) {
            val offset = i * 3
            mRgbTarget[offset] = colors[i].red.toFloat()
            mRgbTarget[offset + 1] = colors[i].green.toFloat()
            mRgbTarget[offset + 2] = colors[i].blue.toFloat()
        }
        mRgbTarget.copyInto(mTarget)
        if (usesYuv()) toYuv(mTarget)
        if (resized) mTarget.copyInto(mCurrent)
        mCurrent.copyInto(mStart)
        mStartTime = now
        mLastTime = now
        mDuration = durationMs.coerceAtLeast(0)
        settled = resized || mDuration == 0 || (mCurrent.contentEquals(mTarget) &&
            (mAlgorithm != "hybrid" || mVelocity.all { abs(it) < 0.01f }))
        if (settled) {
            mTarget.copyInto(mCurrent)
            mVelocity.fill(0f)
        }
        return true
    }

    fun snapshot(now: Long): FloatArray {
        advance(now)
        if (settled) return mRgbTarget.copyOf()
        return mCurrent.copyOf().also { values ->
            if (usesYuv()) toRgb(values)
            for (i in values.indices) values[i] = values[i].coerceIn(0f, 255f)
        }
    }

    private fun advance(now: Long) {
        if (settled) return
        val elapsed = (now - mStartTime).coerceAtLeast(0)
        val dt = (now - mLastTime).coerceAtLeast(0)
        mLastTime = now
        val previous = if (usesYuv() && mLuminanceLimit > 0f) mCurrent.copyOf() else null
        when (mAlgorithm) {
            "exponential" -> {
                val factor = (1.0 - exp(-dt.toDouble() / mDuration.coerceAtLeast(1))).toFloat()
                for (i in mCurrent.indices) mCurrent[i] += factor * (mTarget[i] - mCurrent[i])
                settled = mCurrent.indices.all { abs(mTarget[it] - mCurrent[it]) < 0.01f }
            }
            "hybrid" -> {
                // Малые подшаги сохраняют устойчивость пружины после задержки USB-записи.
                var remaining = min(dt, 100L) / 1000f
                val scale = 200f / mDuration.coerceAtLeast(1)
                while (remaining > 0f) {
                    val step = min(remaining, 0.005f / scale.coerceAtLeast(1f))
                    for (i in mCurrent.indices) {
                        val acceleration = 200f * scale * scale * (mTarget[i] - mCurrent[i]) -
                            26f * scale * mVelocity[i]
                        mVelocity[i] += acceleration * step
                        mCurrent[i] += mVelocity[i] * step
                        val minimum = if (i % 3 == 0) 0f else -127.5f
                        val maximum = if (i % 3 == 0) 255f else 127.5f
                        if (mCurrent[i] < minimum || mCurrent[i] > maximum) {
                            mCurrent[i] = mCurrent[i].coerceIn(minimum, maximum)
                            mVelocity[i] = 0f
                        }
                    }
                    remaining -= step
                }
                settled = mCurrent.indices.all {
                    abs(mTarget[it] - mCurrent[it]) < 0.01f && abs(mVelocity[it]) < 0.01f
                }
            }
            else -> {
                val factor = (elapsed.toFloat() / mDuration.coerceAtLeast(1)).coerceIn(0f, 1f)
                for (i in mCurrent.indices) mCurrent[i] = mStart[i] + factor * (mTarget[i] - mStart[i])
                settled = factor >= 1f
            }
        }
        if (previous != null) {
            val allowed = mLuminanceLimit * dt / 1000f
            for (i in mCurrent.indices step 3) {
                val change = abs(mCurrent[i] - previous[i])
                if (change > allowed) {
                    val factor = allowed / change
                    for (channel in i..i + 2) {
                        mCurrent[channel] = previous[channel] + (mCurrent[channel] - previous[channel]) * factor
                        mVelocity[channel] *= factor
                    }
                    settled = false
                }
            }
        }
        if (settled) {
            mTarget.copyInto(mCurrent)
            mVelocity.fill(0f)
        }
    }

    private fun usesYuv(): Boolean = mAlgorithm == "yuv" || mAlgorithm == "hybrid"

    private fun toYuv(values: FloatArray) {
        for (i in values.indices step 3) {
            val r = values[i]
            val g = values[i + 1]
            val b = values[i + 2]
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            values[i] = y
            values[i + 1] = (b - y) / 1.8556f
            values[i + 2] = (r - y) / 1.5748f
        }
    }

    private fun toRgb(values: FloatArray) {
        for (i in values.indices step 3) {
            val y = values[i]
            val u = values[i + 1]
            val v = values[i + 2]
            values[i] = y + 1.5748f * v
            values[i + 1] = y - 0.187324f * u - 0.468124f * v
            values[i + 2] = y + 1.8556f * u
        }
    }
}
