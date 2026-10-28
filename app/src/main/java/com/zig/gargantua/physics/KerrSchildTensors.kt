package com.zig.gargantua.physics

import kotlin.math.abs

/**
 * 4-dimensional spacetime tensors and null vectors for Kerr-Schild calculations.
 *
 * METRIC SIGNATURE & CONVENTIONS:
 * - Signature: (- + + +) (mostly positive, standard in general relativity and Kerr-Schild literature).
 * - Coordinates: x^μ = (x^0, x^1, x^2, x^3) = (T, X, Y, Z).
 * - Flat background: η_μν = diag(-1, 1, 1, 1) and η^μν = diag(-1, 1, 1, 1).
 */
data class MetricTensor4(
    val elements: DoubleArray
) {
    init {
        require(elements.size == 16) {
            "MetricTensor4 requires exactly 16 elements, got ${elements.size}"
        }
    }

    /** Accesses tensor component at row μ and column ν (0=T, 1=X, 2=Y, 3=Z). */
    operator fun get(mu: Int, nu: Int): Double = elements[mu * 4 + nu]

    /** Checks if the tensor is symmetric within the specified absolute tolerance. */
    fun isSymmetric(tolerance: Double = 1e-12): Boolean {
        for (i in 0 until 4) {
            for (j in i + 1 until 4) {
                if (abs(this[i, j] - this[j, i]) > tolerance) return false
            }
        }
        return true
    }

    /**
     * Matrix multiplication C^μ_ν = A^μ_α B^α_ν.
     * When multiplying contravariant g^μα by covariant g_αν, the result should equal the Kronecker delta δ^μ_ν.
     */
    operator fun times(other: MetricTensor4): MetricTensor4 {
        val res = DoubleArray(16)
        for (i in 0 until 4) {
            val iOffset = i * 4
            for (j in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += elements[iOffset + k] * other.elements[k * 4 + j]
                }
                res[iOffset + j] = sum
            }
        }
        return MetricTensor4(res)
    }

    /**
     * Evaluates the maximum absolute residual from the identity tensor δ^μ_ν:
     *   max_{μ, ν} | M[μ, ν] - δ^μ_ν |
     */
    fun maxResidualFromIdentity(): Double {
        var maxErr = 0.0
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                val expected = if (i == j) 1.0 else 0.0
                val err = abs(this[i, j] - expected)
                if (err > maxErr) maxErr = err
            }
        }
        return maxErr
    }

    /**
     * Contracts the tensor with two 4-vectors: T_μν u^μ v^ν or T^μν u_μ v_ν.
     */
    fun contract(u: DoubleArray, v: DoubleArray): Double {
        require(u.size == 4 && v.size == 4) { "Vectors for contraction must have size 4" }
        var sum = 0.0
        for (i in 0 until 4) {
            val iOffset = i * 4
            val ui = u[i]
            for (j in 0 until 4) {
                sum += elements[iOffset + j] * ui * v[j]
            }
        }
        return sum
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MetricTensor4) return false
        return elements.contentEquals(other.elements)
    }

    override fun hashCode(): Int = elements.contentHashCode()

    companion object {
        /** Minkowski background metric η_μν in signature (- + + +). */
        val MINKOWSKI_LOWER = MetricTensor4(
            doubleArrayOf(
                -1.0, 0.0, 0.0, 0.0,
                 0.0, 1.0, 0.0, 0.0,
                 0.0, 0.0, 1.0, 0.0,
                 0.0, 0.0, 0.0, 1.0
            )
        )

        /** Minkowski inverse metric η^μν in signature (- + + +). */
        val MINKOWSKI_UPPER = MetricTensor4(
            doubleArrayOf(
                -1.0, 0.0, 0.0, 0.0,
                 0.0, 1.0, 0.0, 0.0,
                 0.0, 0.0, 1.0, 0.0,
                 0.0, 0.0, 0.0, 1.0
            )
        )
    }
}

/**
 * Kerr-Schild null 1-form l_μ and null vector l^μ.
 *
 * For Kerr-Schild spacetime:
 *   l_μ = ( 1, (r X + a Y)/(r² + a²), (r Y - a X)/(r² + a²), Z / r )
 *
 * Contravariant vector l^μ = η^μν l_ν:
 *   l^0 = η^00 l_0 = -1
 *   l^1 = η^11 l_1 = l_X
 *   l^2 = η^22 l_2 = l_Y
 *   l^3 = η^33 l_3 = l_Z
 *
 * Notice:
 *   η^μν l_μ l_ν = -(1)² + l_X² + l_Y² + l_Z²
 *   By construction of the Kerr-Schild radial coordinate r,
 *   l_X² + l_Y² + l_Z² = (X² + Y²)/(r² + a²) + Z²/r² = 1.
 *   Therefore η^μν l_μ l_ν = -1 + 1 = 0 identically.
 */
data class NullVector(
    val l_t: Double = 1.0,
    val l_x: Double,
    val l_y: Double,
    val l_z: Double
) {
    /** Returns the covariant components l_μ = (l_t, l_x, l_y, l_z). */
    fun toCovariantArray(): DoubleArray = doubleArrayOf(l_t, l_x, l_y, l_z)

    /** Returns the contravariant components l^μ = (-l_t, l_x, l_y, l_z). */
    fun toContravariantArray(): DoubleArray = doubleArrayOf(-l_t, l_x, l_y, l_z)

    /**
     * Minkowski norm squared: η^μν l_μ l_ν = -(l_t)² + l_x² + l_y² + l_z².
     * Must equal 0 within machine precision.
     */
    fun minkowskiNorm(): Double = -(l_t * l_t) + (l_x * l_x) + (l_y * l_y) + (l_z * l_z)
}
