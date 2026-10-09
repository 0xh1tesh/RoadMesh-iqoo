package org.resilane.sim.render

import android.graphics.Color
import android.opengl.GLES30
import android.os.SystemClock
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Draws vehicles as a grid-line hologram: the car's own surface sliced by a model-space lattice
 * (horizontal contour lines plus fainter lengthwise / crosswise ones), a Fresnel rim that outlines
 * the silhouette, a scan band sweeping up the body, and a glowing projector pad on the ground.
 * Everything is additive light over the camera image, in the node's colour.
 *
 * A depth pre-pass keeps only the nearest skin, so the hologram reads as a clean shell rather than
 * a tangle of overlapping inner surfaces. The lattice lives in model space and moves with the car.
 */
class HologramRenderer {
    private var prog = 0
    private var padProg = 0
    private val u = HashMap<String, Int>()
    private val pu = HashMap<String, Int>()
    private val quad: FloatBuffer = Gl.floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))

    fun init() {
        prog = Gl.program(VS, FS)
        for (n in listOf("u_Model", "u_ViewProj", "u_NormalMat", "u_CamPos", "u_Tint", "u_Alpha", "u_Time",
            "u_Spacing", "u_Height", "u_Glass")) u[n] = GLES30.glGetUniformLocation(prog, n)
        padProg = Gl.program(PAD_VS, PAD_FS)
        for (n in listOf("u_Model", "u_ViewProj", "u_Half", "u_Tint", "u_Alpha", "u_Time", "u_Spacing"))
            pu[n] = GLES30.glGetUniformLocation(padProg, n)
    }

    fun draw(model: GlbModel, cars: List<VehicleInstance>, viewProj: FloatArray, camPos: FloatArray) {
        if (cars.isEmpty() || !model.uploaded) return
        val time = (SystemClock.uptimeMillis() % 1_000_000L) / 1000f
        val spacing = model.lengthM / 16f

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // Projector pads first, so the cars' depth doesn't cut them.
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(padProg)
        GLES30.glUniformMatrix4fv(pu["u_ViewProj"]!!, 1, false, viewProj, 0)
        GLES30.glUniform2f(pu["u_Half"]!!, model.widthM * 0.5f * 1.25f, model.lengthM * 0.5f * 1.12f)
        GLES30.glUniform1f(pu["u_Time"]!!, time)
        GLES30.glUniform1f(pu["u_Spacing"]!!, spacing * 2f)
        Gl.clientArrays()
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        for (c in cars) if (c.shadow) {
            GLES30.glUniformMatrix4fv(pu["u_Model"]!!, 1, false, c.model, 0)
            GLES30.glUniform3fv(pu["u_Tint"]!!, 1, tint(c), 0)
            GLES30.glUniform1f(pu["u_Alpha"]!!, c.alpha)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glDisableVertexAttribArray(0)

        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(u["u_ViewProj"]!!, 1, false, viewProj, 0)
        GLES30.glUniform3fv(u["u_CamPos"]!!, 1, camPos, 0)
        GLES30.glUniform1f(u["u_Time"]!!, time)
        GLES30.glUniform1f(u["u_Spacing"]!!, spacing)
        GLES30.glUniform1f(u["u_Height"]!!, max(model.heightM, 0.1f))
        for (c in cars) {
            GLES30.glUniformMatrix4fv(u["u_Model"]!!, 1, false, c.model, 0)
            GLES30.glUniformMatrix3fv(u["u_NormalMat"]!!, 1, false, GlbModel.normalMatrix(c.model), 0)
            GLES30.glUniform3fv(u["u_Tint"]!!, 1, tint(c), 0)
            GLES30.glUniform1f(u["u_Alpha"]!!, c.alpha)
            // Depth only: the nearest skin of this car.
            GLES30.glColorMask(false, false, false, false)
            GLES30.glDepthMask(true)
            drawGroups(model)
            // Light: only fragments on that skin pass LEQUAL.
            GLES30.glColorMask(true, true, true, true)
            GLES30.glDepthMask(false)
            drawGroups(model)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        Gl.check("hologram")
    }

    private fun drawGroups(model: GlbModel) {
        for (g in model.groups) {
            GLES30.glUniform1f(u["u_Glass"]!!, if (g.material.glass) 1f else 0f)
            GLES30.glBindVertexArray(g.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, g.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        }
    }

    /** Node colour pushed to full brightness, so dark paints (blue, purple) still glow. */
    private fun tint(c: VehicleInstance): FloatArray {
        var r = Color.red(c.paintArgb) / 255f; var g = Color.green(c.paintArgb) / 255f; var b = Color.blue(c.paintArgb) / 255f
        val m = max(r, max(g, b)).coerceAtLeast(0.05f)
        r /= m; g /= m; b /= m
        // A touch of cyan-white keeps every colour reading as "hologram"; degraded nodes go grey.
        val w = 0.22f
        r = r * (1 - w) + 0.55f * w; g = g * (1 - w) + 0.95f * w; b = b * (1 - w) + 1.0f * w
        val grey = (r + g + b) / 3f
        val d = c.desaturate
        return floatArrayOf(r + (grey - r) * d, g + (grey - g) * d, b + (grey - b) * d)
    }

    private companion object {
        const val VS = """#version 300 es
layout(location=0) in vec3 a_Pos;
layout(location=1) in vec3 a_Nrm;
uniform mat4 u_Model;
uniform mat4 u_ViewProj;
uniform mat3 u_NormalMat;
out vec3 v_Local;
out vec3 v_LocalN;
out vec3 v_World;
out vec3 v_Nrm;
void main() {
  v_Local = a_Pos;
  v_LocalN = a_Nrm;
  vec4 w = u_Model * vec4(a_Pos, 1.0);
  v_World = w.xyz;
  v_Nrm = u_NormalMat * a_Nrm;
  gl_Position = u_ViewProj * w;
}
"""
        const val FS = """#version 300 es
precision highp float;
in vec3 v_Local;
in vec3 v_LocalN;
in vec3 v_World;
in vec3 v_Nrm;
out vec4 o;
uniform vec3 u_CamPos;
uniform vec3 u_Tint;
uniform float u_Alpha;
uniform float u_Time;
uniform float u_Spacing;
uniform float u_Height;
uniform float u_Glass;

// Anti-aliased line every 1.0 of x, about `px` pixels wide.
float gridLine(float x, float px) {
  float d = abs(fract(x - 0.5) - 0.5) / max(fwidth(x), 1e-4);
  return 1.0 - clamp(d / px, 0.0, 1.0);
}

void main() {
  vec3 N = normalize(v_Nrm);
  if (!gl_FrontFacing) N = -N;
  vec3 V = normalize(u_CamPos - v_World);
  // clamp: |N.V| can round past 1, and pow() of a negative base is undefined (NaN on some GPUs).
  float rim = pow(clamp(1.0 - abs(dot(N, V)), 0.0, 1.0), 2.2);

  // Model-space lattice. A face perpendicular to an axis has constant coordinate along it, so
  // that axis's lines are faded out there instead of flooding the whole face.
  vec3 ln = abs(normalize(v_LocalN));
  vec3 g = v_Local / u_Spacing;
  float lx = gridLine(g.x, 1.0) * (1.0 - smoothstep(0.80, 0.97, ln.x));
  float ly = gridLine(g.y, 1.3) * (1.0 - smoothstep(0.80, 0.97, ln.y));
  float lz = gridLine(g.z, 1.0) * (1.0 - smoothstep(0.80, 0.97, ln.z));
  float lines = max(ly, max(lx, lz) * 0.7);

  float h = clamp(v_Local.y / u_Height, 0.0, 1.0);
  float bd = (h - fract(u_Time * 0.32)) * 12.0;
  float band = exp(-bd * bd);
  float stripes = 0.82 + 0.18 * step(0.5, fract(gl_FragCoord.y / 4.0));
  float flicker = 0.95 + 0.05 * sin(u_Time * 41.0 + h * 7.0);
  float base = (0.05 + 0.04 * (1.0 - h)) * (u_Glass > 0.5 ? 0.5 : 1.0);

  float I = (base + 0.5 * lines + 0.7 * rim + 0.35 * band * (0.3 + lines)) * stripes * flicker;
  vec3 col = u_Tint * I + vec3(0.85, 1.0, 1.0) * (0.12 * lines + 0.1 * rim) * I;
  // Soft roll-off instead of hard clipping, so the light adds to the camera image without blowing out.
  col = 1.0 - exp(-col * 1.3);
  o = vec4(col * u_Alpha, 1.0);
}
"""
        const val PAD_VS = """#version 300 es
layout(location=0) in vec2 a_Pos;
uniform mat4 u_Model;
uniform mat4 u_ViewProj;
uniform vec2 u_Half;
out vec2 v_P;
void main() {
  v_P = a_Pos * u_Half;
  gl_Position = u_ViewProj * u_Model * vec4(v_P.x, 0.006, v_P.y, 1.0);
}
"""
        const val PAD_FS = """#version 300 es
precision highp float;
in vec2 v_P;
out vec4 o;
uniform vec2 u_Half;
uniform vec3 u_Tint;
uniform float u_Alpha;
uniform float u_Time;
uniform float u_Spacing;
void main() {
  // Rounded-rectangle footprint: bright outline, soft glow, faint grid inside.
  float r = 0.2;
  vec2 q = abs(v_P) - (u_Half - vec2(r + 0.04));
  float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
  float edge = 1.0 - clamp(abs(sd) / (fwidth(sd) * 1.6), 0.0, 1.0);
  float glow = exp(-abs(sd) * 10.0) * 0.45;
  vec2 g = v_P / u_Spacing;
  vec2 gd = abs(fract(g - 0.5) - 0.5) / max(fwidth(g), vec2(1e-4));
  float grid = (1.0 - clamp(min(gd.x, gd.y), 0.0, 1.0)) * 0.35;
  float inside = 1.0 - smoothstep(-0.02, 0.02, sd);
  float pulse = 0.78 + 0.22 * sin(u_Time * 3.0);
  float I = (edge + glow) * pulse + inside * (grid + 0.05);
  o = vec4(u_Tint * I * u_Alpha, 1.0);
}
"""
    }
}
