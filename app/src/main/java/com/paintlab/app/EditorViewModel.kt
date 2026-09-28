package com.paintlab.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paintlab.app.frames.FrameDivider
import com.paintlab.app.frames.Poly
import com.paintlab.app.frames.Seg
import com.paintlab.app.io.ExportFormat
import com.paintlab.app.io.ImageIO
import com.paintlab.app.ml.FriendlyException
import com.paintlab.app.ml.MaskMath
import com.paintlab.app.ml.SceneResult
import com.paintlab.app.ml.SceneSegmenter
import com.paintlab.app.ml.SmartKind
import com.paintlab.app.ml.SubjectSegmenter
import com.paintlab.app.model.AdjustParams
import com.paintlab.app.model.FloodFill
import com.paintlab.app.model.SizeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Tool(val label: String) {
    BRUSH("Brush"),
    ERASER("Eraser"),
    FILL("Fill"),
    EYEDROPPER("Picker"),
    SELECT("Select"),
    TRANSFORM("Transform"),
    FRAME("Frames"),
    HAND("Move view"),
}

enum class SelectShape { LASSO, RECT }
enum class SelectMode { NEW, ADD, SUBTRACT }

class Layer(name: String, var bitmap: Bitmap) {
    val id: Long = nextId++
    var name by mutableStateOf(name)
    var visible by mutableStateOf(true)
    var opacity by mutableFloatStateOf(1f)

    private companion object {
        var nextId = 1L
    }
}

private class LayerState(
    val layer: Layer,
    val bitmap: Bitmap,
    val name: String,
    val visible: Boolean,
    val opacity: Float,
)

/**
 * Undo history. Layer bitmaps are never modified in place: every edit makes a
 * new bitmap and the old one goes into history, so any snapshot stays valid.
 */
private sealed class UndoEntry {
    abstract val bytes: Long

    class Pixels(val layer: Layer, val bitmap: Bitmap) : UndoEntry() {
        override val bytes: Long = bitmap.allocationByteCount.toLong()
    }

    class Structure(
        val states: List<LayerState>,
        val w: Int,
        val h: Int,
        val dpi: Double,
        val active: Int,
    ) : UndoEntry() {
        override val bytes: Long = states.sumOf { it.bitmap.allocationByteCount.toLong() } / 3
    }
}

class TransformSession(
    val layer: Layer,
    val source: Bitmap,
    val base: Bitmap?,
    val bounds: RectF,
    val selMask: Bitmap?,
) {
    val matrix = Matrix()
}

private class ProbCache(val version: Long, val w: Int, val h: Int, val data: FloatArray)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    // ---------------------------------------------------------------- document
    var hasDocument by mutableStateOf(false); private set
    var docWidth by mutableIntStateOf(0); private set
    var docHeight by mutableIntStateOf(0); private set
    var docDpi by mutableDoubleStateOf(300.0); private set
    var docUnit by mutableStateOf(SizeUnit.PX)
    val layers = mutableStateListOf<Layer>()           // index 0 = bottom
    var activeIndex by mutableIntStateOf(0); private set
    val activeLayer: Layer? get() = layers.getOrNull(activeIndex)

    /** Bumped whenever something visible changes; the canvas redraws on it. */
    var revision by mutableIntStateOf(0); private set
    private var contentVersion = 0L
    private var layerCounter = 1

    var busyMessage by mutableStateOf<String?>(null); private set
    val isBusy: Boolean get() = busyMessage != null
    var message by mutableStateOf<String?>(null)

    fun invalidate(contentChanged: Boolean = false) {
        revision++
        if (contentChanged) contentVersion++
    }

    // ---------------------------------------------------------------- tools
    var tool by mutableStateOf(Tool.BRUSH); private set
    var brushColor by mutableIntStateOf(Color.BLACK)
    var brushSize by mutableFloatStateOf(12f)
    var brushOpacity by mutableFloatStateOf(1f)
    var brushHardness by mutableFloatStateOf(1f)
    var eraserSize by mutableFloatStateOf(40f)
    var eraserOpacity by mutableFloatStateOf(1f)
    var eraserHardness by mutableFloatStateOf(1f)
    var fillTolerance by mutableFloatStateOf(32f)
    var allowViewRotation by mutableStateOf(true)
    val recentColors = mutableStateListOf<Int>()

    // ---------------------------------------------------------------- selection
    var selectShape by mutableStateOf(SelectShape.LASSO)
    var selectMode by mutableStateOf(SelectMode.NEW)
    var selection: Bitmap? = null; private set
    var hasSelection by mutableStateOf(false); private set
    val lassoPath = Path()
    var lassoActive = false; private set
    var rectStart: PointF? = null; private set
    var rectEnd: PointF? = null; private set

    // ---------------------------------------------------------------- adjust
    var adjusting by mutableStateOf(false); private set
    var adjust by mutableStateOf(AdjustParams()); private set
    var adjustFilter: ColorMatrixColorFilter = ColorMatrixColorFilter(AdjustParams().toColorMatrix()); private set

    // ---------------------------------------------------------------- transform
    var transform: TransformSession? by mutableStateOf(null); private set
    private var transformDrag = -1 // -1 none, 0..7 handle, 8 move

    // ---------------------------------------------------------------- frames
    var frameActive by mutableStateOf(false); private set
    var frameRows by mutableIntStateOf(1); private set
    var frameCols by mutableIntStateOf(1); private set
    var frameGutter by mutableFloatStateOf(40f); private set
    var frameBorder by mutableFloatStateOf(8f); private set
    var frameMargin by mutableFloatStateOf(80f); private set
    var frameSnap by mutableStateOf(true)
    var frameWhiteGutters by mutableStateOf(true); private set
    val frameSplits = mutableStateListOf<Seg>()
    var framePreview: Bitmap? = null; private set
    var framePanels: List<Poly> = emptyList(); private set
    var frameLine: Seg? = null; private set

    // ---------------------------------------------------------------- stroke
    var strokeBitmap: Bitmap? = null; private set
    var isStroking = false; private set
    var strokeIsEraser = false; private set
    var strokeAlpha = 255; private set
    private var strokeCanvas: Canvas? = null
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val strokePath = Path()
    private var lastX = 0f
    private var lastY = 0f
    private var midX = 0f
    private var midY = 0f
    private var strokeBaseWidth = 1f

    // ---------------------------------------------------------------- view
    val viewMatrix = Matrix()
    private var viewW = 0
    private var viewH = 0
    private var viewFitted = false

    // ---------------------------------------------------------------- undo
    private val undoStack = ArrayDeque<UndoEntry>()
    private val redoStack = ArrayDeque<UndoEntry>()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private val undoBudgetBytes = 320L * 1024 * 1024

    // ---------------------------------------------------------------- ML
    val sceneSegmenter = SceneSegmenter(app)
    private var subjectCache: ProbCache? = null
    private var sceneCache: Pair<Long, SceneResult>? = null

    private val dstIn = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val dstOut = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // =====================================================================
    // Document lifecycle
    // =====================================================================

    fun newDocument(w: Int, h: Int, dpi: Double, unit: SizeUnit, background: Int?, picture: Bitmap?) {
        resetAll()
        docWidth = w
        docHeight = h
        docDpi = dpi
        docUnit = unit
        val bg = blankBitmap()
        if (background != null) bg.eraseColor(background)
        layers.add(Layer("Background", bg))
        if (picture != null) {
            val pic = blankBitmap()
            val fit = min(w / picture.width.toFloat(), h / picture.height.toFloat())
            val m = Matrix().apply {
                postScale(fit, fit)
                postTranslate((w - picture.width * fit) / 2f, (h - picture.height * fit) / 2f)
            }
            Canvas(pic).drawBitmap(picture, m, filterPaint)
            layers.add(Layer("Picture", pic))
        }
        layers.add(Layer("Layer ${layerCounter++}", blankBitmap()))
        activeIndex = layers.lastIndex
        val minSide = min(w, h).toFloat()
        frameGutter = (minSide * 0.025f).coerceAtLeast(2f)
        frameBorder = (minSide * 0.005f).coerceAtLeast(1f)
        frameMargin = (minSide * 0.05f)
        hasDocument = true
        viewFitted = false
        fitView()
        invalidate(true)
    }

    fun closeDocument() {
        resetAll()
        hasDocument = false
    }

    private fun resetAll() {
        layers.clear()
        undoStack.clear()
        redoStack.clear()
        updateUndoFlags()
        selection = null
        hasSelection = false
        transform = null
        adjusting = false
        frameActive = false
        frameSplits.clear()
        framePreview = null
        strokeBitmap = null
        strokeCanvas = null
        isStroking = false
        subjectCache = null
        sceneCache = null
        tool = Tool.BRUSH
        layerCounter = 1
    }

    fun blankBitmap(): Bitmap = Bitmap.createBitmap(docWidth, docHeight, Bitmap.Config.ARGB_8888)

    private fun newMask(): Bitmap = Bitmap.createBitmap(docWidth, docHeight, Bitmap.Config.ALPHA_8)

    /** Makes an edited copy of the layer's bitmap and records the old one for undo. */
    private inline fun editLayer(layer: Layer, block: (Canvas, Bitmap) -> Unit) {
        val nb = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        block(Canvas(nb), nb)
        pushUndo(UndoEntry.Pixels(layer, layer.bitmap))
        layer.bitmap = nb
        invalidate(true)
    }

    // =====================================================================
    // Undo / redo
    // =====================================================================

    private fun pushUndo(e: UndoEntry) {
        undoStack.addLast(e)
        redoStack.clear()
        var total = undoStack.sumOf { it.bytes }
        while (undoStack.size > 1 && (undoStack.size > 40 || total > undoBudgetBytes)) {
            total -= undoStack.removeFirst().bytes
        }
        updateUndoFlags()
    }

    private fun updateUndoFlags() {
        canUndo = undoStack.isNotEmpty() || (frameActive && frameSplits.isNotEmpty())
        canRedo = redoStack.isNotEmpty() && !frameActive
    }

    private fun captureStructure() = UndoEntry.Structure(
        layers.map { LayerState(it, it.bitmap, it.name, it.visible, it.opacity) },
        docWidth, docHeight, docDpi, activeIndex,
    )

    private fun pushStructure() = pushUndo(captureStructure())

    private fun restoreStructure(s: UndoEntry.Structure) {
        val sizeChanged = s.w != docWidth || s.h != docHeight
        layers.clear()
        for (st in s.states) {
            st.layer.bitmap = st.bitmap
            st.layer.name = st.name
            st.layer.visible = st.visible
            st.layer.opacity = st.opacity
            layers.add(st.layer)
        }
        docWidth = s.w
        docHeight = s.h
        docDpi = s.dpi
        activeIndex = s.active.coerceIn(0, max(0, layers.lastIndex))
        if (sizeChanged) {
            clearSelectionInternal()
            fitView()
        }
    }

    private fun applyEntry(e: UndoEntry): UndoEntry = when (e) {
        is UndoEntry.Pixels -> {
            val inverse = UndoEntry.Pixels(e.layer, e.layer.bitmap)
            e.layer.bitmap = e.bitmap
            inverse
        }
        is UndoEntry.Structure -> {
            val inverse = captureStructure()
            restoreStructure(e)
            inverse
        }
    }

    fun undo() {
        if (isBusy) return
        if (frameActive) {
            frameUndoSplit()
            return
        }
        cancelTransientSessions()
        val e = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(applyEntry(e))
        updateUndoFlags()
        invalidate(true)
    }

    fun redo() {
        if (isBusy || frameActive) return
        cancelTransientSessions()
        val e = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(applyEntry(e))
        updateUndoFlags()
        invalidate(true)
    }

    private fun cancelTransientSessions() {
        if (transform != null) cancelTransform()
        if (adjusting) cancelAdjust()
        strokeCancel()
    }

    // =====================================================================
    // View (pan / zoom / rotate)
    // =====================================================================

    fun onViewSize(w: Int, h: Int) {
        if (w == viewW && h == viewH) return
        viewW = w
        viewH = h
        fitView()
    }

    fun fitView() {
        if (viewW <= 0 || viewH <= 0 || docWidth <= 0) return
        val s = min(viewW * 0.92f / docWidth, viewH * 0.92f / docHeight)
        viewMatrix.reset()
        viewMatrix.postScale(s, s)
        viewMatrix.postTranslate((viewW - docWidth * s) / 2f, (viewH - docHeight * s) / 2f)
        viewFitted = true
        invalidate()
    }

    val viewScale: Float
        get() {
            val v = FloatArray(9)
            viewMatrix.getValues(v)
            return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
        }

    fun screenToDoc(o: Offset): PointF {
        val inv = Matrix()
        viewMatrix.invert(inv)
        val p = floatArrayOf(o.x, o.y)
        inv.mapPoints(p)
        return PointF(p[0], p[1])
    }

    private fun screenVectorToDoc(v: Offset): PointF {
        val inv = Matrix()
        viewMatrix.invert(inv)
        val p = floatArrayOf(v.x, v.y)
        inv.mapVectors(p)
        return PointF(p[0], p[1])
    }

    fun navigate(pan: Offset, zoom: Float, rotationDeg: Float, centroid: Offset) {
        val s = viewScale
        val target = (s * zoom).coerceIn(0.02f, 64f)
        val z = if (s > 0f) target / s else 1f
        viewMatrix.postTranslate(pan.x, pan.y)
        if (centroid.isSpecifiedSafe()) {
            viewMatrix.postScale(z, z, centroid.x, centroid.y)
            if (allowViewRotation && rotationDeg != 0f) viewMatrix.postRotate(rotationDeg, centroid.x, centroid.y)
        }
        invalidate()
    }

    private fun Offset.isSpecifiedSafe(): Boolean = this != Offset.Unspecified && !x.isNaN() && !y.isNaN()

    // =====================================================================
    // Pointer routing (called from the canvas gesture handler)
    // =====================================================================

    private var downScreen = Offset.Zero
    private var lastDoc = PointF()
    private var dragMoved = false

    fun pointerDown(pos: Offset, pressure: Float, stylus: Boolean) {
        val d = screenToDoc(pos)
        downScreen = pos
        lastDoc = d
        dragMoved = false
        if (adjusting && tool != Tool.HAND) return
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> strokeBegin(d, pressure, stylus)
            Tool.EYEDROPPER -> pickColorAt(d)
            Tool.SELECT -> selectionBegin(d)
            Tool.TRANSFORM -> transformDown(pos)
            Tool.FRAME -> frameLineBegin(d)
            Tool.FILL, Tool.HAND -> Unit
        }
    }

    fun pointerMove(pos: Offset, pressure: Float, stylus: Boolean, screenDelta: Offset) {
        val d = screenToDoc(pos)
        if ((pos - downScreen).getDistance() > 12f) dragMoved = true
        if (adjusting && tool != Tool.HAND) {
            lastDoc = d
            return
        }
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> strokeMove(d, pressure, stylus)
            Tool.EYEDROPPER -> pickColorAt(d)
            Tool.SELECT -> selectionMove(d)
            Tool.TRANSFORM -> transformMove(d)
            Tool.FRAME -> frameLineMove(d)
            Tool.HAND -> if (screenDelta != Offset.Zero) navigate(screenDelta, 1f, 0f, pos)
            Tool.FILL -> Unit
        }
        lastDoc = d
    }

    fun pointerUp(pos: Offset) {
        val d = screenToDoc(pos)
        if (adjusting && tool != Tool.HAND) return
        when (tool) {
            Tool.BRUSH, Tool.ERASER -> strokeEnd()
            Tool.SELECT -> selectionEnd(dragMoved)
            Tool.FILL -> if (!dragMoved) floodFill(d)
            Tool.FRAME -> frameLineEnd()
            Tool.TRANSFORM -> transformDrag = -1
            Tool.EYEDROPPER -> addRecentColor(brushColor)
            Tool.HAND -> Unit
        }
    }

    /** A second finger arrived: abandon whatever the first finger started. */
    fun pointerCancel() {
        strokeCancel()
        lassoActive = false
        rectStart = null
        rectEnd = null
        frameLine = null
        transformDrag = -1
        dragMoved = true
        invalidate()
    }

    fun twoFinger(pan: Offset, zoom: Float, rotation: Float, centroid: Offset) {
        val t = transform
        if (tool == Tool.TRANSFORM && t != null && centroid.isSpecifiedSafe()) {
            val v = screenVectorToDoc(pan)
            val c = screenToDoc(centroid)
            t.matrix.postTranslate(v.x, v.y)
            t.matrix.postScale(zoom, zoom, c.x, c.y)
            t.matrix.postRotate(rotation, c.x, c.y)
            invalidate()
        } else {
            navigate(pan, zoom, rotation, centroid)
        }
    }

    // =====================================================================
    // Tool switching
    // =====================================================================

    fun selectTool(t: Tool) {
        if (isBusy) return
        if (t == tool) {
            if (t == Tool.TRANSFORM && transform == null) beginTransform()
            if (t == Tool.FRAME && !frameActive) beginFrame()
            return
        }
        when (tool) {
            Tool.TRANSFORM -> applyTransform()
            Tool.FRAME -> if (frameActive) {
                if (frameSplits.isNotEmpty() || frameRows > 1 || frameCols > 1) frameDone() else frameCancel()
            }
            else -> Unit
        }
        if (adjusting) cancelAdjust()
        strokeCancel()
        lassoActive = false
        tool = t
        when (t) {
            Tool.TRANSFORM -> beginTransform()
            Tool.FRAME -> beginFrame()
            else -> Unit
        }
        invalidate()
    }

    // =====================================================================
    // Brush / eraser
    // =====================================================================

    private fun ensureStrokeBitmap(): Bitmap {
        val b = strokeBitmap
        if (b != null && b.width == docWidth && b.height == docHeight) return b
        val nb = Bitmap.createBitmap(docWidth, docHeight, Bitmap.Config.ARGB_8888)
        strokeBitmap = nb
        strokeCanvas = Canvas(nb)
        return nb
    }

    private fun widthFor(pressure: Float, stylus: Boolean): Float =
        if (stylus) strokeBaseWidth * (0.15f + 0.85f * pressure.coerceIn(0f, 1f)) else strokeBaseWidth

    private fun strokeBegin(p: PointF, pressure: Float, stylus: Boolean) {
        val layer = activeLayer ?: return
        if (!layer.visible) {
            message = "The current layer is hidden."
            return
        }
        val eraser = tool == Tool.ERASER
        val b = ensureStrokeBitmap()
        b.eraseColor(Color.TRANSPARENT)
        strokeIsEraser = eraser
        val size = if (eraser) eraserSize else brushSize
        val hardness = if (eraser) eraserHardness else brushHardness
        val opacity = if (eraser) eraserOpacity else brushOpacity
        strokeAlpha = (opacity * 255f).roundToInt().coerceIn(0, 255)
        strokeBaseWidth = size
        strokePaint.color = if (eraser) Color.BLACK else (brushColor or 0xFF000000.toInt())
        val blur = size * 0.5f * (1f - hardness)
        strokePaint.maskFilter = if (blur >= 0.5f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
        strokePaint.strokeWidth = widthFor(pressure, stylus)
        strokeCanvas?.drawPoint(p.x, p.y, strokePaint)
        lastX = p.x
        lastY = p.y
        midX = p.x
        midY = p.y
        isStroking = true
        invalidate()
    }

    private fun strokeMove(p: PointF, pressure: Float, stylus: Boolean) {
        if (!isStroking) return
        if (hypot(p.x - lastX, p.y - lastY) < 0.75f) return
        val mx = (lastX + p.x) / 2f
        val my = (lastY + p.y) / 2f
        strokePath.reset()
        strokePath.moveTo(midX, midY)
        strokePath.quadTo(lastX, lastY, mx, my)
        strokePaint.strokeWidth = widthFor(pressure, stylus)
        strokeCanvas?.drawPath(strokePath, strokePaint)
        lastX = p.x
        lastY = p.y
        midX = mx
        midY = my
        invalidate()
    }

    private fun strokeEnd() {
        if (!isStroking) return
        strokePath.reset()
        strokePath.moveTo(midX, midY)
        strokePath.lineTo(lastX, lastY)
        strokeCanvas?.drawPath(strokePath, strokePaint)
        val b = strokeBitmap
        isStroking = false
        if (b != null) commitOverlay(b, strokeAlpha, strokeIsEraser)
        invalidate()
    }

    private fun strokeCancel() {
        if (!isStroking) return
        isStroking = false
        invalidate()
    }

    /** Merges an overlay (stroke or fill) into the active layer, clipped to the selection. */
    private fun commitOverlay(overlay: Bitmap, alpha: Int, eraser: Boolean) {
        val layer = activeLayer ?: return
        val sel = selection
        editLayer(layer) { c, _ ->
            val p = Paint().apply {
                this.alpha = alpha
                if (eraser) xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            }
            c.saveLayer(null, p)
            c.drawBitmap(overlay, 0f, 0f, null)
            if (sel != null) c.drawBitmap(sel, 0f, 0f, dstIn)
            c.restore()
        }
    }

    // =====================================================================
    // Color picking & fill
    // =====================================================================

    fun sampleColor(x: Int, y: Int): Int? {
        if (x !in 0 until docWidth || y !in 0 until docHeight) return null
        var r = 0f
        var g = 0f
        var b = 0f
        var a = 0f
        for (l in layers) {
            if (!l.visible) continue
            val c = l.bitmap.getPixel(x, y)
            val sa = Color.alpha(c) / 255f * l.opacity
            if (sa <= 0f) continue
            r = Color.red(c) / 255f * sa + r * (1f - sa)
            g = Color.green(c) / 255f * sa + g * (1f - sa)
            b = Color.blue(c) / 255f * sa + b * (1f - sa)
            a = sa + a * (1f - sa)
        }
        if (a <= 0.001f) return null
        return Color.rgb(
            (r / a * 255f).roundToInt().coerceIn(0, 255),
            (g / a * 255f).roundToInt().coerceIn(0, 255),
            (b / a * 255f).roundToInt().coerceIn(0, 255),
        )
    }

    private fun pickColorAt(p: PointF) {
        sampleColor(p.x.toInt(), p.y.toInt())?.let { brushColor = it }
    }

    fun addRecentColor(c: Int) {
        recentColors.remove(c)
        recentColors.add(0, c)
        while (recentColors.size > 12) recentColors.removeAt(recentColors.lastIndex)
    }

    private fun floodFill(p: PointF) {
        val layer = activeLayer ?: return
        val x = p.x.toInt()
        val y = p.y.toInt()
        if (x !in 0 until docWidth || y !in 0 until docHeight) return
        if (!layer.visible) {
            message = "The current layer is hidden."
            return
        }
        val snapshot = layers.filter { it.visible }.map { it.bitmap to it.opacity }
        val w = docWidth
        val h = docHeight
        val color = brushColor or 0xFF000000.toInt()
        val tol = fillTolerance.roundToInt()
        val alpha = (brushOpacity * 255f).roundToInt()
        busyMessage = "Filling…"
        viewModelScope.launch {
            try {
                val overlay = withContext(Dispatchers.Default) {
                    val ref = flatten(snapshot, w, h, 1f, null)
                    val px = IntArray(w * h)
                    ref.getPixels(px, 0, w, 0, 0, w, h)
                    val mask = FloodFill.mask(px, w, h, x, y, tol)
                    for (i in px.indices) px[i] = if (mask[i].toInt() != 0) color else 0
                    val o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    o.setPixels(px, 0, w, 0, 0, w, h)
                    o
                }
                if (w == docWidth && h == docHeight) {
                    commitOverlay(overlay, alpha, false)
                    addRecentColor(color)
                }
            } catch (e: OutOfMemoryError) {
                message = "Not enough memory to fill this canvas."
            } catch (e: Exception) {
                message = "Fill failed: ${e.message}"
            } finally {
                busyMessage = null
            }
        }
    }

    // =====================================================================
    // Flatten / export
    // =====================================================================

    private fun flatten(
        list: List<Pair<Bitmap, Float>>,
        w: Int, h: Int,
        scale: Float,
        background: Int?,
    ): Bitmap {
        val ow = max(1, (w * scale).roundToInt())
        val oh = max(1, (h * scale).roundToInt())
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (background != null) c.drawColor(background)
        c.scale(ow / w.toFloat(), oh / h.toFloat())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((bmp, opacity) in list) {
            p.alpha = (opacity * 255f).roundToInt()
            c.drawBitmap(bmp, 0f, 0f, p)
        }
        return out
    }

    private fun flattenMax(list: List<Pair<Bitmap, Float>>, w: Int, h: Int, maxSide: Int, bg: Int?): Bitmap {
        val s = min(1f, maxSide / max(w, h).toFloat())
        return flatten(list, w, h, s, bg)
    }

    private fun visibleSnapshot() = layers.filter { it.visible }.map { it.bitmap to it.opacity }

    fun defaultExportName(): String =
        "PaintLab_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private suspend fun renderForExport(format: ExportFormat, scale: Float, transparent: Boolean): Bitmap {
        val snap = visibleSnapshot()
        val w = docWidth
        val h = docHeight
        val bg = if (format == ExportFormat.PNG && transparent) null else Color.WHITE
        return withContext(Dispatchers.Default) { flatten(snap, w, h, scale, bg) }
    }

    fun exportToUri(uri: Uri, format: ExportFormat, scale: Float, transparent: Boolean, jpgQuality: Int) {
        if (isBusy) return
        busyMessage = "Exporting ${format.label}…"
        viewModelScope.launch {
            try {
                val bmp = renderForExport(format, scale, transparent)
                val dpi = docDpi * scale
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        ImageIO.write(it, bmp, format, jpgQuality, dpi)
                    } ?: error("Couldn't open the file")
                }
                message = "Saved ${format.label} (${bmp.width} × ${bmp.height} px)"
            } catch (e: OutOfMemoryError) {
                message = "Not enough memory. Try a smaller export size."
            } catch (e: Exception) {
                message = "Export failed: ${e.message}"
            } finally {
                busyMessage = null
            }
        }
    }

    fun quickSave(format: ExportFormat, scale: Float, transparent: Boolean, jpgQuality: Int) {
        if (isBusy) return
        busyMessage = "Saving ${format.label}…"
        viewModelScope.launch {
            try {
                val bmp = renderForExport(format, scale, transparent)
                val dpi = docDpi * scale
                val where = withContext(Dispatchers.IO) {
                    ImageIO.saveToMediaStore(getApplication<Application>(), bmp, format, jpgQuality, dpi, defaultExportName())
                }
                message = "Saved to $where"
            } catch (e: OutOfMemoryError) {
                message = "Not enough memory. Try a smaller export size."
            } catch (e: Exception) {
                message = "Save failed: ${e.message}"
            } finally {
                busyMessage = null
            }
        }
    }

    // =====================================================================
    // Layers
    // =====================================================================

    private fun finishSessionsBeforeLayerChange() {
        if (transform != null) applyTransform()
        if (adjusting) cancelAdjust()
        if (frameActive) frameCancel()
        strokeCancel()
    }

    fun setActive(i: Int) {
        if (i == activeIndex || i !in layers.indices) return
        val wasTransform = transform != null
        finishSessionsBeforeLayerChange()
        activeIndex = i
        if (wasTransform && tool == Tool.TRANSFORM) beginTransform()
        invalidate()
    }

    fun addLayer() {
        finishSessionsBeforeLayerChange()
        pushStructure()
        layers.add(activeIndex + 1, Layer("Layer ${layerCounter++}", blankBitmap()))
        activeIndex += 1
        invalidate(true)
    }

    fun duplicateLayer() {
        val l = activeLayer ?: return
        finishSessionsBeforeLayerChange()
        pushStructure()
        val copy = Layer("${l.name} copy", l.bitmap.copy(Bitmap.Config.ARGB_8888, true))
        copy.opacity = l.opacity
        layers.add(activeIndex + 1, copy)
        activeIndex += 1
        invalidate(true)
    }

    fun deleteLayer() {
        if (layers.size <= 1) {
            message = "A canvas needs at least one layer."
            return
        }
        finishSessionsBeforeLayerChange()
        pushStructure()
        layers.removeAt(activeIndex)
        activeIndex = activeIndex.coerceAtMost(layers.lastIndex)
        invalidate(true)
    }

    fun moveLayer(up: Boolean) {
        val to = if (up) activeIndex + 1 else activeIndex - 1
        if (to !in layers.indices) return
        finishSessionsBeforeLayerChange()
        pushStructure()
        val l = layers.removeAt(activeIndex)
        layers.add(to, l)
        activeIndex = to
        invalidate(true)
    }

    fun mergeDown() {
        if (activeIndex <= 0) return
        finishSessionsBeforeLayerChange()
        val upper = layers[activeIndex]
        val lower = layers[activeIndex - 1]
        pushStructure()
        val merged = lower.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (upper.visible) {
            val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (upper.opacity * 255f).roundToInt() }
            Canvas(merged).drawBitmap(upper.bitmap, 0f, 0f, p)
        }
        lower.bitmap = merged
        layers.removeAt(activeIndex)
        activeIndex -= 1
        invalidate(true)
    }

    fun clearLayer() {
        val l = activeLayer ?: return
        finishSessionsBeforeLayerChange()
        val sel = selection
        editLayer(l) { c, _ ->
            if (sel != null) c.drawBitmap(sel, 0f, 0f, dstOut)
            else c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
    }

    fun toggleVisible(l: Layer) {
        l.visible = !l.visible
        invalidate(true)
    }

    fun setOpacity(l: Layer, v: Float) {
        l.opacity = v.coerceIn(0f, 1f)
        invalidate(true)
    }

    fun renameLayer(l: Layer, name: String) {
        if (name.isNotBlank()) l.name = name.trim()
    }

    fun importPicture(uri: Uri) {
        if (!hasDocument || isBusy) return
        busyMessage = "Importing picture…"
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { ImageIO.decode(getApplication<Application>(), uri) }
                finishSessionsBeforeLayerChange()
                pushStructure()
                val fit = min(1f, min(docWidth / bmp.width.toFloat(), docHeight / bmp.height.toFloat()))
                val m = Matrix().apply {
                    postScale(fit, fit)
                    postTranslate((docWidth - bmp.width * fit) / 2f, (docHeight - bmp.height * fit) / 2f)
                }
                val lb = blankBitmap()
                Canvas(lb).drawBitmap(bmp, m, filterPaint)
                val layer = Layer("Picture ${layerCounter++}", lb)
                layers.add(activeIndex + 1, layer)
                activeIndex += 1
                clearSelectionInternal()
                if (adjusting) cancelAdjust()
                tool = Tool.TRANSFORM
                // Keep the full-resolution picture as the transform source so scaling up stays sharp.
                transform = TransformSession(layer, bmp, null, RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), null)
                    .also { it.matrix.set(m) }
                message = "Picture added. Drag, pinch or use the handles, then tap Apply."
                invalidate(true)
            } catch (e: OutOfMemoryError) {
                message = "That picture is too large to open."
            } catch (e: Exception) {
                message = "Couldn't open the picture: ${e.message}"
            } finally {
                busyMessage = null
            }
        }
    }

    // =====================================================================
    // Canvas size / rotation
    // =====================================================================

    fun resizeCanvas(newW: Int, newH: Int, newDpi: Double, unit: SizeUnit, scaleContent: Boolean) {
        if (newW == docWidth && newH == docHeight) {
            docDpi = newDpi
            docUnit = unit
            return
        }
        finishSessionsBeforeLayerChange()
        pushStructure()
        val ow = docWidth
        val oh = docHeight
        for (l in layers) {
            val nb = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
            val c = Canvas(nb)
            if (scaleContent) {
                c.scale(newW / ow.toFloat(), newH / oh.toFloat())
                c.drawBitmap(l.bitmap, 0f, 0f, filterPaint)
            } else {
                c.drawBitmap(l.bitmap, (newW - ow) / 2f, (newH - oh) / 2f, null)
            }
            l.bitmap = nb
        }
        docWidth = newW
        docHeight = newH
        docDpi = newDpi
        docUnit = unit
        clearSelectionInternal()
        fitView()
        invalidate(true)
    }

    fun rotateCanvas(clockwise: Boolean) {
        finishSessionsBeforeLayerChange()
        pushStructure()
        val ow = docWidth
        val oh = docHeight
        val m = Matrix().apply {
            if (clockwise) {
                postRotate(90f)
                postTranslate(oh.toFloat(), 0f)
            } else {
                postRotate(-90f)
                postTranslate(0f, ow.toFloat())
            }
        }
        for (l in layers) {
            val nb = Bitmap.createBitmap(oh, ow, Bitmap.Config.ARGB_8888)
            Canvas(nb).drawBitmap(l.bitmap, m, null)
            l.bitmap = nb
        }
        docWidth = oh
        docHeight = ow
        clearSelectionInternal()
        fitView()
        invalidate(true)
    }

    fun flipCanvas(horizontal: Boolean) {
        finishSessionsBeforeLayerChange()
        pushStructure()
        val m = Matrix().apply {
            if (horizontal) {
                postScale(-1f, 1f)
                postTranslate(docWidth.toFloat(), 0f)
            } else {
                postScale(1f, -1f)
                postTranslate(0f, docHeight.toFloat())
            }
        }
        for (l in layers) {
            val nb = blankBitmap()
            Canvas(nb).drawBitmap(l.bitmap, m, null)
            l.bitmap = nb
        }
        clearSelectionInternal()
        invalidate(true)
    }

    // =====================================================================
    // Selection
    // =====================================================================

    private fun selectionBegin(p: PointF) {
        if (selectShape == SelectShape.RECT) {
            rectStart = p
            rectEnd = p
        } else {
            lassoPath.reset()
            lassoPath.moveTo(p.x, p.y)
            lassoActive = true
        }
        invalidate()
    }

    private fun selectionMove(p: PointF) {
        if (selectShape == SelectShape.RECT) {
            if (rectStart != null) rectEnd = p
        } else if (lassoActive) {
            lassoPath.lineTo(p.x, p.y)
        }
        invalidate()
    }

    private fun selectionEnd(moved: Boolean) {
        val wasActive = lassoActive || rectStart != null
        if (!wasActive) return
        if (!moved) {
            // A tap: in "New" mode it clears the selection.
            lassoActive = false
            rectStart = null
            rectEnd = null
            if (selectMode == SelectMode.NEW) clearSelection()
            invalidate()
            return
        }
        val m = newMask()
        val c = Canvas(m)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK }
        val rs = rectStart
        val re = rectEnd
        if (selectShape == SelectShape.RECT && rs != null && re != null) {
            c.drawRect(min(rs.x, re.x), min(rs.y, re.y), max(rs.x, re.x), max(rs.y, re.y), fill)
        } else {
            lassoPath.close()
            c.drawPath(lassoPath, fill)
        }
        lassoActive = false
        rectStart = null
        rectEnd = null
        combineSelection(m)
    }

    private fun combineSelection(m: Bitmap) {
        val cur = selection
        selection = when {
            selectMode == SelectMode.NEW -> m
            cur == null -> if (selectMode == SelectMode.SUBTRACT) null else m
            selectMode == SelectMode.ADD -> cur.also { Canvas(it).drawBitmap(m, 0f, 0f, null) }
            else -> cur.also { Canvas(it).drawBitmap(m, 0f, 0f, dstOut) }
        }
        hasSelection = selection != null
        invalidate()
    }

    fun selectAll() {
        val m = newMask()
        m.eraseColor(Color.BLACK)
        selection = m
        hasSelection = true
        invalidate()
    }

    fun invertSelection() {
        val cur = selection
        if (cur == null) {
            selectAll()
            return
        }
        val m = newMask()
        val c = Canvas(m)
        c.drawColor(Color.BLACK)
        c.drawBitmap(cur, 0f, 0f, dstOut)
        selection = m
        invalidate()
    }

    fun clearSelection() {
        if (adjusting) cancelAdjust()
        clearSelectionInternal()
        invalidate()
    }

    private fun clearSelectionInternal() {
        selection = null
        hasSelection = false
    }

    fun deleteSelected() {
        val sel = selection ?: return
        val l = activeLayer ?: return
        editLayer(l) { c, _ -> c.drawBitmap(sel, 0f, 0f, dstOut) }
    }

    fun fillSelection() {
        val sel = selection ?: return
        val l = activeLayer ?: return
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            color = brushColor or 0xFF000000.toInt()
            alpha = (brushOpacity * 255f).roundToInt()
        }
        editLayer(l) { c, _ -> c.drawBitmap(sel, 0f, 0f, p) }
        addRecentColor(brushColor)
    }

    /** Copies (or cuts) the selected pixels of the current layer into a new layer above it. */
    fun selectionToLayer(cut: Boolean) {
        val sel = selection ?: return
        val l = activeLayer ?: return
        pushStructure()
        val extracted = l.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(extracted).drawBitmap(sel, 0f, 0f, dstIn)
        if (cut) {
            val rest = l.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(rest).drawBitmap(sel, 0f, 0f, dstOut)
            l.bitmap = rest
        }
        layers.add(activeIndex + 1, Layer(if (cut) "Cut ${layerCounter++}" else "Copy ${layerCounter++}", extracted))
        activeIndex += 1
        message = if (cut) "Cut to a new layer." else "Copied to a new layer."
        invalidate(true)
    }

    fun smartSelect(kind: SmartKind) {
        if (isBusy || !hasDocument) return
        if (adjusting) cancelAdjust()
        val snapshot = visibleSnapshot()
        val version = contentVersion
        val w = docWidth
        val h = docHeight
        busyMessage = "Finding ${kind.label.lowercase()}…"
        viewModelScope.launch {
            try {
                val (small, coverage) = withContext(Dispatchers.Default) { computeSmartMask(kind, snapshot, w, h, version) }
                if (w != docWidth || h != docHeight) return@launch
                if (coverage < 0.002f) {
                    message = "No ${kind.label.lowercase()} found in this image."
                } else {
                    val full = newMask()
                    Canvas(full).drawBitmap(small, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), filterPaint)
                    combineSelection(full)
                    message = "${kind.label} selected. Adjust, delete, copy or transform it."
                }
            } catch (e: FriendlyException) {
                message = e.message
            } catch (e: OutOfMemoryError) {
                message = "Not enough memory for smart selection."
            } catch (e: Exception) {
                message = "Smart select failed: ${e.message}"
            } finally {
                busyMessage = null
            }
        }
    }

    private suspend fun computeSmartMask(
        kind: SmartKind,
        snapshot: List<Pair<Bitmap, Float>>,
        w: Int, h: Int,
        version: Long,
    ): Pair<Bitmap, Float> {
        val classes = kind.classes
        val prob: FloatArray
        val sw: Int
        val sh: Int
        if (classes == null) {
            val small = flattenMax(snapshot, w, h, 1024, Color.WHITE)
            sw = small.width
            sh = small.height
            val cached = subjectCache?.takeIf { it.version == version && it.w == sw && it.h == sh }
            val fg = cached?.data ?: SubjectSegmenter.foreground(small).also {
                subjectCache = ProbCache(version, sw, sh, it)
            }
            prob = if (kind == SmartKind.BACKGROUND) FloatArray(fg.size) { 1f - fg[it] } else fg
        } else {
            val small = flattenMax(snapshot, w, h, 768, Color.WHITE)
            sw = small.width
            sh = small.height
            val scene = sceneCache?.takeIf { it.first == version }?.second
                ?: sceneSegmenter.run(small).also { sceneCache = version to it }
            val low = scene.groupProbability(classes)
            val guideLowBmp = Bitmap.createScaledBitmap(small, scene.width, scene.height, true)
            val guideLow = IntArray(scene.width * scene.height)
            guideLowBmp.getPixels(guideLow, 0, scene.width, 0, 0, scene.width, scene.height)
            val hi = IntArray(sw * sh)
            small.getPixels(hi, 0, sw, 0, 0, sw, sh)
            val up = MaskMath.jointBilateralUpsample(low, scene.width, scene.height, guideLow, hi, sw, sh)
            prob = FloatArray(up.size) { MaskMath.smoothstep(0.35f, 0.65f, up[it]) }
        }
        var covered = 0
        for (v in prob) if (v > 0.5f) covered++
        val coverage = covered.toFloat() / max(1, prob.size)
        return MaskMath.toAlphaBitmap(prob, sw, sh, sharpen = false) to coverage
    }

    fun loadSceneModel(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val tmp = java.io.File(ctx.filesDir, "scene_model.tmp")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: error("Couldn't read the file")
                    val header = tmp.inputStream().use { s -> ByteArray(8).also { s.read(it) } }
                    if (String(header, 4, 4, Charsets.US_ASCII) != "TFL3") {
                        tmp.delete()
                        throw FriendlyException("That file isn't a TensorFlow Lite model.")
                    }
                    sceneSegmenter.reset()
                    val dest = sceneSegmenter.customFile
                    dest.delete()
                    if (!tmp.renameTo(dest)) error("Couldn't save the model")
                }
                sceneCache = null
                message = "Scene model loaded. Sky, Nature, Buildings and more are ready."
            } catch (e: FriendlyException) {
                message = e.message
            } catch (e: Exception) {
                message = "Couldn't load the model: ${e.message}"
            }
        }
    }

    // =====================================================================
    // Adjustments (Lightroom-style, applied to the selection or whole layer)
    // =====================================================================

    fun beginAdjust() {
        if (activeLayer == null) return
        if (transform != null) applyTransform()
        adjust = AdjustParams()
        adjustFilter = ColorMatrixColorFilter(adjust.toColorMatrix())
        adjusting = true
        invalidate()
    }

    fun updateAdjust(p: AdjustParams) {
        adjust = p
        adjustFilter = ColorMatrixColorFilter(p.toColorMatrix())
        invalidate()
    }

    fun cancelAdjust() {
        adjusting = false
        invalidate()
    }

    fun applyAdjust() {
        val l = activeLayer
        if (l == null || adjust.isNeutral) {
            cancelAdjust()
            return
        }
        val orig = l.bitmap
        val sel = selection
        val filter = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = adjustFilter }
        val nb = Bitmap.createBitmap(docWidth, docHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(nb)
        if (sel == null) {
            c.drawBitmap(orig, 0f, 0f, filter)
        } else {
            // result = original * (1 - mask) + adjusted * mask
            c.drawBitmap(orig, 0f, 0f, null)
            c.drawBitmap(sel, 0f, 0f, dstOut)
            val add = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
            c.saveLayer(null, add)
            c.drawBitmap(orig, 0f, 0f, filter)
            c.drawBitmap(sel, 0f, 0f, dstIn)
            c.restore()
        }
        pushUndo(UndoEntry.Pixels(l, orig))
        l.bitmap = nb
        adjusting = false
        invalidate(true)
    }

    // =====================================================================
    // Transform
    // =====================================================================

    private fun contentBounds(b: Bitmap): RectF? {
        val w = b.width
        val h = b.height
        val row = IntArray(w)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            var any = false
            for (x in 0 until w) {
                if ((row[x] ushr 24) != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    any = true
                }
            }
            if (any) {
                if (y < minY) minY = y
                maxY = y
            }
        }
        if (maxX < 0) return null
        return RectF(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
    }

    private fun beginTransform() {
        val l = activeLayer ?: return
        if (transform != null) return
        val sel = selection
        val source: Bitmap
        val base: Bitmap?
        if (sel != null) {
            source = l.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(source).drawBitmap(sel, 0f, 0f, dstIn)
            base = l.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(base).drawBitmap(sel, 0f, 0f, dstOut)
        } else {
            source = l.bitmap
            base = null
        }
        val bounds = contentBounds(source)
        if (bounds == null) {
            message = if (sel != null) "Nothing selected on this layer." else "This layer is empty."
            return
        }
        transform = TransformSession(l, source, base, bounds, sel)
        invalidate()
    }

    fun applyTransform() {
        val t = transform ?: return
        if (t.matrix.isIdentity && t.selMask == null) {
            cancelTransform()
            return
        }
        val out = t.base ?: blankBitmap()
        Canvas(out).drawBitmap(t.source, t.matrix, filterPaint)
        pushUndo(UndoEntry.Pixels(t.layer, t.layer.bitmap))
        t.layer.bitmap = out
        val sm = t.selMask
        if (sm != null) {
            val ns = newMask()
            Canvas(ns).drawBitmap(sm, t.matrix, filterPaint)
            selection = ns
            hasSelection = true
        }
        transform = null
        invalidate(true)
    }

    fun cancelTransform() {
        transform = null
        invalidate()
    }

    private fun transformedCenter(t: TransformSession): PointF {
        val r = RectF(t.bounds)
        t.matrix.mapRect(r)
        return PointF(r.centerX(), r.centerY())
    }

    fun transformFlip(horizontal: Boolean) {
        val t = transform ?: return
        val c = PointF(t.bounds.centerX(), t.bounds.centerY())
        if (horizontal) t.matrix.preScale(-1f, 1f, c.x, c.y) else t.matrix.preScale(1f, -1f, c.x, c.y)
        invalidate()
    }

    fun transformRotate90() {
        val t = transform ?: return
        val c = transformedCenter(t)
        t.matrix.postRotate(90f, c.x, c.y)
        invalidate()
    }

    fun transformFit() {
        val t = transform ?: return
        val b = t.bounds
        val s = min(docWidth / b.width(), docHeight / b.height())
        t.matrix.reset()
        t.matrix.postTranslate(-b.left, -b.top)
        t.matrix.postScale(s, s)
        t.matrix.postTranslate((docWidth - b.width() * s) / 2f, (docHeight - b.height() * s) / 2f)
        invalidate()
    }

    fun transformReset() {
        transform?.matrix?.reset()
        invalidate()
    }

    /** Handle positions in document space: TL, T, TR, R, BR, B, BL, L. */
    fun transformHandles(t: TransformSession): FloatArray {
        val b = t.bounds
        val pts = floatArrayOf(
            b.left, b.top, b.centerX(), b.top, b.right, b.top, b.right, b.centerY(),
            b.right, b.bottom, b.centerX(), b.bottom, b.left, b.bottom, b.left, b.centerY(),
        )
        t.matrix.mapPoints(pts)
        return pts
    }

    private fun transformDown(screen: Offset) {
        val t = transform ?: return
        val pts = transformHandles(t)
        viewMatrix.mapPoints(pts)
        var best = -1
        var bestD = 56f
        for (i in 0 until 8) {
            val d = hypot(pts[i * 2] - screen.x, pts[i * 2 + 1] - screen.y)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        transformDrag = if (best >= 0) best else 8
    }

    private fun transformMove(d: PointF) {
        val t = transform ?: return
        when (val h = transformDrag) {
            8 -> t.matrix.postTranslate(d.x - lastDoc.x, d.y - lastDoc.y)
            in 0..7 -> {
                val inv = Matrix()
                if (!t.matrix.invert(inv)) return
                val lp = floatArrayOf(d.x, d.y)
                inv.mapPoints(lp)
                val lx = lp[0]
                val ly = lp[1]
                val b = t.bounds
                val bw = max(1f, b.width())
                val bh = max(1f, b.height())
                fun ok(v: Float) = v > 0.02f && v < 50f
                when (h) {
                    3 -> {
                        val sx = (lx - b.left) / bw
                        if (ok(sx)) t.matrix.preScale(sx, 1f, b.left, b.centerY())
                    }
                    7 -> {
                        val sx = (b.right - lx) / bw
                        if (ok(sx)) t.matrix.preScale(sx, 1f, b.right, b.centerY())
                    }
                    1 -> {
                        val sy = (b.bottom - ly) / bh
                        if (ok(sy)) t.matrix.preScale(1f, sy, b.centerX(), b.bottom)
                    }
                    5 -> {
                        val sy = (ly - b.top) / bh
                        if (ok(sy)) t.matrix.preScale(1f, sy, b.centerX(), b.top)
                    }
                    else -> {
                        val (ax, ay, sx, sy) = when (h) {
                            0 -> Quad(b.right, b.bottom, (b.right - lx) / bw, (b.bottom - ly) / bh)
                            2 -> Quad(b.left, b.bottom, (lx - b.left) / bw, (b.bottom - ly) / bh)
                            4 -> Quad(b.left, b.top, (lx - b.left) / bw, (ly - b.top) / bh)
                            else -> Quad(b.right, b.top, (b.right - lx) / bw, (ly - b.top) / bh)
                        }
                        val s = (sx + sy) / 2f
                        if (ok(s)) t.matrix.preScale(s, s, ax, ay)
                    }
                }
            }
            else -> return
        }
        invalidate()
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    // =====================================================================
    // Frame divider
    // =====================================================================

    private fun beginFrame() {
        if (frameActive) return
        frameSplits.clear()
        frameRows = 1
        frameCols = 1
        framePreview = blankBitmap()
        frameActive = true
        frameRebuild()
        updateUndoFlags()
        message = "Drag a line across a panel to split it."
    }

    private fun frameRebuild() {
        val prev = framePreview ?: return
        framePanels = FrameDivider.buildPanels(
            docWidth, docHeight, frameMargin, frameGutter, frameRows, frameCols, frameSplits,
        )
        FrameDivider.render(prev, framePanels, frameBorder, Color.BLACK, frameWhiteGutters)
        invalidate()
    }

    fun setFrameGrid(rows: Int, cols: Int) {
        frameRows = rows.coerceIn(1, 8)
        frameCols = cols.coerceIn(1, 8)
        frameRebuild()
    }

    fun changeFrameGutter(v: Float) { frameGutter = v; frameRebuild() }
    fun changeFrameBorder(v: Float) { frameBorder = v; frameRebuild() }
    fun changeFrameMargin(v: Float) { frameMargin = v; frameRebuild() }
    fun changeFrameWhiteGutters(v: Boolean) { frameWhiteGutters = v; frameRebuild() }

    fun frameUndoSplit() {
        if (frameSplits.isNotEmpty()) {
            frameSplits.removeAt(frameSplits.lastIndex)
            frameRebuild()
        }
        updateUndoFlags()
    }

    fun frameClearSplits() {
        frameSplits.clear()
        frameRebuild()
        updateUndoFlags()
    }

    private fun frameLineBegin(p: PointF) {
        if (!frameActive) return
        frameLine = Seg(p.x, p.y, p.x, p.y)
        invalidate()
    }

    private fun frameLineMove(p: PointF) {
        val l = frameLine ?: return
        frameLine = snapSeg(Seg(l.ax, l.ay, p.x, p.y))
        invalidate()
    }

    private fun snapSeg(s: Seg): Seg {
        if (!frameSnap) return s
        val ang = Math.toDegrees(atan2((s.by - s.ay).toDouble(), (s.bx - s.ax).toDouble()))
        val near = ((ang / 90.0).roundToInt() * 90.0)
        return if (abs(ang - near) < 10.0) {
            if (near.roundToInt() % 180 == 0) Seg(s.ax, s.ay, s.bx, s.ay) else Seg(s.ax, s.ay, s.ax, s.by)
        } else s
    }

    private fun frameLineEnd() {
        val l = frameLine ?: return
        frameLine = null
        if (hypot(l.bx - l.ax, l.by - l.ay) < 12f / max(0.01f, viewScale)) {
            invalidate()
            return
        }
        val before = framePanels.size
        frameSplits.add(l)
        frameRebuild()
        if (framePanels.size == before) {
            frameSplits.removeAt(frameSplits.lastIndex)
            frameRebuild()
            message = "Draw the line across a panel."
        }
        updateUndoFlags()
    }

    fun frameDone() {
        val prev = framePreview ?: return
        pushStructure()
        layers.add(Layer("Frames", prev))
        frameActive = false
        framePreview = null
        frameSplits.clear()
        frameLine = null
        if (tool == Tool.FRAME) tool = Tool.BRUSH
        updateUndoFlags()
        message = "Frames added as a new layer on top."
        invalidate(true)
    }

    fun frameCancel() {
        frameActive = false
        framePreview = null
        frameSplits.clear()
        frameLine = null
        if (tool == Tool.FRAME) tool = Tool.BRUSH
        updateUndoFlags()
        invalidate()
    }
}
