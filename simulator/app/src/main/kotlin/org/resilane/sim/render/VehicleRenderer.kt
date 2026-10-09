package org.resilane.sim.render

import android.graphics.Color
import android.opengl.GLES30
import java.nio.FloatBuffer
import kotlin.math.pow

/** One car to draw this frame. */
class VehicleInstance(
    /** Column-major model matrix including scale; model space is metres, +Z forward, y=0 ground. */
    val model: FloatArray,
    val paintArgb: Int,
    /** 1 = solid. < 1 draws a translucent "ghost" (tracking degraded / stale). */
    val alpha: Float,
    val desaturate: Float,
    val shadow: Boolean,
)

/**
 * Physically-based-ish shading of a [GlbModel] under the [Environment]: GGX direct light from
 * ARCore's main light, SH ambient with baked AO, cubemap reflections with a clearcoat layer on the
 * paint, dark tinted glass, ACES tone mapping. Plus a soft contact shadow under each grounded car,
 * which is what visually anchors it to the floor.
 */
class VehicleRenderer(private val env: Environment) {
    private var prog = 0
    private var shadowProg = 0
    private val u = HashMap<String, Int>()
    private val su = HashMap<String, Int>()
    private val quad: FloatBuffer = Gl.floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))

    fun init() {
        prog = Gl.program(VS, FS)
        for (n in listOf("u_Model", "u_ViewProj", "u_NormalMat", "u_CamPos", "u_LightDir", "u_LightColor", "u_SH", "u_Env",
            "u_EnvMaxLod", "u_EnvIntensity", "u_BaseColor", "u_Metal", "u_Rough", "u_Clearcoat", "u_Emissive", "u_HasBase",
            "u_HasEm", "u_HasAo", "u_AoStrength", "u_Glass", "u_Alpha", "u_Desat", "u_BaseTex", "u_EmTex", "u_AoTex",
            "u_BaseXf", "u_Exposure")) u[n] = GLES30.glGetUniformLocation(prog, n)
        shadowProg = Gl.program(SHADOW_VS, SHADOW_FS)
        for (n in listOf("u_Model", "u_ViewProj", "u_Half", "u_Strength")) su[n] = GLES30.glGetUniformLocation(shadowProg, n)
    }

    fun draw(model: GlbModel, cars: List<VehicleInstance>, viewProj: FloatArray, camPos: FloatArray) {
        if (cars.isEmpty() || !model.uploaded) return
        drawShadows(model, cars.filter { it.shadow }, viewProj)

        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(u["u_ViewProj"]!!, 1, false, viewProj, 0)
        GLES30.glUniform3fv(u["u_CamPos"]!!, 1, camPos, 0)
        GLES30.glUniform3fv(u["u_LightDir"]!!, 1, env.lightDir, 0)
        GLES30.glUniform3fv(u["u_LightColor"]!!, 1, env.lightColor, 0)
        GLES30.glUniform3fv(u["u_SH"]!!, 9, env.sh, 0)
        GLES30.glUniform1f(u["u_EnvMaxLod"]!!, env.maxLod)
        GLES30.glUniform1f(u["u_EnvIntensity"]!!, 1.0f)
        GLES30.glUniform1f(u["u_Exposure"]!!, 1.15f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, env.cubemap)
        GLES30.glUniform1i(u["u_Env"]!!, 3)
        GLES30.glUniform1i(u["u_BaseTex"]!!, 0)
        GLES30.glUniform1i(u["u_EmTex"]!!, 1)
        GLES30.glUniform1i(u["u_AoTex"]!!, 2)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        for (car in cars) {
            GLES30.glUniformMatrix4fv(u["u_Model"]!!, 1, false, car.model, 0)
            GLES30.glUniformMatrix3fv(u["u_NormalMat"]!!, 1, false, GlbModel.normalMatrix(car.model), 0)
            GLES30.glUniform1f(u["u_Desat"]!!, car.desaturate)
            val ghost = car.alpha < 0.99f
            if (ghost) {
                // Depth pre-pass so a translucent car shows only its outer skin, not its insides.
                GLES30.glColorMask(false, false, false, false)
                drawGroups(model, car, opaqueOnly = true)
                GLES30.glColorMask(true, true, true, true)
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glDepthMask(false)
                drawGroups(model, car, opaqueOnly = true)
                GLES30.glDepthMask(true)
                GLES30.glDisable(GLES30.GL_BLEND)
            } else {
                drawGroups(model, car, opaqueOnly = false)
            }
        }
        GLES30.glBindVertexArray(0)
        Gl.check("vehicles")
    }

    private fun drawGroups(model: GlbModel, car: VehicleInstance, opaqueOnly: Boolean) {
        val paint = srgbToLinear(car.paintArgb)
        var blending = false
        for (g in model.groups) {
            val m = g.material
            val transparent = m.glass || m.blend
            if (transparent && opaqueOnly) continue
            if (transparent && !blending) {
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glDepthMask(false)
                blending = true
            }
            if (m.doubleSided || transparent) GLES30.glDisable(GLES30.GL_CULL_FACE)
            else { GLES30.glEnable(GLES30.GL_CULL_FACE); GLES30.glCullFace(GLES30.GL_BACK) }

            if (m.isPaint) GLES30.glUniform4f(u["u_BaseColor"]!!, paint[0], paint[1], paint[2], 1f)
            else GLES30.glUniform4fv(u["u_BaseColor"]!!, 1, m.baseColor, 0)
            GLES30.glUniform1f(u["u_Metal"]!!, if (m.isPaint) 0.15f else m.metallic)
            GLES30.glUniform1f(u["u_Rough"]!!, if (m.isPaint) 0.35f else m.roughness)
            GLES30.glUniform1f(u["u_Clearcoat"]!!, m.clearcoat)
            GLES30.glUniform3fv(u["u_Emissive"]!!, 1, m.emissive, 0)
            GLES30.glUniform1f(u["u_Glass"]!!, if (m.glass) 1f else 0f)
            GLES30.glUniform1f(u["u_Alpha"]!!, car.alpha)
            bindTex(model, m.baseTex, 0, "u_HasBase")
            bindTex(model, m.emTex, 1, "u_HasEm")
            bindTex(model, m.aoTex, 2, "u_HasAo")
            GLES30.glUniform1f(u["u_AoStrength"]!!, m.aoStrength)
            GLES30.glUniformMatrix3fv(u["u_BaseXf"]!!, 1, false, m.baseXf, 0)
            GLES30.glBindVertexArray(g.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, g.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        }
        if (blending) { GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDepthMask(true) }
        GLES30.glDisable(GLES30.GL_CULL_FACE)
    }

    private fun bindTex(model: GlbModel, image: Int?, unit: Int, flag: String) {
        val id = image?.let { model.textures[it] }
        GLES30.glUniform1f(u[flag]!!, if (id != null) 1f else 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id ?: 0)
    }

    private fun drawShadows(model: GlbModel, cars: List<VehicleInstance>, viewProj: FloatArray) {
        if (cars.isEmpty()) return
        GLES30.glUseProgram(shadowProg)
        GLES30.glUniformMatrix4fv(su["u_ViewProj"]!!, 1, false, viewProj, 0)
        GLES30.glUniform2f(su["u_Half"]!!, model.widthM * 0.5f * 1.08f, model.lengthM * 0.5f * 1.04f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        Gl.clientArrays()
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        for (c in cars) {
            GLES30.glUniformMatrix4fv(su["u_Model"]!!, 1, false, c.model, 0)
            GLES30.glUniform1f(su["u_Strength"]!!, 0.62f * c.alpha)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun srgbToLinear(argb: Int): FloatArray {
        fun c(v: Int) = (v / 255f).let { if (it <= 0.04045f) it / 12.92f else ((it + 0.055f) / 1.055f).pow(2.4f) }
        return floatArrayOf(c(Color.red(argb)), c(Color.green(argb)), c(Color.blue(argb)))
    }

    private companion object {
        const val VS = """#version 300 es
layout(location=0) in vec3 a_Pos;
layout(location=1) in vec3 a_Nrm;
layout(location=2) in vec2 a_Uv0;
layout(location=3) in vec2 a_Uv1;
uniform mat4 u_Model;
uniform mat4 u_ViewProj;
uniform mat3 u_NormalMat;
out vec3 v_World;
out vec3 v_Nrm;
out vec2 v_Uv0;
out vec2 v_Uv1;
void main() {
  vec4 w = u_Model * vec4(a_Pos, 1.0);
  v_World = w.xyz;
  v_Nrm = u_NormalMat * a_Nrm;
  v_Uv0 = a_Uv0;
  v_Uv1 = a_Uv1;
  gl_Position = u_ViewProj * w;
}
"""
        const val FS = """#version 300 es
precision highp float;
in vec3 v_World;
in vec3 v_Nrm;
in vec2 v_Uv0;
in vec2 v_Uv1;
out vec4 o;
uniform vec3 u_CamPos;
uniform vec3 u_LightDir;
uniform vec3 u_LightColor;
uniform vec3 u_SH[9];
uniform samplerCube u_Env;
uniform float u_EnvMaxLod;
uniform float u_EnvIntensity;
uniform float u_Exposure;
uniform vec4 u_BaseColor;
uniform float u_Metal;
uniform float u_Rough;
uniform float u_Clearcoat;
uniform vec3 u_Emissive;
uniform float u_HasBase;
uniform float u_HasEm;
uniform float u_HasAo;
uniform float u_AoStrength;
uniform float u_Glass;
uniform float u_Alpha;
uniform float u_Desat;
uniform sampler2D u_BaseTex;
uniform sampler2D u_EmTex;
uniform sampler2D u_AoTex;
uniform mat3 u_BaseXf;

vec3 shIrradiance(vec3 n) {
  vec3 r = u_SH[0] + u_SH[1]*n.y + u_SH[2]*n.z + u_SH[3]*n.x + u_SH[4]*n.y*n.x + u_SH[5]*n.y*n.z
         + u_SH[6]*(3.0*n.z*n.z - 1.0) + u_SH[7]*n.z*n.x + u_SH[8]*(n.x*n.x - n.y*n.y);
  return max(r, vec3(0.0));
}
vec3 aces(vec3 x) { return clamp((x*(2.51*x + 0.03)) / (x*(2.43*x + 0.59) + 0.14), 0.0, 1.0); }

void main() {
  vec3 N = normalize(v_Nrm);
  if (!gl_FrontFacing) N = -N;
  vec3 V = normalize(u_CamPos - v_World);
  vec4 base = u_BaseColor;
  if (u_HasBase > 0.5) base *= texture(u_BaseTex, (u_BaseXf * vec3(v_Uv0, 1.0)).xy);
  float ao = 1.0;
  if (u_HasAo > 0.5) ao = mix(1.0, texture(u_AoTex, v_Uv1).r, u_AoStrength);
  float rough = clamp(u_Rough, 0.05, 1.0);
  vec3 L = normalize(u_LightDir);
  vec3 H = normalize(L + V);
  float NdL = max(dot(N, L), 0.0);
  float NdV = max(dot(N, V), 1e-3);
  float NdH = max(dot(N, H), 0.0);
  vec3 F0 = mix(vec3(0.04), base.rgb, u_Metal);
  vec3 F = F0 + (1.0 - F0) * pow(clamp(1.0 - NdV, 0.0, 1.0), 5.0);
  float a = rough * rough;
  float a2 = a * a;
  float dd = NdH * NdH * (a2 - 1.0) + 1.0;
  float D = a2 / (3.14159 * dd * dd);
  float k = (rough + 1.0) * (rough + 1.0) / 8.0;
  float G = (NdL / (NdL * (1.0 - k) + k)) * (NdV / (NdV * (1.0 - k) + k));
  vec3 direct = D * G * F / max(4.0 * NdV * NdL, 1e-3) * u_LightColor * NdL;
  vec3 R = reflect(-V, N);
  vec3 envRough = textureLod(u_Env, R, rough * u_EnvMaxLod).rgb * u_EnvIntensity;
  vec3 diffuse = base.rgb * (1.0 - u_Metal) * (shIrradiance(N) * ao + u_LightColor * NdL * 0.6);
  vec3 col = diffuse * (1.0 - F) + direct + envRough * F * ao;
  if (u_Clearcoat > 0.0) {
    float Fc = 0.04 + 0.96 * pow(clamp(1.0 - NdV, 0.0, 1.0), 5.0);
    vec3 envSharp = textureLod(u_Env, R, 0.0).rgb * u_EnvIntensity;
    col = col * (1.0 - Fc * u_Clearcoat) + envSharp * Fc * u_Clearcoat * ao;
  }
  vec3 em = u_Emissive;
  if (u_HasEm > 0.5) em *= texture(u_EmTex, v_Uv0).rgb;
  col += em;
  float alpha = base.a * u_Alpha;
  if (u_Glass > 0.5) {
    vec3 envSharp = textureLod(u_Env, R, 0.0).rgb * u_EnvIntensity;
    float Fg = 0.06 + 0.94 * pow(clamp(1.0 - NdV, 0.0, 1.0), 5.0);
    col = vec3(0.012, 0.014, 0.018) + envSharp * Fg;
    alpha = clamp(0.62 + 0.38 * Fg, 0.0, 1.0) * u_Alpha;
  }
  col = aces(col * u_Exposure);
  float g = dot(col, vec3(0.299, 0.587, 0.114));
  col = mix(col, vec3(g), u_Desat);
  o = vec4(pow(col, vec3(1.0/2.2)), alpha);
}
"""
        const val SHADOW_VS = """#version 300 es
layout(location=0) in vec2 a_Pos;
uniform mat4 u_Model;
uniform mat4 u_ViewProj;
uniform vec2 u_Half;
out vec2 v_P;
void main() {
  v_P = a_Pos;
  // A little above the ground plane (model y = 0) to avoid z-fighting with detected planes.
  gl_Position = u_ViewProj * u_Model * vec4(a_Pos.x * u_Half.x, 0.004, a_Pos.y * u_Half.y, 1.0);
}
"""
        const val SHADOW_FS = """#version 300 es
precision mediump float;
in vec2 v_P;
uniform float u_Strength;
out vec4 o;
void main() {
  // Rounded-rectangle falloff: dark under the body, fading out past the wheels.
  vec2 q = abs(v_P);
  float d = length(max(q - vec2(0.62, 0.70), 0.0)) + min(max(q.x - 0.62, q.y - 0.70), 0.0);
  float a = 1.0 - smoothstep(-0.25, 0.32, d);
  o = vec4(0.0, 0.0, 0.0, a * a * u_Strength);
}
"""
    }
}
