package org.resilane.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import org.resilane.core.vision.Box
import org.resilane.core.vision.Coco
import org.resilane.core.vision.Letterbox
import org.resilane.core.vision.YoloDecoder
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.FloatBuffer
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone's YOLO path, minus Android: letterbox to the model's 320x640 (w x h) input, RGB/255
 * CHW, run the shipped ONNX, decode with [YoloDecoder]. Compared with what Ultralytics itself
 * reports for the same Blender render of the vehicle (0.91 "car", recorded by tools/yolo_check).
 */
class YoloOnnxTest {
    private val root = File(System.getProperty("resilane.root") ?: "..")

    @Test fun shippedModelFindsTheRenderedCar() {
        val model = File(root, "scene/src/main/assets/models/yolo11n_640x320.onnx")
        check(model.exists()) { "Model test requested but ${model.path} is missing. Run tools/fetch_model.py first." }
        val img = ImageIO.read(javaClass.getResource("/render_car_d08_a030.jpg"))
        val env = OrtEnvironment.getEnvironment()
        env.createSession(model.readBytes()).use { s ->
            val shape = (s.inputInfo.values.first().info as TensorInfo).shape
            val inH = shape[2].toInt(); val inW = shape[3].toInt()
            assertEquals(640, inH); assertEquals(320, inW)
            val lb = Letterbox.fit(img.width, img.height, inW, inH)
            val canvas = BufferedImage(inW, inH, BufferedImage.TYPE_INT_RGB)
            canvas.createGraphics().apply {
                color = Color(114, 114, 114); fillRect(0, 0, inW, inH)
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(img, lb.padX.toInt(), lb.padY.toInt(), (img.width * lb.scale).toInt(), (img.height * lb.scale).toInt(), null)
                dispose()
            }
            val plane = inW * inH
            val input = FloatBuffer.allocate(3 * plane)
            for (y in 0 until inH) for (x in 0 until inW) {
                val p = canvas.getRGB(x, y); val i = y * inW + x
                input.put(i, ((p shr 16) and 0xFF) / 255f)
                input.put(plane + i, ((p shr 8) and 0xFF) / 255f)
                input.put(2 * plane + i, (p and 0xFF) / 255f)
            }
            OnnxTensor.createTensor(env, input, longArrayOf(1, 3, inH.toLong(), inW.toLong())).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { r ->
                    val out = (r[0] as OnnxTensor).floatBuffer
                    val arr = FloatArray(out.remaining()).also { out.get(it) }
                    val dets = YoloDecoder.decode(arr, 80, arr.size / 84, lb, 0.25f)
                    val car = dets.maxByOrNull { it.score }!!
                    assertEquals(Coco.CAR, car.cls)
                    assertTrue(car.score > 0.8f, "score ${car.score}")
                    val expected = Box(113.6f, 468.8f, 492.8f, 619.8f) // Ultralytics on the same image
                    assertTrue(car.box.iou(expected) > 0.9f, "box ${car.box} vs $expected")
                }
            }
        }
    }
}
