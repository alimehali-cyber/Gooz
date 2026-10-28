package com.zig.gargantua.renderer

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure Phase 2 animation helpers. The renderer remains responsible for all GL resource ownership;
 * these values are intentionally deterministic and not persisted.
 */
object GargantuaAnimation {
    const val NOISE_WIDTH = 512
    const val NOISE_HEIGHT = 256
    const val NOISE_BYTES = NOISE_WIDTH * NOISE_HEIGHT
    const val TICK_INTERVAL_MS = 34L
    const val CAMERA_SETTLE_MS = 300L
    const val FLOW_MAP_PERIOD_SECONDS = 24.0
    const val TIME_DIGIT_BASE = 16.0
    const val TIME_DIGIT_COUNT = 8
    const val NOISE_OCTAVES = 3

    data class NoiseOctave(val latticeWidth: Int, val latticeHeight: Int, val weight: Float)

    val NOISE_OCTAVE_SPECS: List<NoiseOctave> = listOf(
        NoiseOctave(32, 8, 0.50f),
        NoiseOctave(64, 16, 0.35f),
        NoiseOctave(128, 32, 0.15f)
    )

    enum class AnimationSpeed(
        val periodSeconds: Double,
        val englishLabel: String,
        val persianLabel: String
    ) {
        SLOW(12.0, "Slow", "آهسته"),
        NORMAL(6.0, "Normal", "عادی"),
        FAST(3.5, "Fast", "سریع")
    }

    data class FlowMapTimes(
        val timeA: Double,
        val timeB: Double,
        val blendA: Double
    ) {
        val tA: Double get() = timeA
        val tB: Double get() = timeB
        val blendWeight: Double get() = blendA
        val blendB: Double get() = 1.0 - blendA
    }

    val AMPLITUDE_STEPS: List<Pair<Boolean, Int>> = listOf(
        false to 0,
        true to 15,
        true to 40,
        true to 80
    )

    fun nextMode(enabled: Boolean, amplitudePercent: Int): Pair<Boolean, Int> {
        if (!enabled) return AMPLITUDE_STEPS[1]
        val current = AMPLITUDE_STEPS.indexOf(true to amplitudePercent)
        return AMPLITUDE_STEPS[if (current >= 0) (current + 1) % AMPLITUDE_STEPS.size else 1]
    }

    fun label(enabled: Boolean, amplitudePercent: Int): String = when {
        !enabled -> "ANIM OFF"
        else -> "ANIM ±${amplitudePercent.coerceIn(15, 80)}%"
    }

    fun flowMapTimes(seconds: Double): FlowMapTimes {
        val periodSeconds = FLOW_MAP_PERIOD_SECONDS
        val tA = positiveModulo(seconds, periodSeconds)
        val tB = positiveModulo(tA + periodSeconds * 0.5, periodSeconds)
        val blendA = 1.0 - abs(2.0 * tA / periodSeconds - 1.0)
        return FlowMapTimes(tA, tB, blendA.coerceIn(0.0, 1.0))
    }

    private fun positiveModulo(value: Double, modulus: Double): Double {
        val result = value % modulus
        return if (result < 0.0) result + modulus else result
    }

    /**
     * Generates deterministic smooth periodic value noise. Each octave wraps at both lattice
     * boundaries through samplePeriodic's explicit modulo interpolation. Contrast stretching is
     * performed before quantization so the byte tile contains 0 and 255.
     */
    fun deterministicNoise(seed: Int = 0x5EED1234): ByteArray {
        val values = FloatArray(NOISE_BYTES)
        NOISE_OCTAVE_SPECS.forEachIndexed { octave, spec ->
            val latticeWidth = spec.latticeWidth
            val latticeHeight = spec.latticeHeight
            val weight = spec.weight
            val lattice = latticeValues(latticeWidth, latticeHeight, seed + octave * 0x45D9F3B)
            for (y in 0 until NOISE_HEIGHT) {
                for (x in 0 until NOISE_WIDTH) {
                    values[y * NOISE_WIDTH + x] += weight * samplePeriodic(
                        lattice,
                        latticeWidth,
                        latticeHeight,
                        x.toFloat() * latticeWidth / NOISE_WIDTH.toFloat(),
                        y.toFloat() * latticeHeight / NOISE_HEIGHT.toFloat()
                    )
                }
            }
        }
        var minValue = values.minOrNull() ?: 0.0f
        var maxValue = values.maxOrNull() ?: 1.0f
        if (maxValue <= minValue) maxValue = minValue + 1.0f
        val result = ByteArray(NOISE_BYTES)
        for (i in values.indices) {
            val stretched = ((values[i] - minValue) / (maxValue - minValue) * 255.0f)
                .coerceIn(0.0f, 255.0f)
            result[i] = stretched.roundToInt().coerceIn(0, 255).toByte()
        }
        return result
    }

    /**
     * Base-16 time digits. Digit zero is the bounded fractional/low-order part passed as u_Time;
     * the remaining digits preserve continuity without asking one fp32 value to represent hours.
     */
    fun timeDigits(seconds: Double): FloatArray {
        var remaining = seconds.coerceAtLeast(0.0)
        val digits = FloatArray(TIME_DIGIT_COUNT)
        for (index in digits.indices) {
            digits[index] = (remaining % TIME_DIGIT_BASE).toFloat().coerceAtMost(15.999999f)
            remaining = kotlin.math.floor(remaining / TIME_DIGIT_BASE)
        }
        return digits
    }

    /**
     * Zero-allocation variant of timeDigits: writes base-16 digits directly into a pre-allocated array.
     */
    fun fillTimeDigits(seconds: Double, outDigits: FloatArray) {
        require(outDigits.size >= TIME_DIGIT_COUNT) { "outDigits must have size >= $TIME_DIGIT_COUNT" }
        var remaining = seconds.coerceAtLeast(0.0)
        for (index in 0 until TIME_DIGIT_COUNT) {
            outDigits[index] = (remaining % TIME_DIGIT_BASE).toFloat().coerceAtMost(15.999999f)
            remaining = kotlin.math.floor(remaining / TIME_DIGIT_BASE)
        }
    }

    fun normalizedMean(bytes: ByteArray): Float {
        require(bytes.isNotEmpty())
        var sum = 0L
        bytes.forEach { sum += it.toInt() and 0xFF }
        return sum.toFloat() / (bytes.size.toFloat() * 255.0f)
    }

    private fun latticeValues(width: Int, height: Int, seed: Int): FloatArray {
        val lattice = FloatArray(width * height)
        var state = seed
        for (i in lattice.indices) {
            state = xorshift(state)
            lattice[i] = ((state ushr 8) and 0x00FFFFFF) / 16777215.0f
        }
        return lattice
    }

    private fun samplePeriodic(
        lattice: FloatArray,
        latticeWidth: Int,
        latticeHeight: Int,
        x: Float,
        y: Float
    ): Float {
        val xFloor = kotlin.math.floor(x).toInt()
        val yFloor = kotlin.math.floor(y).toInt()
        val x0 = ((xFloor % latticeWidth) + latticeWidth) % latticeWidth
        val y0 = ((yFloor % latticeHeight) + latticeHeight) % latticeHeight
        val x1 = (x0 + 1) % latticeWidth
        val y1 = (y0 + 1) % latticeHeight
        val tx = smoothstep(x - xFloor.toFloat())
        val ty = smoothstep(y - yFloor.toFloat())
        val top = lattice[y0 * latticeWidth + x0] * (1.0f - tx) +
            lattice[y0 * latticeWidth + x1] * tx
        val bottom = lattice[y1 * latticeWidth + x0] * (1.0f - tx) +
            lattice[y1 * latticeWidth + x1] * tx
        return top * (1.0f - ty) + bottom * ty
    }

    private fun smoothstep(value: Float): Float {
        val t = value.coerceIn(0.0f, 1.0f)
        return t * t * (3.0f - 2.0f * t)
    }

    private fun xorshift(value: Int): Int {
        var x = value
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        return x
    }
}
