package com.paintlab.app.model

import android.graphics.ColorMatrix
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Lightroom-style tone and color adjustments, all neutral at 0. */
data class AdjustParams(
    val exposure: Float = 0f,     // stops, -2..2
    val contrast: Float = 0f,     // -1..1
    val saturation: Float = 0f,   // -1..1
    val temperature: Float = 0f,  // -1 (cool) .. 1 (warm)
    val tint: Float = 0f,         // -1 (green) .. 1 (magenta)
    val hue: Float = 0f,          // degrees, -180..180
) {
    val isNeutral: Boolean
        get() = exposure == 0f && contrast == 0f && saturation == 0f &&
            temperature == 0f && tint == 0f && hue == 0f

    fun toColorMatrix(): ColorMatrix {
        val m = ColorMatrix()

        val e = 2f.pow(exposure)
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    e, 0f, 0f, 0f, 0f,
                    0f, e, 0f, 0f, 0f,
                    0f, 0f, e, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )

        val t = temperature * 0.25f
        val g = tint * 0.2f
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f + t, 0f, 0f, 0f, 0f,
                    0f, 1f - g, 0f, 0f, 0f,
                    0f, 0f, 1f - t, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )

        val k = if (contrast >= 0f) 1f + contrast * 1.5f else 1f + contrast * 0.9f
        val off = 128f * (1f - k)
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    k, 0f, 0f, 0f, off,
                    0f, k, 0f, 0f, off,
                    0f, 0f, k, 0f, off,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )

        val s = ColorMatrix()
        s.setSaturation((1f + saturation).coerceAtLeast(0f))
        m.postConcat(s)

        if (hue != 0f) m.postConcat(hueMatrix(hue))
        return m
    }

    private fun hueMatrix(degrees: Float): ColorMatrix {
        val a = Math.toRadians(degrees.toDouble())
        val c = cos(a).toFloat()
        val sn = sin(a).toFloat()
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        return ColorMatrix(
            floatArrayOf(
                lr + c * (1 - lr) + sn * (-lr), lg + c * (-lg) + sn * (-lg), lb + c * (-lb) + sn * (1 - lb), 0f, 0f,
                lr + c * (-lr) + sn * 0.143f, lg + c * (1 - lg) + sn * 0.140f, lb + c * (-lb) + sn * (-0.283f), 0f, 0f,
                lr + c * (-lr) + sn * (-(1 - lr)), lg + c * (-lg) + sn * lg, lb + c * (1 - lb) + sn * lb, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    }
}
