package org.resilane.sim.render

import android.opengl.GLES30
import com.google.ar.core.LightEstimate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Real-world lighting for the cars, from ARCore's Environmental HDR estimate: a main directional
 * light, 2nd-order spherical-harmonic ambient, and an HDR reflection cubemap. A car lit by the
 * room it appears in reads as part of the scene (to people and to YOLO); a car lit by a fixed
 * studio light looks pasted on.
 *
 * Until the first valid estimate (or if HDR is unsupported) a procedural sky/ground cubemap and a
 * soft overhead light are used, so paint still gets a horizon reflection.
 */
class Environment {
    val lightDir = floatArrayOf(0.3f, 0.9f, 0.2f)
    val lightColor = floatArrayOf(1.6f, 1.55f, 1.5f)
    /** 9 premultiplied RGB SH coefficients, packed. */
    val sh = FloatArray(27)
    var cubemap = 0; private set
    var maxLod = 4f; private set
    var hdrValid = false; private set
    private var lastTimestamp = -1L
    private var lastCubeMs = 0L

    fun init() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        cubemap = ids[0]
        setDefaultSh()
        uploadCube(16) { dx, dy, dz -> skyColor(dx, dy, dz) }
    }

    fun update(le: LightEstimate) {
        if (le.state != LightEstimate.State.VALID) return
        try {
            updateHdr(le)
        } catch (e: IllegalStateException) {
            // Not in ENVIRONMENTAL_HDR mode: scale the default rig by the ambient pixel intensity.
            val k = (le.pixelIntensity / 0.5f).coerceIn(0.2f, 2.5f)
            setDefaultSh()
            for (i in sh.indices) sh[i] *= k
            for (c in 0..2) lightColor[c] = 1.5f * k
        } catch (e: Exception) {
            // NotYetAvailable etc.: keep the previous estimate.
        }
    }

    private fun updateHdr(le: LightEstimate) {
        if (le.timestamp == lastTimestamp) return
        lastTimestamp = le.timestamp
        val d = le.environmentalHdrMainLightDirection
        val i = le.environmentalHdrMainLightIntensity
        // Convention guard: treat it as the direction toward the light; real main lights are above.
        val s = if (d[1] < -0.2f) -1f else 1f
        lightDir[0] = d[0] * s; lightDir[1] = d[1] * s; lightDir[2] = d[2] * s
        for (k in 0..2) lightColor[k] = i[k].coerceIn(0f, 8f)
        val c = le.environmentalHdrAmbientSphericalHarmonics
        for (k in 0 until 9) for (ch in 0..2) sh[k * 3 + ch] = c[k * 3 + ch] * SH_FACTORS[k]
        val now = System.currentTimeMillis()
        if (now - lastCubeMs < 500) return
        lastCubeMs = now
        val faces = le.acquireEnvironmentalHdrCubeMap()
        try {
            val size = faces[0].width
            val data = Array(6) { f ->
                val plane = faces[f].planes[0]
                val buf = plane.buffer.order(ByteOrder.nativeOrder())
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride.takeIf { it > 0 } ?: 8
                FloatArray(size * size * 3) { idx ->
                    val px = idx / 3; val ch = idx % 3
                    val x = px % size; val y = px / size
                    halfToFloat(buf.getShort(y * rowStride + x * pixelStride + ch * 2))
                }
            }
            uploadFaces(size, data)
            hdrValid = true
        } finally {
            faces.forEach { it.close() }
        }
    }

    private fun setDefaultSh() {
        sh.fill(0f)
        // Flat-ish ambient, a little brighter from above.
        for (ch in 0..2) { sh[ch] = 0.55f; sh[3 + ch] = 0.12f }
    }

    private fun skyColor(dx: Float, dy: Float, dz: Float): FloatArray = when {
        dy > 0f -> { val t = dy; floatArrayOf(0.85f - 0.45f * t, 0.9f - 0.35f * t, 1.0f - 0.2f * t) }
        else -> { val t = (-dy).coerceAtMost(1f); floatArrayOf(0.35f - 0.2f * t, 0.33f - 0.19f * t, 0.31f - 0.18f * t) }
    }

    private fun uploadCube(size: Int, f: (Float, Float, Float) -> FloatArray) {
        val data = Array(6) { face ->
            FloatArray(size * size * 3).also { out ->
                for (y in 0 until size) for (x in 0 until size) {
                    val u = (x + 0.5f) / size * 2 - 1; val v = (y + 0.5f) / size * 2 - 1
                    val dir = when (face) {
                        0 -> floatArrayOf(1f, -v, -u); 1 -> floatArrayOf(-1f, -v, u)
                        2 -> floatArrayOf(u, 1f, v); 3 -> floatArrayOf(u, -1f, -v)
                        4 -> floatArrayOf(u, -v, 1f); else -> floatArrayOf(-u, -v, -1f)
                    }
                    val l = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
                    val c = f(dir[0] / l, dir[1] / l, dir[2] / l)
                    System.arraycopy(c, 0, out, (y * size + x) * 3, 3)
                }
            }
        }
        uploadFaces(size, data)
    }

    /** Uploads 6 RGB float faces with a CPU-built box-filtered mip chain (rough = blurrier). */
    private fun uploadFaces(size0: Int, faces: Array<FloatArray>) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, cubemap)
        var size = size0
        var level = 0
        var cur = faces
        while (true) {
            for (f in 0 until 6) {
                val bb = ByteBuffer.allocateDirect(cur[f].size * 4).order(ByteOrder.nativeOrder())
                bb.asFloatBuffer().put(cur[f])
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X + f, level, GLES30.GL_RGB16F, size, size, 0,
                    GLES30.GL_RGB, GLES30.GL_FLOAT, bb)
            }
            if (size == 1) break
            val ns = max(1, size / 2)
            val s = size
            cur = Array(6) { f ->
                FloatArray(ns * ns * 3) { idx ->
                    val p = idx / 3; val ch = idx % 3
                    val x = (p % ns) * 2; val y = (p / ns) * 2
                    val src = cur[f]
                    (src[(y * s + x) * 3 + ch] + src[(y * s + minOf(x + 1, s - 1)) * 3 + ch] +
                        src[(minOf(y + 1, s - 1) * s + x) * 3 + ch] + src[(minOf(y + 1, s - 1) * s + minOf(x + 1, s - 1)) * 3 + ch]) / 4f
                }
            }
            size = ns; level++
        }
        maxLod = level.toFloat()
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        Gl.check("cubemap")
    }

    companion object {
        /** ARCore's SH basis normalisation (same constants as its HDR sample). */
        private val SH_FACTORS = floatArrayOf(0.282095f, -0.325735f, 0.325735f, -0.325735f, 0.273137f, -0.273137f, 0.078848f, -0.273137f, 0.136569f)

        fun halfToFloat(h: Short): Float {
            val bits = h.toInt() and 0xFFFF
            val sign = bits ushr 15
            val exp = (bits ushr 10) and 0x1F
            val mant = bits and 0x3FF
            val v = when (exp) {
                0 -> mant / 1024f * (1f / 16384f)
                31 -> if (mant == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1f + mant / 1024f) * Math.pow(2.0, (exp - 15).toDouble()).toFloat()
            }
            return (if (sign == 1) -v else v).let { if (it.isFinite()) it else 0f }
        }
    }
}
