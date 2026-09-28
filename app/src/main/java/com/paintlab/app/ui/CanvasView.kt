package com.paintlab.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.paintlab.app.EditorViewModel
import com.paintlab.app.Tool
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CanvasView(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val renderer = remember(vm) { CanvasRenderer(vm) }
    androidx.compose.foundation.Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { vm.onViewSize(it.width, it.height) }
            .pointerInput(vm) { canvasGestures(vm) }
    ) {
        // Reading revision subscribes this draw pass to every document change.
        @Suppress("UNUSED_VARIABLE")
        val rev = vm.revision
        drawIntoCanvas { renderer.draw(it.nativeCanvas) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private suspend fun PointerInputScope.canvasGestures(vm: EditorViewModel) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (vm.isBusy) {
            // Swallow the whole gesture while something is processing.
            while (true) {
                val e = awaitPointerEvent()
                e.changes.forEach { it.consume() }
                if (e.changes.none { it.pressed }) break
            }
            return@awaitEachGesture
        }
        down.consume()
        val stylus = down.type == PointerType.Stylus
        vm.pointerDown(down.position, down.pressure, stylus)
        var multi = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                if (!multi) {
                    val up = event.changes.firstOrNull { it.id == down.id }
                    vm.pointerUp(up?.position ?: down.position)
                }
                event.changes.forEach { it.consume() }
                break
            }
            if (pressed.size >= 2) {
                if (!multi) {
                    multi = true
                    vm.pointerCancel()
                }
                vm.twoFinger(
                    event.calculatePan(),
                    event.calculateZoom(),
                    event.calculateRotation(),
                    event.calculateCentroid(useCurrent = true),
                )
            } else if (!multi) {
                val ch = pressed.first()
                for (hc in ch.historical) {
                    vm.pointerMove(hc.position, ch.pressure, stylus, Offset.Zero)
                }
                vm.pointerMove(ch.position, ch.pressure, stylus, ch.position - ch.previousPosition)
            }
            event.changes.forEach { it.consume() }
        }
    }
}

/** Draws the document with the view matrix, plus live previews of the current tool. */
private class CanvasRenderer(private val vm: EditorViewModel) {
    private val checkerTile: Bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        c.drawColor(0xFFFFFFFF.toInt())
        val p = Paint().apply { color = 0xFFD6D6D6.toInt() }
        c.drawRect(0f, 0f, 8f, 8f, p)
        c.drawRect(8f, 8f, 16f, 16f, p)
    }
    private val checkerShader = BitmapShader(checkerTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val checkerPaint = Paint().apply { shader = checkerShader }
    private val shaderMatrix = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val layerPaint = Paint()
    private val strokePaint = Paint()
    private val adjPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstIn = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val selTint = Paint().apply { color = 0x553D8BFF }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val outline2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF3D8BFF.toInt()
        strokeWidth = 3f
    }
    private val shadow = Paint().apply { color = 0x66000000 }

    fun draw(c: Canvas) {
        c.drawColor(0xFF2A2A2E.toInt())
        if (!vm.hasDocument || vm.docWidth <= 0) return
        val w = vm.docWidth.toFloat()
        val h = vm.docHeight.toFloat()
        val docRect = RectF(0f, 0f, w, h)
        val scale = max(0.0001f, vm.viewScale)

        c.save()
        c.concat(vm.viewMatrix)

        // drop shadow + transparency checkerboard (constant size on screen)
        val sh = 6f / scale
        c.drawRect(sh, sh, w + sh, h + sh, shadow)
        shaderMatrix.setScale(1f / scale, 1f / scale)
        checkerShader.setLocalMatrix(shaderMatrix)
        c.drawRect(docRect, checkerPaint)

        bmpPaint.isFilterBitmap = scale < 2.5f
        val active = vm.activeIndex
        val layers = vm.layers
        for (i in layers.indices) {
            val layer = layers[i]
            if (!layer.visible) continue
            val alpha = (layer.opacity * 255f).roundToInt().coerceIn(0, 255)
            if (i == active) drawActive(c, i, alpha, docRect) else {
                bmpPaint.alpha = alpha
                c.drawBitmap(layer.bitmap, 0f, 0f, bmpPaint)
                bmpPaint.alpha = 255
            }
        }

        // Frame divider preview
        val fp = vm.framePreview
        if (vm.frameActive && fp != null) c.drawBitmap(fp, 0f, 0f, bmpPaint)

        // Selection overlay
        val sel = vm.selection
        if (sel != null && vm.transform?.selMask == null && !vm.adjusting) c.drawBitmap(sel, 0f, 0f, selTint)

        // Lasso / rectangle / frame line previews
        val lw = 2f / scale
        outline.strokeWidth = lw * 1.5f
        outline.color = 0xFF000000.toInt()
        outline.pathEffect = null
        outline2.strokeWidth = lw
        outline2.color = 0xFFFFFFFF.toInt()
        outline2.pathEffect = DashPathEffect(floatArrayOf(8f / scale, 6f / scale), 0f)
        if (vm.lassoActive) {
            c.drawPath(vm.lassoPath, outline)
            c.drawPath(vm.lassoPath, outline2)
        }
        val rs = vm.rectStart
        val re = vm.rectEnd
        if (rs != null && re != null) {
            val r = RectF(min(rs.x, re.x), min(rs.y, re.y), max(rs.x, re.x), max(rs.y, re.y))
            c.drawRect(r, outline)
            c.drawRect(r, outline2)
        }
        val fl = vm.frameLine
        if (fl != null) {
            outline.color = 0xFF3D8BFF.toInt()
            outline.strokeWidth = 3f / scale
            c.drawLine(fl.ax, fl.ay, fl.bx, fl.by, outline)
        }

        c.restore()

        // Transform box and handles, in screen space
        val t = vm.transform
        if (t != null && vm.tool == Tool.TRANSFORM) {
            val pts = vm.transformHandles(t)
            vm.viewMatrix.mapPoints(pts)
            handleStroke.strokeWidth = 3f
            val corners = intArrayOf(0, 2, 4, 6)
            for (k in corners.indices) {
                val a = corners[k]
                val b = corners[(k + 1) % 4]
                c.drawLine(pts[a * 2], pts[a * 2 + 1], pts[b * 2], pts[b * 2 + 1], handleStroke)
            }
            for (i in 0 until 8) {
                val r = if (i % 2 == 0) 16f else 12f
                c.drawCircle(pts[i * 2], pts[i * 2 + 1], r, handleFill)
                c.drawCircle(pts[i * 2], pts[i * 2 + 1], r, handleStroke)
            }
        }
    }

    private fun drawActive(c: Canvas, index: Int, alpha: Int, docRect: RectF) {
        val layer = vm.layers[index]
        val t = vm.transform
        val stroke = vm.strokeBitmap
        val sel = vm.selection
        layerPaint.alpha = alpha
        when {
            t != null && t.layer === layer -> {
                c.saveLayer(docRect, layerPaint)
                t.base?.let { c.drawBitmap(it, 0f, 0f, bmpPaint) }
                c.save()
                c.concat(t.matrix)
                c.drawBitmap(t.source, 0f, 0f, adjPaint.apply { colorFilter = null })
                c.restore()
                c.restore()
            }
            vm.isStroking && stroke != null -> {
                c.saveLayer(docRect, layerPaint)
                c.drawBitmap(layer.bitmap, 0f, 0f, bmpPaint)
                strokePaint.alpha = vm.strokeAlpha
                strokePaint.xfermode = if (vm.strokeIsEraser) PorterDuffXfermode(PorterDuff.Mode.DST_OUT) else null
                c.saveLayer(docRect, strokePaint)
                c.drawBitmap(stroke, 0f, 0f, bmpPaint)
                if (sel != null) c.drawBitmap(sel, 0f, 0f, dstIn)
                c.restore()
                c.restore()
            }
            vm.adjusting -> {
                c.saveLayer(docRect, layerPaint)
                c.drawBitmap(layer.bitmap, 0f, 0f, bmpPaint)
                c.saveLayer(docRect, null)
                adjPaint.colorFilter = vm.adjustFilter
                c.drawBitmap(layer.bitmap, 0f, 0f, adjPaint)
                if (sel != null) c.drawBitmap(sel, 0f, 0f, dstIn)
                c.restore()
                c.restore()
            }
            else -> {
                bmpPaint.alpha = alpha
                c.drawBitmap(layer.bitmap, 0f, 0f, bmpPaint)
                bmpPaint.alpha = 255
            }
        }
    }
}
