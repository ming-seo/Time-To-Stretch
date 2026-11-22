package com.example.tts.neck

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mode C geometry (design doc §11.3). Pure logic, no Android dependencies.
 * Matrices are row-major DoubleArray(9), vectors DoubleArray(3).
 * Camera axes: x image right, y down, z forward. Head axes: the camera axes at the front calibration step.
 */
object ModeCGeometry {

    /** Device axes (x right, y up, z out of the screen) to front camera axes; its own inverse. */
    val P = doubleArrayOf(-1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 1.0)

    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c]
    }

    fun mulVec(a: DoubleArray, v: DoubleArray) = DoubleArray(3) { r ->
        a[3 * r] * v[0] + a[3 * r + 1] * v[1] + a[3 * r + 2] * v[2]
    }

    fun transpose(a: DoubleArray) = DoubleArray(9) { i -> a[3 * (i % 3) + i / 3] }

    fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    /** Rotation matrix of the rotation vector [w] (axis × angle in radians). */
    fun rodrigues(w: DoubleArray): DoubleArray {
        val th = norm(w)
        if (th < 1e-12) return doubleArrayOf(1.0, -w[2], w[1], w[2], 1.0, -w[0], -w[1], w[0], 1.0)
        val x = w[0] / th
        val y = w[1] / th
        val z = w[2] / th
        val c = cos(th)
        val s = sin(th)
        val k = 1 - c
        return doubleArrayOf(
            c + x * x * k, x * y * k - z * s, x * z * k + y * s,
            y * x * k + z * s, c + y * y * k, y * z * k - x * s,
            z * x * k - y * s, z * y * k + x * s, c + z * z * k,
        )
    }

    /**
     * Head→camera rotation of a calibration step: R_v = P·M_vᵀ·M_0·P, from the device→world rotation matrices
     * of the front step ([front], M_0) and this step ([step], M_v) (SensorManager.getRotationMatrixFromVector).
     */
    fun headToCamera(front: DoubleArray, step: DoubleArray): DoubleArray = mul(mul(P, mul(transpose(step), front)), P)

    /** (pitch_C, yaw_C) in degrees of head→camera rotation [r]: pitch + = face looks above the camera, yaw − = phone on the user's left. */
    fun faceAngles(r: DoubleArray): Pair<Double, Double> =
        Math.toDegrees(atan2(r[5], r[8])) to Math.toDegrees(atan2(-r[2], r[8]))

    /** Head flexion α (°) relative to gravity: up vector u = Rᵀ·P·ĝ in head axes, α = atan2(u_z, −u_y). [gx]..[gz]: TYPE_GRAVITY. */
    fun headFlexion(r: DoubleArray, gx: Float, gy: Float, gz: Float): Double {
        val u = mulVec(transpose(r), doubleArrayOf(-gx.toDouble(), -gy.toDouble(), gz.toDouble()))
        return Math.toDegrees(atan2(u[2], -u[1]))
    }

    /** Pixel position of head-frame point [x] under pose ([r], [t]) for focal length [f] and principal point ([cx], [cy]). */
    fun project(r: DoubleArray, t: DoubleArray, x: DoubleArray, f: Double, cx: Double, cy: Double): DoubleArray {
        val c = mulVec(r, x)
        val z = c[2] + t[2]
        return doubleArrayOf(f * (c[0] + t[0]) / z + cx, f * (c[1] + t[1]) / z + cy)
    }

    /** Element-wise mean of rotation matrices, re-orthonormalized (Gram–Schmidt on the rows). */
    fun meanRotation(rotations: List<DoubleArray>): DoubleArray {
        val m = DoubleArray(9) { i -> rotations.sumOf { it[i] } / rotations.size }
        val r0 = unit(doubleArrayOf(m[0], m[1], m[2]))
        val r1raw = doubleArrayOf(m[3], m[4], m[5])
        val d = r0[0] * r1raw[0] + r0[1] * r1raw[1] + r0[2] * r1raw[2]
        val r1 = unit(DoubleArray(3) { r1raw[it] - d * r0[it] })
        val r2 = doubleArrayOf(r0[1] * r1[2] - r0[2] * r1[1], r0[2] * r1[0] - r0[0] * r1[2], r0[0] * r1[1] - r0[1] * r1[0])
        return r0 + r1 + r2
    }

    private fun unit(v: DoubleArray): DoubleArray {
        val n = norm(v)
        return DoubleArray(3) { v[it] / n }
    }
}

/** Levenberg–Marquardt least squares with a forward-difference Jacobian. Pure logic. */
object LevenbergMarquardt {

    class Result(val params: DoubleArray, val cost: Double)

    /** Minimizes the sum of squares of [residuals] starting from [initial]. */
    fun minimize(initial: DoubleArray, maxIterations: Int = 100, residuals: (DoubleArray) -> DoubleArray): Result {
        val n = initial.size
        var p = initial.copyOf()
        var r = residuals(p)
        var cost = sumSq(r)
        var lambda = 1e-3
        for (iteration in 0 until maxIterations) {
            val jac = Array(n) { j ->
                val h = 1e-6 * max(1.0, abs(p[j]))
                val q = p.copyOf().also { it[j] += h }
                val rq = residuals(q)
                DoubleArray(r.size) { i -> (rq[i] - r[i]) / h }
            }
            val a = Array(n) { j -> DoubleArray(n) { k -> dot(jac[j], jac[k]) } }
            val g = DoubleArray(n) { j -> -dot(jac[j], r) }
            var improved = false
            while (!improved && lambda < 1e10) {
                val damped = Array(n) { j -> a[j].copyOf().also { it[j] += lambda * max(a[j][j], 1e-9) } }
                val delta = solve(damped, g)
                if (delta == null) {
                    lambda *= 10
                    continue
                }
                val pNew = DoubleArray(n) { p[it] + delta[it] }
                val rNew = residuals(pNew)
                val costNew = sumSq(rNew)
                if (costNew.isFinite() && costNew < cost) {
                    val converged = cost - costNew < 1e-12 * (1 + cost) || delta.maxOf { abs(it) } < 1e-10
                    p = pNew
                    r = rNew
                    cost = costNew
                    lambda = max(lambda / 10, 1e-12)
                    improved = true
                    if (converged) return Result(p, cost)
                } else {
                    lambda *= 10
                }
            }
            if (!improved) break
        }
        return Result(p, cost)
    }

    private fun sumSq(r: DoubleArray) = r.sumOf { it * it }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val x = b.copyOf()
        for (col in 0 until n) {
            val piv = (col until n).maxBy { abs(m[it][col]) }
            if (abs(m[piv][col]) < 1e-300) return null
            m[col] = m[piv].also { m[piv] = m[col] }
            x[col] = x[piv].also { x[piv] = x[col] }
            for (row in col + 1 until n) {
                val factor = m[row][col] / m[col][col]
                if (factor == 0.0) continue
                for (k in col until n) m[row][k] -= factor * m[col][k]
                x[row] -= factor * x[col]
            }
        }
        for (row in n - 1 downTo 0) {
            var s = x[row]
            for (k in row + 1 until n) s -= m[row][k] * x[k]
            x[row] = s / m[row][row]
        }
        return x
    }
}
