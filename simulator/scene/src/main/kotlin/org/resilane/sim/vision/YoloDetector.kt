package org.resilane.sim.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.resilane.core.vision.Detection
import org.resilane.core.vision.Letterbox
import org.resilane.core.vision.YoloDecoder
import org.resilane.sim.RLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * YOLO11n exported to ONNX, run with ONNX Runtime. With [npu] it asks for the Qualcomm QNN execution provider on the
 * Hexagon NPU (HTP, fp16); unsupported layers fall back to the CPU. Without it, or if QNN is unavailable, it uses
 * XNNPACK when the runtime has it, else ONNX Runtime's own CPU kernels. [provider] says which one is running.
 */
class YoloDetector(context: Context, asset: String = "models/yolo11n_640x320.onnx", npu: Boolean = false) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    val inH: Int
    val inW: Int
    private val numClasses: Int
    private val numAnchors: Int
    val provider: String

    private val canvasBmp: Bitmap
    private val canvas: Canvas
    private val pixels: IntArray
    private val input: FloatBuffer
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        var used = "CPU"
        var opts = OrtSession.SessionOptions()
        var made: OrtSession? = null
        if (npu) runCatching {
            opts.addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1",
                "htp_graph_finalization_optimization_mode" to "3"))
            opts.setIntraOpNumThreads(2)
            made = env.createSession(bytes, opts); used = "QNN-HTP"
        }.onFailure { RLog.i("Yolo", "QNN unavailable: ${it.message}"); opts = OrtSession.SessionOptions() }
        if (made == null) {
            try {
                opts.addXnnpack(mapOf("intra_op_num_threads" to "4"))
                opts.setIntraOpNumThreads(1)
                used = "XNNPACK"
            } catch (e: Exception) {
                opts.setIntraOpNumThreads(4)
            }
            made = env.createSession(bytes, opts)
        }
        session = made!!
        provider = used
        inputName = session.inputNames.first()
        val inShape = (session.inputInfo[inputName]!!.info as ai.onnxruntime.TensorInfo).shape
        inH = inShape[2].toInt(); inW = inShape[3].toInt()
        val outShape = (session.outputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape
        numClasses = outShape[1].toInt() - 4
        numAnchors = outShape[2].toInt()
        canvasBmp = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
        canvas = Canvas(canvasBmp)
        pixels = IntArray(inW * inH)
        input = ByteBuffer.allocateDirect(3 * inW * inH * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        RLog.i("Yolo", "loaded $asset: input ${inW}x$inH, $numClasses classes, $numAnchors anchors, provider $provider")
    }

    /** Detections in [src] pixel coordinates. */
    fun detect(src: Bitmap, conf: Float, keep: Set<Int>? = null): List<Detection> {
        val lb = Letterbox.fit(src.width, src.height, inW, inH)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(src, null, RectF(lb.padX, lb.padY, lb.padX + src.width * lb.scale, lb.padY + src.height * lb.scale), paint)
        canvasBmp.getPixels(pixels, 0, inW, 0, 0, inW, inH)
        val plane = inW * inH
        input.clear()
        for (i in 0 until plane) {
            val p = pixels[i]
            input.put(i, ((p shr 16) and 0xFF) / 255f)
            input.put(plane + i, ((p shr 8) and 0xFF) / 255f)
            input.put(2 * plane + i, (p and 0xFF) / 255f)
        }
        input.rewind()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, inH.toLong(), inW.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { r ->
                val out = (r[0] as OnnxTensor).floatBuffer
                val arr = FloatArray(out.remaining())
                out.get(arr)
                return YoloDecoder.decode(arr, numClasses, numAnchors, lb, conf, 0.5f, keep)
            }
        }
    }

    override fun close() { session.close() }
}
