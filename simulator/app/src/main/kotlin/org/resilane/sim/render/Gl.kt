package org.resilane.sim.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import org.resilane.sim.RLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

object Gl {
    fun program(vs: String, fs: String): Int {
        val v = shader(GLES30.GL_VERTEX_SHADER, vs)
        val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("link: " + GLES30.glGetProgramInfoLog(p))
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) error("compile: " + GLES30.glGetShaderInfoLog(s) + "\n" + src.lines().take(8).joinToString("\n"))
        return s
    }

    fun floats(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    /**
     * Call before drawing from client-side arrays (java.nio buffers): with a VAO or an array buffer
     * still bound, the buffer's address would be read as an offset into that VBO instead.
     */
    fun clientArrays() {
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun check(where: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) RLog.w("GL", "$where: 0x${Integer.toHexString(e)}")
    }
}

/**
 * Asks for 4x MSAA (smoother car silhouettes, which matters for detection and looks), falling back
 * to a plain RGBA8888 + depth config on GPUs without it.
 */
class MsaaConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val es3 = 0x40 // EGL_OPENGL_ES3_BIT_KHR
        fun attrs(samples: Int) = intArrayOf(
            EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_RENDERABLE_TYPE, es3,
            EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, EGL10.EGL_SAMPLES, samples,
            EGL10.EGL_NONE,
        )
        for (samples in intArrayOf(4, 0)) {
            val n = IntArray(1)
            val cfgs = arrayOfNulls<EGLConfig>(1)
            if (egl.eglChooseConfig(display, attrs(samples), cfgs, 1, n) && n[0] > 0 && cfgs[0] != null) {
                RLog.i("GL", "EGL config: ${samples}x MSAA")
                return cfgs[0]!!
            }
        }
        error("no usable EGL config")
    }
}
