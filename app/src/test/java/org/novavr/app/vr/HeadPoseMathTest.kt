package org.novavr.app.vr

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class HeadPoseMathTest {

    private fun headView(px: Float, py: Float, pz: Float, qx: Float, qy: Float, qz: Float, qw: Float): FloatArray {
        val out = FloatArray(16)
        HeadTracker.poseToHeadView(floatArrayOf(px, py, pz, qx, qy, qz, qw), out)
        return out
    }

    /** Column-major matrix * (x, y, z, 1). */
    private fun transform(m: FloatArray, x: Float, y: Float, z: Float): FloatArray =
        FloatArray(3) { r -> m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r] }

    @Test
    fun identityQuaternionGivesIdentityRotation() {
        val m = headView(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val expected = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        expected.forEachIndexed { i, v -> assertEquals("m[$i]", v, m[i], 1e-6f) }
    }

    @Test
    fun translationLandsInLastColumn() {
        val m = headView(0.1f, -0.2f, 0.3f, 0f, 0f, 0f, 1f)
        assertEquals(0.1f, m[12], 1e-6f)
        assertEquals(-0.2f, m[13], 1e-6f)
        assertEquals(0.3f, m[14], 1e-6f)
        assertEquals(1f, m[15], 1e-6f)
    }

    @Test
    fun yawQuaternionRotatesAroundVerticalAxis() {
        // 90 degrees about +Y.
        val half = Math.toRadians(45.0)
        val m = headView(0f, 0f, 0f, 0f, sin(half).toFloat(), 0f, cos(half).toFloat())
        // A point straight ahead (-Z) ends up on the -X side.
        val p = transform(m, 0f, 0f, -1f)
        assertEquals(-1f, p[0], 1e-5f)
        assertEquals(0f, p[1], 1e-5f)
        assertEquals(0f, p[2], 1e-5f)
        // Vertical axis is unaffected.
        val up = transform(m, 0f, 1f, 0f)
        assertEquals(1f, up[1], 1e-5f)
    }

    @Test
    fun rotationPartIsOrthonormal() {
        val n = kotlin.math.sqrt(0.1f * 0.1f + 0.7f * 0.7f + 0.2f * 0.2f + 0.68f * 0.68f)
        val m = headView(0f, 0f, 0f, 0.1f / n, 0.7f / n, 0.2f / n, 0.68f / n)
        for (a in 0..2) {
            for (b in 0..2) {
                var dot = 0f
                for (r in 0..2) dot += m[a * 4 + r] * m[b * 4 + r]
                assertEquals("col $a · col $b", if (a == b) 1f else 0f, dot, 1e-5f)
            }
        }
    }
}
