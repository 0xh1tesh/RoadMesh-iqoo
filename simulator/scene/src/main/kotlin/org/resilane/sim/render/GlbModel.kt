package org.resilane.sim.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import org.json.JSONArray
import org.json.JSONObject
import org.resilane.sim.RLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Minimal glTF 2.0 binary (.glb) loader, enough for a static vehicle: node hierarchy (baked into
 * vertices), triangle primitives, metallic-roughness materials with base colour / emissive /
 * occlusion textures, KHR_texture_transform on the base colour, and transmission flagged as glass.
 * Normal maps, animation and skinning are ignored.
 *
 * After loading, the model is normalised: centred on X/Z, wheels on y = 0, facing +Z, so a model
 * matrix of (position on the ground) x (yaw) x (scale) puts it on the road.
 */
class GlbModel private constructor(
    val groups: List<Group>,
    val images: Map<Int, Bitmap>,
    /** Model-space bounds after normalisation (x: width, y: height, z: length). */
    val min: FloatArray,
    val max: FloatArray,
    /** Sparse model-space points on the vehicle's surface, xyz packed, for ground-truth boxes. */
    val silhouette: FloatArray,
) {
    class Material(
        val name: String,
        val baseColor: FloatArray,
        val metallic: Float,
        val roughness: Float,
        val emissive: FloatArray,
        val baseTex: Int?,
        val baseXf: FloatArray, // column-major mat3 for UVs
        val emTex: Int?,
        val aoTex: Int?,
        val aoStrength: Float,
        val doubleSided: Boolean,
        val glass: Boolean,
        val blend: Boolean,
        val clearcoat: Float,
    ) {
        /** Body paint: tinted with the node's colour. */
        val isPaint get() = name.startsWith("Paint 1")
    }

    /** All triangles sharing one material, interleaved pos3 nrm3 uv0 uv1 (10 floats). */
    class Group(val material: Material, val vertices: FloatBuffer, val indices: IntBuffer, val indexCount: Int) {
        var vao = 0; var vbo = 0; var ibo = 0
    }

    val lengthM get() = max[2] - min[2]
    val widthM get() = max[0] - min[0]
    val heightM get() = max[1] - min[1]

    /** GL texture ids by image index; filled by [upload] on the GL thread. */
    val textures = HashMap<Int, Int>()
    @Volatile var uploaded = false; private set

    /** The old context already deleted its objects; retain CPU data for a fresh upload. */
    fun contextLost() {
        uploaded = false
        textures.clear()
        groups.forEach { it.vao = 0; it.vbo = 0; it.ibo = 0 }
    }

    /** Called after the GL view has stopped, when this model will never be uploaded again. */
    fun releaseCpu() { images.values.forEach { if (!it.isRecycled) it.recycle() } }

    fun upload(srgbImages: Set<Int>) {
        for (g in groups) {
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0); g.vao = ids[0]
            GLES30.glBindVertexArray(g.vao)
            GLES30.glGenBuffers(1, ids, 0); g.vbo = ids[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, g.vertices.capacity() * 4, g.vertices, GLES30.GL_STATIC_DRAW)
            val stride = 10 * 4
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
            GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, stride, 32)
            GLES30.glGenBuffers(1, ids, 0); g.ibo = ids[0]
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.ibo)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.indexCount * 4, g.indices, GLES30.GL_STATIC_DRAW)
            GLES30.glBindVertexArray(0)
        }
        // GL_ARRAY_BUFFER is global, not VAO state: left bound, it turns every later client-side
        // vertex array (camera background, grid, pads) into an offset into this VBO.
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        for ((idx, bmp) in images) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            if (idx in srgbImages) {
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, bmp, GLES30.GL_UNSIGNED_BYTE, 0)
            } else {
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            }
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
            textures[idx] = ids[0]
            Gl.check("texture $idx")
        }
        uploaded = true
    }

    companion object {
        private const val FLOAT = 5126
        private const val UBYTE = 5121
        private const val USHORT = 5123
        private const val UINT = 5125

        fun load(bytes: ByteArray): GlbModel {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bb.int == 0x46546C67) { "not a GLB" }
            bb.int; bb.int // version, length
            val jsonLen = bb.int; require(bb.int == 0x4E4F534A)
            val json = JSONObject(String(bytes, 20, jsonLen, Charsets.UTF_8))
            bb.position(20 + jsonLen)
            val binLen = bb.int; bb.int
            val binStart = bb.position()
            val bin = ByteBuffer.wrap(bytes, binStart, binLen).slice().order(ByteOrder.LITTLE_ENDIAN)

            val views = json.getJSONArray("bufferViews")
            val accessors = json.getJSONArray("accessors")
            val texturesJ = json.optJSONArray("textures") ?: JSONArray()
            val imagesJ = json.optJSONArray("images") ?: JSONArray()

            fun texImage(texIdx: Int) = texturesJ.getJSONObject(texIdx).getInt("source")

            fun readAccessor(i: Int): Pair<FloatArray, Int> {
                val a = accessors.getJSONObject(i)
                val count = a.getInt("count")
                val comps = when (a.getString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> error("type") }
                val ct = a.getInt("componentType")
                val norm = a.optBoolean("normalized", false)
                val v = views.getJSONObject(a.getInt("bufferView"))
                val csize = when (ct) { FLOAT, UINT -> 4; USHORT -> 2; else -> 1 }
                val stride = v.optInt("byteStride", 0).takeIf { it > 0 } ?: (csize * comps)
                val base = v.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
                val out = FloatArray(count * comps)
                for (e in 0 until count) for (c in 0 until comps) {
                    val p = base + e * stride + c * csize
                    out[e * comps + c] = when (ct) {
                        FLOAT -> bin.getFloat(p)
                        UINT -> (bin.getInt(p).toLong() and 0xFFFFFFFFL).toFloat()
                        USHORT -> (bin.getShort(p).toInt() and 0xFFFF).let { if (norm) it / 65535f else it.toFloat() }
                        UBYTE -> (bin.get(p).toInt() and 0xFF).let { if (norm) it / 255f else it.toFloat() }
                        else -> error("componentType $ct")
                    }
                }
                return out to comps
            }

            fun readIndices(i: Int): IntArray {
                val a = accessors.getJSONObject(i)
                val count = a.getInt("count")
                val ct = a.getInt("componentType")
                val v = views.getJSONObject(a.getInt("bufferView"))
                val base = v.optInt("byteOffset", 0) + a.optInt("byteOffset", 0)
                return IntArray(count) { e ->
                    when (ct) {
                        UINT -> bin.getInt(base + e * 4)
                        USHORT -> bin.getShort(base + e * 2).toInt() and 0xFFFF
                        else -> bin.get(base + e).toInt() and 0xFF
                    }
                }
            }

            // Materials
            val matsJ = json.optJSONArray("materials") ?: JSONArray()
            val usedImages = HashSet<Int>()
            val materials = (0 until matsJ.length()).map { mi ->
                val m = matsJ.getJSONObject(mi)
                val pbr = m.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
                val ext = m.optJSONObject("extensions") ?: JSONObject()
                val name = m.optString("name", "m$mi")
                val bcf = pbr.optJSONArray("baseColorFactor")
                var baseColor = if (bcf != null) FloatArray(4) { bcf.getDouble(it).toFloat() } else floatArrayOf(1f, 1f, 1f, 1f)
                var baseTex = pbr.optJSONObject("baseColorTexture")?.let { texImage(it.getInt("index")) }
                val baseXf = pbr.optJSONObject("baseColorTexture")?.optJSONObject("extensions")
                    ?.optJSONObject("KHR_texture_transform")?.let { uvTransform(it) } ?: identity3()
                val emf = m.optJSONArray("emissiveFactor")
                val emStrength = ext.optJSONObject("KHR_materials_emissive_strength")?.optDouble("emissiveStrength", 1.0)?.toFloat() ?: 1f
                val emissive = if (emf != null) FloatArray(3) { emf.getDouble(it).toFloat() * emStrength } else FloatArray(3)
                var emTex = m.optJSONObject("emissiveTexture")?.let { texImage(it.getInt("index")) }
                val occ = m.optJSONObject("occlusionTexture")
                val aoTex = occ?.takeIf { it.optInt("texCoord", 0) == 1 }?.let { texImage(it.getInt("index")) }
                val transmission = ext.optJSONObject("KHR_materials_transmission")?.optDouble("transmissionFactor", 0.0) ?: 0.0
                val clearcoat = ext.optJSONObject("KHR_materials_clearcoat")?.optDouble("clearcoatFactor", 0.0)?.toFloat() ?: 0f
                if (name == "License") {
                    // The sample asset's plate shows the Khronos logo, which its CC-BY licence
                    // excludes. Draw a blank white plate instead.
                    baseColor = floatArrayOf(0.85f, 0.85f, 0.85f, 1f); baseTex = null; emTex = null
                }
                listOfNotNull(baseTex, emTex, aoTex).forEach { usedImages += it }
                Material(
                    name, baseColor,
                    pbr.optDouble("metallicFactor", 1.0).toFloat(), pbr.optDouble("roughnessFactor", 1.0).toFloat(),
                    emissive, baseTex, baseXf, emTex, aoTex, occ?.optDouble("strength", 1.0)?.toFloat() ?: 1f,
                    m.optBoolean("doubleSided", false), transmission > 0.0,
                    m.optString("alphaMode", "OPAQUE") == "BLEND", if (name.startsWith("Paint 1")) maxOf(clearcoat, 0.6f) else clearcoat,
                )
            }
            val fallback = Material("default", floatArrayOf(0.6f, 0.6f, 0.6f, 1f), 0f, 0.6f, FloatArray(3), null, identity3(), null, null, 1f, false, false, false, 0f)

            // Walk the scene, baking node transforms into geometry, bucketed by material.
            class Acc { val v = ArrayList<FloatArray>(); val i = ArrayList<IntArray>(); var n = 0 }
            val buckets = LinkedHashMap<Int, Acc>()
            val nodes = json.getJSONArray("nodes")
            val meshes = json.getJSONArray("meshes")
            val gmin = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val gmax = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            val allPositions = ArrayList<FloatArray>()

            fun visit(ni: Int, parent: FloatArray) {
                val n = nodes.getJSONObject(ni)
                val world = FloatArray(16)
                Matrix.multiplyMM(world, 0, parent, 0, localMatrix(n), 0)
                if (n.has("mesh")) {
                    val normalM = normalMatrix(world)
                    val flip = det3(world) < 0
                    val prims = meshes.getJSONObject(n.getInt("mesh")).getJSONArray("primitives")
                    for (pi in 0 until prims.length()) {
                        val p = prims.getJSONObject(pi)
                        if (p.optInt("mode", 4) != 4) continue
                        val at = p.getJSONObject("attributes")
                        val (pos, _) = readAccessor(at.getInt("POSITION"))
                        val vc = pos.size / 3
                        val nrm = if (at.has("NORMAL")) readAccessor(at.getInt("NORMAL")).first else FloatArray(vc * 3).also { for (k in 0 until vc) it[k * 3 + 1] = 1f }
                        val uv0 = if (at.has("TEXCOORD_0")) readAccessor(at.getInt("TEXCOORD_0")).first else FloatArray(vc * 2)
                        val uv1 = if (at.has("TEXCOORD_1")) readAccessor(at.getInt("TEXCOORD_1")).first else uv0
                        val idx = if (p.has("indices")) readIndices(p.getInt("indices")) else IntArray(vc) { it }
                        val inter = FloatArray(vc * 10)
                        val tmp = FloatArray(4); val out = FloatArray(4)
                        val wp = FloatArray(vc * 3)
                        for (k in 0 until vc) {
                            tmp[0] = pos[k * 3]; tmp[1] = pos[k * 3 + 1]; tmp[2] = pos[k * 3 + 2]; tmp[3] = 1f
                            Matrix.multiplyMV(out, 0, world, 0, tmp, 0)
                            val nx = nrm[k * 3]; val ny = nrm[k * 3 + 1]; val nz = nrm[k * 3 + 2]
                            var tx = normalM[0] * nx + normalM[3] * ny + normalM[6] * nz
                            var ty = normalM[1] * nx + normalM[4] * ny + normalM[7] * nz
                            var tz = normalM[2] * nx + normalM[5] * ny + normalM[8] * nz
                            val l = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-8f)
                            tx /= l; ty /= l; tz /= l
                            val o = k * 10
                            inter[o] = out[0]; inter[o + 1] = out[1]; inter[o + 2] = out[2]
                            inter[o + 3] = tx; inter[o + 4] = ty; inter[o + 5] = tz
                            inter[o + 6] = uv0[k * 2]; inter[o + 7] = uv0[k * 2 + 1]
                            inter[o + 8] = uv1[k * 2]; inter[o + 9] = uv1[k * 2 + 1]
                            wp[k * 3] = out[0]; wp[k * 3 + 1] = out[1]; wp[k * 3 + 2] = out[2]
                            for (c in 0..2) { gmin[c] = minOf(gmin[c], out[c]); gmax[c] = maxOf(gmax[c], out[c]) }
                        }
                        if (flip) for (t in idx.indices step 3) { val s = idx[t + 1]; idx[t + 1] = idx[t + 2]; idx[t + 2] = s }
                        val acc = buckets.getOrPut(p.optInt("material", -1)) { Acc() }
                        acc.v += inter
                        acc.i += IntArray(idx.size) { idx[it] + acc.n }
                        acc.n += vc
                        allPositions += wp
                    }
                }
                n.optJSONArray("children")?.let { ch -> for (c in 0 until ch.length()) visit(ch.getInt(c), world) }
            }
            val scene = json.getJSONArray("scenes").getJSONObject(json.optInt("scene", 0))
            val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
            val roots = scene.getJSONArray("nodes")
            for (r in 0 until roots.length()) visit(roots.getInt(r), identity)

            // Normalise: centre X/Z, sit on y = 0.
            val sx = -(gmin[0] + gmax[0]) / 2; val sy = -gmin[1]; val sz = -(gmin[2] + gmax[2]) / 2
            val groups = buckets.map { (mi, acc) ->
                val total = acc.v.sumOf { it.size }
                val vb = ByteBuffer.allocateDirect(total * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (arr in acc.v) {
                    for (k in arr.indices step 10) { arr[k] += sx; arr[k + 1] += sy; arr[k + 2] += sz }
                    vb.put(arr)
                }
                vb.position(0)
                val icount = acc.i.sumOf { it.size }
                val ib = ByteBuffer.allocateDirect(icount * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
                acc.i.forEach { ib.put(it) }
                ib.position(0)
                Group(if (mi >= 0) materials[mi] else fallback, vb, ib, icount)
            }
            // Opaque first, then blended/glass, so transparency composites over the body.
            val ordered = groups.sortedBy { if (it.material.glass || it.material.blend) 1 else 0 }

            val min = floatArrayOf(gmin[0] + sx, 0f, gmin[2] + sz)
            val max = floatArrayOf(gmax[0] + sx, gmax[1] + sy, gmax[2] + sz)
            val sil = silhouette(allPositions, sx, sy, sz)

            val images = HashMap<Int, Bitmap>()
            for (ii in usedImages) {
                val img = imagesJ.getJSONObject(ii)
                val v = views.getJSONObject(img.getInt("bufferView"))
                val off = binStart + v.optInt("byteOffset", 0)
                val len = v.getInt("byteLength")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, off, len, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = sample
                }
                BitmapFactory.decodeByteArray(bytes, off, len, opts)?.let { images[ii] = it }
            }
            RLog.i("Model", "GLB: ${ordered.size} material groups, ${ordered.sumOf { it.indexCount } / 3} tris, " +
                "size %.2f x %.2f x %.2f m, ${sil.size / 3} silhouette pts, ${images.size} textures".format(
                    max[0] - min[0], max[1] - min[1], max[2] - min[2]))
            return GlbModel(ordered, images, min, max, sil)
        }

        /** One point per occupied 10 cm voxel: a sparse surface sample whose projected extent is the car's. */
        private fun silhouette(parts: List<FloatArray>, sx: Float, sy: Float, sz: Float): FloatArray {
            val seen = HashSet<Long>()
            val out = ArrayList<Float>()
            val cell = 0.10f
            for (arr in parts) for (k in arr.indices step 3) {
                val x = arr[k] + sx; val y = arr[k + 1] + sy; val z = arr[k + 2] + sz
                val key = ((x / cell).toLong() + 4096) * 67_108_864L + ((y / cell).toLong() + 4096) * 8192L + ((z / cell).toLong() + 4096)
                if (seen.add(key)) { out += x; out += y; out += z }
            }
            return out.toFloatArray()
        }

        private fun localMatrix(n: JSONObject): FloatArray {
            val m = FloatArray(16)
            n.optJSONArray("matrix")?.let { a -> for (i in 0 until 16) m[i] = a.getDouble(i).toFloat(); return m }
            val t = n.optJSONArray("translation")
            val r = n.optJSONArray("rotation")
            val s = n.optJSONArray("scale")
            val tx = t?.getDouble(0)?.toFloat() ?: 0f; val ty = t?.getDouble(1)?.toFloat() ?: 0f; val tz = t?.getDouble(2)?.toFloat() ?: 0f
            val qx = r?.getDouble(0)?.toFloat() ?: 0f; val qy = r?.getDouble(1)?.toFloat() ?: 0f
            val qz = r?.getDouble(2)?.toFloat() ?: 0f; val qw = r?.getDouble(3)?.toFloat() ?: 1f
            val sxx = s?.getDouble(0)?.toFloat() ?: 1f; val syy = s?.getDouble(1)?.toFloat() ?: 1f; val szz = s?.getDouble(2)?.toFloat() ?: 1f
            m[0] = (1 - 2 * (qy * qy + qz * qz)) * sxx; m[1] = 2 * (qx * qy + qz * qw) * sxx; m[2] = 2 * (qx * qz - qy * qw) * sxx
            m[4] = 2 * (qx * qy - qz * qw) * syy; m[5] = (1 - 2 * (qx * qx + qz * qz)) * syy; m[6] = 2 * (qy * qz + qx * qw) * syy
            m[8] = 2 * (qx * qz + qy * qw) * szz; m[9] = 2 * (qy * qz - qx * qw) * szz; m[10] = (1 - 2 * (qx * qx + qy * qy)) * szz
            m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1f
            return m
        }

        private fun det3(m: FloatArray) =
            m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

        /** Inverse-transpose of the upper 3x3, column-major mat3. */
        fun normalMatrix(m: FloatArray): FloatArray {
            val a = m[0]; val b = m[4]; val c = m[8]
            val d = m[1]; val e = m[5]; val f = m[9]
            val g = m[2]; val h = m[6]; val i = m[10]
            val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            val id = if (kotlin.math.abs(det) < 1e-12f) 1f else 1f / det
            // cofactor matrix (= det * inverse-transpose), column-major
            return floatArrayOf(
                (e * i - f * h) * id, -(b * i - c * h) * id, (b * f - c * e) * id,
                -(d * i - f * g) * id, (a * i - c * g) * id, -(a * f - c * d) * id,
                (d * h - e * g) * id, -(a * h - b * g) * id, (a * e - b * d) * id,
            )
        }

        fun identity3() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        /** KHR_texture_transform: T * R * S, column-major mat3. */
        private fun uvTransform(x: JSONObject): FloatArray {
            val o = x.optJSONArray("offset"); val s = x.optJSONArray("scale")
            val ox = o?.getDouble(0)?.toFloat() ?: 0f; val oy = o?.getDouble(1)?.toFloat() ?: 0f
            val sx = s?.getDouble(0)?.toFloat() ?: 1f; val sy = s?.getDouble(1)?.toFloat() ?: 1f
            val r = x.optDouble("rotation", 0.0).toFloat()
            val c = cos(r); val sn = sin(r)
            return floatArrayOf(c * sx, -sn * sx, 0f, sn * sy, c * sy, 0f, ox, oy, 1f)
        }
    }
}
