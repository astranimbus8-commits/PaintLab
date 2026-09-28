package com.paintlab.app.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.roundToInt

/** Length units for the canvas size. perInch == null means pixels. */
enum class SizeUnit(val label: String, val perInch: Double?) {
    PX("px", null),
    IN("in", 1.0),
    CM("cm", 2.54),
    MM("mm", 25.4),
    PT("pt", 72.0);
}

object Units {
    const val MAX_SIDE = 8192
    /** Android can't draw a single bitmap over 100 MB (25 MP at 4 bytes/pixel). */
    const val MAX_PIXELS = 25_000_000L

    fun toPx(value: Double, unit: SizeUnit, dpi: Double): Int {
        val perInch = unit.perInch
        val px = if (perInch == null) value else value / perInch * dpi
        return px.roundToInt().coerceIn(1, MAX_SIDE)
    }

    fun fromPx(px: Int, unit: SizeUnit, dpi: Double): Double {
        val perInch = unit.perInch ?: return px.toDouble()
        return px / dpi * perInch
    }

    fun format(v: Double, unit: SizeUnit): String =
        if (unit == SizeUnit.PX) v.roundToInt().toString() else trimNumber(v)

    fun trimNumber(v: Double): String =
        String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}

data class CanvasPreset(
    val name: String,
    val w: Double,
    val h: Double,
    val unit: SizeUnit,
    val dpi: Double,
)

val CANVAS_PRESETS = listOf(
    CanvasPreset("Square 2048", 2048.0, 2048.0, SizeUnit.PX, 72.0),
    CanvasPreset("Phone 1080×1920", 1080.0, 1920.0, SizeUnit.PX, 72.0),
    CanvasPreset("Instagram 4:5", 1080.0, 1350.0, SizeUnit.PX, 72.0),
    CanvasPreset("Full HD", 1920.0, 1080.0, SizeUnit.PX, 72.0),
    CanvasPreset("4K UHD", 3840.0, 2160.0, SizeUnit.PX, 72.0),
    CanvasPreset("Roblox texture", 1024.0, 1024.0, SizeUnit.PX, 72.0),
    CanvasPreset("A4", 21.0, 29.7, SizeUnit.CM, 300.0),
    CanvasPreset("A5", 14.8, 21.0, SizeUnit.CM, 300.0),
    CanvasPreset("US Letter", 8.5, 11.0, SizeUnit.IN, 300.0),
    CanvasPreset("Comic B5", 18.2, 25.7, SizeUnit.CM, 350.0),
    CanvasPreset("Postcard 4×6", 4.0, 6.0, SizeUnit.IN, 300.0),
)

/**
 * Holds a canvas size that can be edited in any unit. Pixels are the source
 * of truth; the text fields show the size in the currently chosen unit.
 * Changing DPI keeps the physical size when a physical unit is selected
 * (pixels change), and keeps pixels when "px" is selected.
 */
class SizeEditorState(initW: Int, initH: Int, initDpi: Double, initUnit: SizeUnit) {
    var widthPx by mutableIntStateOf(initW.coerceIn(1, Units.MAX_SIDE)); private set
    var heightPx by mutableIntStateOf(initH.coerceIn(1, Units.MAX_SIDE)); private set
    var dpi by mutableDoubleStateOf(initDpi); private set
    var unit by mutableStateOf(initUnit); private set
    var widthText by mutableStateOf(""); private set
    var heightText by mutableStateOf(""); private set
    var dpiText by mutableStateOf(Units.trimNumber(initDpi)); private set
    var lockAspect by mutableStateOf(false); private set
    private var aspect = initW.toDouble() / initH

    init {
        refreshTexts()
    }

    private fun refreshTexts() {
        widthText = Units.format(Units.fromPx(widthPx, unit, dpi), unit)
        heightText = Units.format(Units.fromPx(heightPx, unit, dpi), unit)
    }

    fun changeUnit(u: SizeUnit) {
        unit = u
        refreshTexts()
    }

    fun toggleLock() {
        lockAspect = !lockAspect
        aspect = widthPx.toDouble() / heightPx
    }

    fun onWidthText(t: String) {
        widthText = t
        val v = Units.parse(t) ?: return
        if (v <= 0) return
        widthPx = Units.toPx(v, unit, dpi)
        if (lockAspect) {
            heightPx = (widthPx / aspect).roundToInt().coerceIn(1, Units.MAX_SIDE)
            heightText = Units.format(Units.fromPx(heightPx, unit, dpi), unit)
        }
    }

    fun onHeightText(t: String) {
        heightText = t
        val v = Units.parse(t) ?: return
        if (v <= 0) return
        heightPx = Units.toPx(v, unit, dpi)
        if (lockAspect) {
            widthPx = (heightPx * aspect).roundToInt().coerceIn(1, Units.MAX_SIDE)
            widthText = Units.format(Units.fromPx(widthPx, unit, dpi), unit)
        }
    }

    fun onDpiText(t: String) {
        dpiText = t
        val v = Units.parse(t) ?: return
        if (v < 1 || v > 2400) return
        if (unit.perInch != null) {
            val wv = Units.parse(widthText)
            val hv = Units.parse(heightText)
            dpi = v
            if (wv != null && wv > 0) widthPx = Units.toPx(wv, unit, v)
            if (hv != null && hv > 0) heightPx = Units.toPx(hv, unit, v)
        } else {
            dpi = v
        }
    }

    fun swap() {
        val w = widthPx
        widthPx = heightPx
        heightPx = w
        aspect = widthPx.toDouble() / heightPx
        refreshTexts()
    }

    fun set(wPx: Int, hPx: Int, newDpi: Double = dpi, newUnit: SizeUnit = unit) {
        widthPx = wPx.coerceIn(1, Units.MAX_SIDE)
        heightPx = hPx.coerceIn(1, Units.MAX_SIDE)
        dpi = newDpi
        dpiText = Units.trimNumber(newDpi)
        unit = newUnit
        aspect = widthPx.toDouble() / heightPx
        refreshTexts()
    }

    fun applyPreset(p: CanvasPreset) {
        set(Units.toPx(p.w, p.unit, p.dpi), Units.toPx(p.h, p.unit, p.dpi), p.dpi, p.unit)
    }

    val megapixels: Double get() = widthPx.toDouble() * heightPx / 1_000_000.0

    val tooLarge: Boolean get() = widthPx.toLong() * heightPx > Units.MAX_PIXELS

    fun summary(): String {
        val px = "$widthPx × $heightPx px"
        val phys = if (unit == SizeUnit.PX) {
            val w = Units.trimNumber(Units.fromPx(widthPx, SizeUnit.CM, dpi))
            val h = Units.trimNumber(Units.fromPx(heightPx, SizeUnit.CM, dpi))
            "$w × $h cm"
        } else {
            val w = Units.trimNumber(Units.fromPx(widthPx, unit, dpi))
            val h = Units.trimNumber(Units.fromPx(heightPx, unit, dpi))
            "$w × $h ${unit.label}"
        }
        return "$px  ·  $phys @ ${Units.trimNumber(dpi)} dpi"
    }
}
