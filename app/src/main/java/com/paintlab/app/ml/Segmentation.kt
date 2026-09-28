package com.paintlab.app.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What the smart selection can pick. Scene classes are ADE20K ids
 * (1 = wall, 2 = building, 3 = sky, ...; 0 = "other").
 */
enum class SmartKind(val label: String, val classes: IntArray?) {
    SUBJECT("Subject", null),
    BACKGROUND("Background", null),
    SKY("Sky", intArrayOf(3)),
    NATURE("Nature", intArrayOf(5, 10, 14, 17, 18, 30, 35, 47, 67, 69, 73, 95)),
    WATER("Water", intArrayOf(22, 27, 61, 105, 110, 114, 129)),
    BUILDINGS("Buildings", intArrayOf(1, 2, 9, 15, 26, 43, 49, 52, 62, 80, 85, 87)),
    PEOPLE("People", intArrayOf(13)),
    VEHICLES("Vehicles", intArrayOf(21, 77, 81, 84, 91, 103, 104, 117, 128)),
    GROUND("Ground", intArrayOf(4, 7, 12, 53, 55, 92)),
    ANIMALS("Animals", intArrayOf(127)),
}

class FriendlyException(message: String) : Exception(message)

suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/** Google ML Kit subject segmentation: returns foreground confidence per pixel (0..1). */
object SubjectSegmenter {
    suspend fun foreground(bmp: Bitmap): FloatArray {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        try {
            val result = try {
                segmenter.process(InputImage.fromBitmap(bmp, 0)).awaitTask()
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (e.javaClass.simpleName == "MlKitException" &&
                    (msg.contains("download", true) || msg.contains("unavailable", true) || msg.contains("module", true))
                ) {
                    throw FriendlyException(
                        "The subject model is still downloading through Google Play services. " +
                            "Stay online and try again in a minute."
                    )
                }
                throw e
            }
            val buf = result.foregroundConfidenceMask
                ?: throw FriendlyException("No subject found in this image.")
            buf.rewind()
            val n = bmp.width * bmp.height
            val arr = FloatArray(n)
            buf.get(arr, 0, minOf(n, buf.remaining()))
            return arr
        } finally {
            segmenter.close()
        }
    }
}

/** Output of the scene model at its own (low) resolution. */
class SceneResult(
    val width: Int,
    val height: Int,
    val numClasses: Int,
    private val probs: FloatArray?,   // softmax, width*height*numClasses
    private val labels: IntArray?,    // argmax labels, if the model outputs them
) {
    /** Probability (0..1) that each pixel belongs to any of [classes] (ADE20K ids). */
    fun groupProbability(classes: IntArray): FloatArray {
        val n = width * height
        val out = FloatArray(n)
        // 151 outputs -> index == ADE id (0 is "other"); 150 outputs -> index == id - 1
        val offset = if (numClasses == 150) -1 else 0
        if (probs != null) {
            val c = numClasses
            for (i in 0 until n) {
                var s = 0f
                val base = i * c
                for (cls in classes) {
                    val idx = cls + offset
                    if (idx in 0 until c) s += probs[base + idx]
                }
                out[i] = s.coerceIn(0f, 1f)
            }
        } else if (labels != null) {
            for (i in 0 until n) {
                val id = labels[i] - offset
                out[i] = if (classes.contains(id)) 1f else 0f
            }
        }
        return out
    }
}

/**
 * DeepLabV3 ADE20K semantic segmentation via TensorFlow Lite. Reads the model
 * from app storage (if the user loaded one) or from the bundled asset.
 * Input/output shapes and types are read from the model at runtime.
 */
class SceneSegmenter(private val ctx: Context) {
    private var interpreter: Interpreter? = null

    val customFile: File get() = File(ctx.filesDir, "scene_model.tflite")

    fun isAvailable(): Boolean = customFile.exists() || assetExists()

    private fun assetExists(): Boolean = try {
        ctx.assets.open(ASSET).close()
        true
    } catch (e: IOException) {
        false
    }

    @Synchronized
    fun reset() {
        interpreter?.close()
        interpreter = null
    }

    @Synchronized
    private fun interpreter(): Interpreter {
        val existing = interpreter
        if (existing != null) return existing
        val bytes = if (customFile.exists()) customFile.readBytes()
        else ctx.assets.open(ASSET).use { it.readBytes() }
        val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        bb.put(bytes)
        bb.rewind()
        val opts = Interpreter.Options().setNumThreads(4)
        return Interpreter(bb, opts).also { interpreter = it }
    }

    @Synchronized
    fun run(bmp: Bitmap): SceneResult {
        if (!isAvailable()) {
            throw FriendlyException(
                "The scene model isn't included in this build. Load a DeepLabV3 ADE20K .tflite " +
                    "file with ⋮ → Load scene model. Subject and Background work without it."
            )
        }
        val itp = try {
            interpreter()
        } catch (e: Exception) {
            throw FriendlyException("Couldn't load the scene model: ${e.message}")
        }
        val inT = itp.getInputTensor(0)
        val ishape = inT.shape()
        if (ishape.size != 4 || ishape[3] != 3) throw FriendlyException("Unsupported scene model input.")
        val ih = ishape[1]
        val iw = ishape[2]
        val scaled = Bitmap.createScaledBitmap(bmp, iw, ih, true)
        val px = IntArray(iw * ih)
        scaled.getPixels(px, 0, iw, 0, 0, iw, ih)

        val input: ByteBuffer = when (inT.dataType()) {
            DataType.FLOAT32 -> {
                val b = ByteBuffer.allocateDirect(iw * ih * 3 * 4).order(ByteOrder.nativeOrder())
                for (p in px) {
                    b.putFloat(((p shr 16) and 0xFF) / 127.5f - 1f)
                    b.putFloat(((p shr 8) and 0xFF) / 127.5f - 1f)
                    b.putFloat((p and 0xFF) / 127.5f - 1f)
                }
                b
            }
            DataType.UINT8 -> {
                val b = ByteBuffer.allocateDirect(iw * ih * 3).order(ByteOrder.nativeOrder())
                for (p in px) {
                    b.put(((p shr 16) and 0xFF).toByte())
                    b.put(((p shr 8) and 0xFF).toByte())
                    b.put((p and 0xFF).toByte())
                }
                b
            }
            else -> throw FriendlyException("Unsupported scene model input type.")
        }
        input.rewind()

        val outT = itp.getOutputTensor(0)
        val oshape = outT.shape()
        val out = ByteBuffer.allocateDirect(outT.numBytes()).order(ByteOrder.nativeOrder())
        itp.run(input, out)
        out.rewind()

        return when (oshape.size) {
            4 -> {
                val oh = oshape[1]
                val ow = oshape[2]
                val c = oshape[3]
                val n = oh * ow * c
                val logits = FloatArray(n)
                when (outT.dataType()) {
                    DataType.FLOAT32 -> out.asFloatBuffer().get(logits)
                    DataType.UINT8 -> {
                        val q = outT.quantizationParams()
                        for (i in 0 until n) logits[i] = ((out.get(i).toInt() and 0xFF) - q.zeroPoint) * q.scale
                    }
                    DataType.INT8 -> {
                        val q = outT.quantizationParams()
                        for (i in 0 until n) logits[i] = (out.get(i).toInt() - q.zeroPoint) * q.scale
                    }
                    else -> throw FriendlyException("Unsupported scene model output type.")
                }
                softmaxInPlace(logits, oh * ow, c)
                SceneResult(ow, oh, c, logits, null)
            }
            3 -> {
                val oh = oshape[1]
                val ow = oshape[2]
                val n = oh * ow
                val labels = IntArray(n)
                when (outT.dataType()) {
                    DataType.INT32 -> out.asIntBuffer().get(labels)
                    DataType.INT64 -> {
                        val lb = out.asLongBuffer()
                        for (i in 0 until n) labels[i] = lb.get(i).toInt()
                    }
                    else -> throw FriendlyException("Unsupported scene model output type.")
                }
                SceneResult(ow, oh, 151, null, labels)
            }
            else -> throw FriendlyException("Unsupported scene model output.")
        }
    }

    private fun softmaxInPlace(a: FloatArray, pixels: Int, c: Int) {
        for (i in 0 until pixels) {
            val base = i * c
            var m = a[base]
            for (k in 1 until c) if (a[base + k] > m) m = a[base + k]
            var sum = 0f
            for (k in 0 until c) {
                val e = exp(a[base + k] - m)
                a[base + k] = e
                sum += e
            }
            for (k in 0 until c) a[base + k] /= sum
        }
    }

    companion object {
        const val ASSET = "scene_model.tflite"
    }
}

object MaskMath {
    /**
     * Joint bilateral upsampling: turns a coarse probability map into a
     * high-res one whose edges follow the edges of the guide image.
     */
    fun jointBilateralUpsample(
        low: FloatArray, lw: Int, lh: Int,
        guideLow: IntArray,
        guideHi: IntArray, hw: Int, hh: Int,
    ): FloatArray {
        val out = FloatArray(hw * hh)
        val sigmaR = 22f
        val rangeLut = FloatArray(766) { d ->
            val v = d / 3f
            exp(-(v * v) / (2f * sigmaR * sigmaR))
        }
        val sx = lw.toFloat() / hw
        val sy = lh.toFloat() / hh
        for (y in 0 until hh) {
            val ly = (y + 0.5f) * sy - 0.5f
            val cy = ly.roundToInt()
            for (x in 0 until hw) {
                val lx = (x + 0.5f) * sx - 0.5f
                val cx = lx.roundToInt()
                val g = guideHi[y * hw + x]
                val gr = (g shr 16) and 0xFF
                val gg = (g shr 8) and 0xFF
                val gb = g and 0xFF
                var sw = 0f
                var sv = 0f
                for (dy in -2..2) {
                    val qy = cy + dy
                    if (qy < 0 || qy >= lh) continue
                    val fy = qy - ly
                    for (dx in -2..2) {
                        val qx = cx + dx
                        if (qx < 0 || qx >= lw) continue
                        val fx = qx - lx
                        val ws = exp(-(fx * fx + fy * fy) / 2f)
                        val q = guideLow[qy * lw + qx]
                        val diff = abs(gr - ((q shr 16) and 0xFF)) +
                            abs(gg - ((q shr 8) and 0xFF)) +
                            abs(gb - (q and 0xFF))
                        val wgt = ws * rangeLut[diff]
                        sw += wgt
                        sv += wgt * low[qy * lw + qx]
                    }
                }
                out[y * hw + x] = if (sw > 1e-6f) sv / sw
                else low[cy.coerceIn(0, lh - 1) * lw + cx.coerceIn(0, lw - 1)]
            }
        }
        return out
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Converts probabilities to an ARGB bitmap whose alpha is the mask. */
    fun toAlphaBitmap(p: FloatArray, w: Int, h: Int, sharpen: Boolean): Bitmap {
        val px = IntArray(w * h)
        for (i in px.indices) {
            val v = if (sharpen) smoothstep(0.35f, 0.65f, p[i]) else p[i].coerceIn(0f, 1f)
            px[i] = (max(0, (v * 255f).roundToInt()) shl 24)
        }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }
}
