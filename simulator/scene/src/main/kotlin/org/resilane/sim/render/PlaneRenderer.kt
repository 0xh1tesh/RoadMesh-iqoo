package org.resilane.sim.render

import android.opengl.GLES30
import android.opengl.Matrix
import android.os.SystemClock
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Holographic grid on the ground: 25 cm minor / 1 m major lines over each detected horizontal
 * plane, fading out toward the plane's edge, with a scan ring pulsing out from the viewer.
 * Lines are laid out in the shared world frame once calibrated, so every phone draws the same
 * grid on the same floor. Without ARCore it draws an estimated floor disc around the viewer.
 */
class PlaneRenderer {
    private var prog = 0
    private val u = HashMap<String, Int>()
    private val model = FloatArray(16)
    private var buf: FloatBuffer = alloc(64)

    fun init() {
        prog = Gl.program(VS, FS)
        for (n in listOf("u_Model", "u_ViewProj", "u_GridM", "u_Cam", "u_Time", "u_Color", "u_Strength"))
            u[n] = GLES30.glGetUniformLocation(prog, n)
    }

    /** ARCore planes. [gridFromSession] maps session coordinates to the frame the grid aligns to. */
    fun draw(planes: Collection<Plane>, viewProj: FloatArray, gridFromSession: FloatArray, camPos: FloatArray) {
        begin(viewProj, gridFromSession, camPos, 1f)
        for (p in planes) {
            if (p.trackingState != TrackingState.TRACKING || p.subsumedBy != null) continue
            if (p.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            val poly = p.polygon
            val n = poly.limit() / 2
            if (n < 3) continue
            fan(n) { i -> floatArrayOf(poly.get(2 * i), poly.get(2 * i + 1)) }
            p.centerPose.toMatrix(model, 0)
            drawFan(n)
        }
        end()
    }

    /** No plane detection (3-DoF fallback): an estimated floor disc of [radius] around the viewer. */
    fun drawEstimatedFloor(viewProj: FloatArray, camPos: FloatArray, groundY: Float, radius: Float) {
        begin(viewProj, IDENTITY, camPos, 0.75f)
        val n = 48
        fan(n) { i -> val a = i * 2 * Math.PI.toFloat() / n; floatArrayOf(radius * cos(a), radius * sin(a)) }
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, camPos[0], groundY, camPos[2])
        drawFan(n)
        end()
    }

    private fun begin(viewProj: FloatArray, gridM: FloatArray, camPos: FloatArray, strength: Float) {
        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(u["u_ViewProj"]!!, 1, false, viewProj, 0)
        GLES30.glUniformMatrix4fv(u["u_GridM"]!!, 1, false, gridM, 0)
        GLES30.glUniform3fv(u["u_Cam"]!!, 1, camPos, 0)
        GLES30.glUniform1f(u["u_Time"]!!, (SystemClock.uptimeMillis() % 1_000_000L) / 1000f)
        GLES30.glUniform3f(u["u_Color"]!!, 0.25f, 0.85f, 1.0f)
        GLES30.glUniform1f(u["u_Strength"]!!, strength)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        Gl.clientArrays()
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
    }

    private fun end() {
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Triangle fan: centre (fade 1), then the outline closed back on itself (fade 0). x, z, fade. */
    private fun fan(n: Int, pt: (Int) -> FloatArray) {
        if (buf.capacity() < (n + 2) * 3) buf = alloc((n + 2) * 3 * 2)
        buf.clear()
        buf.put(0f).put(0f).put(1f)
        for (i in 0..n) { val p = pt(i % n); buf.put(p[0]).put(p[1]).put(0f) }
        buf.position(0)
    }

    private fun drawFan(n: Int) {
        GLES30.glUniformMatrix4fv(u["u_Model"]!!, 1, false, model, 0)
        buf.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 12, buf)
        buf.position(2)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, 12, buf)
        buf.position(0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, n + 2)
    }

    private companion object {
        val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        fun alloc(floats: Int): FloatBuffer = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        const val VS = """#version 300 es
layout(location=0) in vec2 a_Xz;
layout(location=1) in float a_Fade;
uniform mat4 u_Model;
uniform mat4 u_ViewProj;
out vec3 v_W;
out float v_Fade;
void main() {
  vec4 w = u_Model * vec4(a_Xz.x, 0.0, a_Xz.y, 1.0);
  v_W = w.xyz;
  v_Fade = a_Fade;
  gl_Position = u_ViewProj * w;
}
"""
        const val FS = """#version 300 es
precision highp float;
in vec3 v_W;
in float v_Fade;
out vec4 o;
uniform mat4 u_GridM;
uniform vec3 u_Cam;
uniform float u_Time;
uniform vec3 u_Color;
uniform float u_Strength;

// Anti-aliased grid lines every 1.0 of c; fades out where cells shrink below a few pixels (moire).
float grid(vec2 c, float px) {
  vec2 fw = max(fwidth(c), vec2(1e-4));
  vec2 d = abs(fract(c - 0.5) - 0.5) / fw;
  float l = 1.0 - clamp(min(d.x, d.y) / px, 0.0, 1.0);
  return l * (1.0 - smoothstep(0.12, 0.4, max(fw.x, fw.y)));
}

void main() {
  vec2 p = (u_GridM * vec4(v_W, 1.0)).xz;
  float minor = grid(p * 4.0, 1.0);
  float major = grid(p, 1.7);
  float dist = length(v_W.xz - u_Cam.xz);
  float r = fract(u_Time * 0.35) * 7.0;
  float rd = (dist - r) * 4.0;
  float ring = exp(-rd * rd) * (1.0 - r / 7.0);
  float edge = smoothstep(0.0, 0.4, v_Fade);
  float far = mix(0.3, 1.0, 1.0 - smoothstep(3.0, 9.0, dist));
  float a = (0.035 + 0.28 * minor + 0.7 * major + ring * (0.12 + 0.6 * (minor + major))) * edge * far * u_Strength;
  o = vec4(u_Color * a, 1.0);
}
"""
    }
}
