package org.resilane.sim.render

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.FloatBuffer

/**
 * Draws the live rear-camera image full-screen from one external OES texture: ARCore's camera
 * texture, or (no-ARCore fallback) a Camera2 SurfaceTexture, centre-cropped to fill the screen.
 */
class BackgroundRenderer {
    var textureId = 0; private set
    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private val quad: FloatBuffer = Gl.floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv: FloatBuffer = Gl.floats(FloatArray(8))
    private val feedUv: FloatBuffer = Gl.floats(FloatArray(8))
    private var feedProg = 0
    private var uTexM = 0

    fun init() {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        textureId = t[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES30.glBindTexture(target, textureId)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        program = Gl.program(VS, FS)
        aPos = GLES30.glGetAttribLocation(program, "a_Pos")
        aUv = GLES30.glGetAttribLocation(program, "a_Uv")
        feedProg = Gl.program(FEED_VS, FS)
        uTexM = GLES30.glGetUniformLocation(feedProg, "u_TexM")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0); uv.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                Coordinates2d.TEXTURE_NORMALIZED, uv)
            quad.position(0); uv.position(0)
        }
        if (frame.timestamp == 0L) return
        GLES30.glUseProgram(program)
        quad(uv)
    }

    /**
     * Camera2 fallback: [texM] is the SurfaceTexture transform (it already turns the sensor image
     * upright for the portrait screen), [imgAspect] the upright image's width / height.
     */
    fun drawFeed(texM: FloatArray, imgAspect: Float, screenW: Int, screenH: Int) {
        val scr = screenW.toFloat() / screenH
        val sx = if (scr < imgAspect) scr / imgAspect else 1f
        val sy = if (scr < imgAspect) 1f else imgAspect / scr
        val u0 = 0.5f - sx / 2; val u1 = 0.5f + sx / 2; val v0 = 0.5f - sy / 2; val v1 = 0.5f + sy / 2
        feedUv.position(0); feedUv.put(floatArrayOf(u0, v0, u1, v0, u0, v1, u1, v1)); feedUv.position(0)
        GLES30.glUseProgram(feedProg)
        GLES30.glUniformMatrix4fv(uTexM, 1, false, texM, 0)
        quad(feedUv)
    }

    private fun quad(uvs: FloatBuffer) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        Gl.clientArrays()
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 0, uvs)
        GLES30.glEnableVertexAttribArray(aPos); GLES30.glEnableVertexAttribArray(aUv)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPos); GLES30.glDisableVertexAttribArray(aUv)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private companion object {
        const val VS = """#version 300 es
layout(location=0) in vec2 a_Pos;
layout(location=1) in vec2 a_Uv;
out vec2 v_Uv;
void main() { v_Uv = a_Uv; gl_Position = vec4(a_Pos, 0.0, 1.0); }
"""
        const val FEED_VS = """#version 300 es
layout(location=0) in vec2 a_Pos;
layout(location=1) in vec2 a_Uv;
uniform mat4 u_TexM;
out vec2 v_Uv;
void main() { v_Uv = (u_TexM * vec4(a_Uv, 0.0, 1.0)).xy; gl_Position = vec4(a_Pos, 0.0, 1.0); }
"""
        const val FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES u_Tex;
in vec2 v_Uv;
out vec4 o;
void main() { o = texture(u_Tex, v_Uv); }
"""
    }
}
