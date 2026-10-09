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
 * YOLO11n (COCO, 80 classes) exported to ONNX at 640x320 (portrait, like the phone's frame),
 * run with ONNX Runtime. Uses the XNNPACK execution provider when available.
 */
class YoloDetector(context: Context, asset: String = "models/yolo11n_640x320.onnx") : AutoCloseable {
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
        val opts = OrtSession.SessionOptions()
        try {
            opts.addXnnpack(mapOf("intra_op_num_threads" to "4"))
            opts.setIntraOpNumThreads(1)
            used = "XNNPACK"
        } catch (e: Exception) {
            opts.setIntraOpNumThreads(4)
        }
        session = env.createSession(bytes, opts)
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
