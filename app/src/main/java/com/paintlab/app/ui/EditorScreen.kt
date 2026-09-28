@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.paintlab.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paintlab.app.EditorViewModel
import com.paintlab.app.Tool
import com.paintlab.app.io.ExportFormat
import com.paintlab.app.model.SizeEditorState
import com.paintlab.app.model.Units
import kotlin.math.roundToInt

private data class PendingExport(val format: ExportFormat, val scale: Float, val transparent: Boolean, val quality: Int)

@Composable
fun EditorScreen(vm: EditorViewModel) {
    var showLayers by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showColor by remember { mutableStateOf(false) }
    var showResize by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingExport?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importPicture(uri)
    }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadSceneModel(uri)
    }
    val onCreated: (android.net.Uri?) -> Unit = { uri ->
        val p = pending
        if (uri != null && p != null) vm.exportToUri(uri, p.format, p.scale, p.transparent, p.quality)
        pending = null
    }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png"), onCreated)
    val saveJpg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg"), onCreated)
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), onCreated)

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snackbar.showSnackbar(m)
            if (vm.message == m) vm.message = null
        }
    }

    BackHandler {
        when {
            showLayers -> showLayers = false
            vm.adjusting -> vm.cancelAdjust()
            vm.transform != null -> vm.cancelTransform()
            vm.frameActive -> vm.frameCancel()
            else -> confirmExit = true
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ---------------- top bar
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { confirmExit = true }) { Icon(Icons.Filled.Close, "Close canvas") }
                IconButton(onClick = vm::undo, enabled = vm.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                }
                IconButton(onClick = vm::redo, enabled = vm.canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                }
                Text(
                    "${vm.docWidth}×${vm.docHeight}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconButton(onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Icon(Icons.Filled.AddPhotoAlternate, "Import picture") }
                IconButton(onClick = { showLayers = !showLayers }) {
                    Icon(Icons.Filled.Layers, "Layers", tint = if (showLayers) MaterialTheme.colorScheme.primary else Color.Unspecified)
                }
                IconButton(onClick = { showExport = true }) { Icon(Icons.Filled.Save, "Export") }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Canvas size…") }, onClick = { showMenu = false; showResize = true })
                        DropdownMenuItem(text = { Text("Rotate canvas right") }, onClick = { showMenu = false; vm.rotateCanvas(true) })
                        DropdownMenuItem(text = { Text("Rotate canvas left") }, onClick = { showMenu = false; vm.rotateCanvas(false) })
                        DropdownMenuItem(text = { Text("Flip canvas horizontally") }, onClick = { showMenu = false; vm.flipCanvas(true) })
                        DropdownMenuItem(text = { Text("Flip canvas vertically") }, onClick = { showMenu = false; vm.flipCanvas(false) })
                        DropdownMenuItem(text = { Text("Fit canvas to screen") }, onClick = { showMenu = false; vm.fitView() })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (vm.sceneSegmenter.isAvailable()) "Replace scene model…" else "Load scene model…") },
                            onClick = { showMenu = false; pickModel.launch(arrayOf("*/*")) },
                        )
                    }
                }
            }
        }

        // ---------------- canvas
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CanvasView(vm, Modifier.fillMaxSize())
            if (showLayers) {
                LayersPanel(
                    vm,
                    onClose = { showLayers = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
            val busy = vm.busyMessage
            if (busy != null) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(busy)
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }

        // ---------------- tool options + toolbar
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                ToolOptions(vm)
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                ToolBar(vm, onColor = { showColor = true })
            }
        }
    }

    if (showColor) {
        ColorPickerDialog(
            initial = vm.brushColor,
            recent = vm.recentColors.toList(),
            onDismiss = { showColor = false },
            onPick = {
                vm.brushColor = it
                vm.addRecentColor(it)
                showColor = false
            },
        )
    }

    if (showExport) {
        ExportDialog(
            vm = vm,
            onDismiss = { showExport = false },
            onQuickSave = { f, s, t, q ->
                showExport = false
                vm.quickSave(f, s, t, q)
            },
            onSaveAs = { f, s, t, q ->
                showExport = false
                pending = PendingExport(f, s, t, q)
                val name = "${vm.defaultExportName()}.${f.ext}"
                when (f) {
                    ExportFormat.PNG -> savePng.launch(name)
                    ExportFormat.JPG -> saveJpg.launch(name)
                    ExportFormat.PDF -> savePdf.launch(name)
                }
            },
        )
    }

    if (showResize) ResizeDialog(vm, onDismiss = { showResize = false })

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Close this canvas?") },
            text = { Text("Unsaved work will be lost. Export it first if you want to keep it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    vm.closeDocument()
                }) { Text("Close") }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Keep editing") } },
        )
    }
}

// =========================================================================
// Toolbar
// =========================================================================

private fun toolIcon(t: Tool): ImageVector = when (t) {
    Tool.BRUSH -> Icons.Filled.Brush
    Tool.ERASER -> EraserIcon
    Tool.FILL -> Icons.Filled.FormatColorFill
    Tool.EYEDROPPER -> Icons.Filled.Colorize
    Tool.SELECT -> Icons.Filled.Gesture
    Tool.TRANSFORM -> Icons.Filled.Transform
    Tool.FRAME -> Icons.Filled.GridOn
    Tool.HAND -> Icons.Filled.PanTool
}

@Composable
private fun ToolBar(vm: EditorViewModel, onColor: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in Tool.entries) {
            val selected = vm.tool == t
            Column(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { vm.selectTool(t) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    toolIcon(t),
                    contentDescription = t.label,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    t.label,
                    fontSize = 10.sp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(vm.brushColor))
                .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                .clickable(onClick = onColor)
        )
        Spacer(Modifier.width(8.dp))
    }
}

// =========================================================================
// Tool options
// =========================================================================

private fun sizeToSlider(size: Float): Float = (kotlin.math.ln(size.coerceIn(1f, 1000f)) / kotlin.math.ln(1000f))
private fun sliderToSize(v: Float): Float = Math.pow(1000.0, v.toDouble()).toFloat().coerceIn(1f, 1000f)

@Composable
private fun Hint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun ToolOptions(vm: EditorViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        when {
            vm.adjusting -> AdjustPanel(vm)
            vm.tool == Tool.BRUSH -> {
                LabeledSlider("Size", sizeToSlider(vm.brushSize), { vm.brushSize = sliderToSize(it) }, 0f..1f, "${vm.brushSize.roundToInt()} px")
                LabeledSlider("Opacity", vm.brushOpacity, { vm.brushOpacity = it }, 0.01f..1f, "${(vm.brushOpacity * 100).roundToInt()}%")
                LabeledSlider("Hardness", vm.brushHardness, { vm.brushHardness = it }, 0f..1f, "${(vm.brushHardness * 100).roundToInt()}%")
            }
            vm.tool == Tool.ERASER -> {
                LabeledSlider("Size", sizeToSlider(vm.eraserSize), { vm.eraserSize = sliderToSize(it) }, 0f..1f, "${vm.eraserSize.roundToInt()} px")
                LabeledSlider("Opacity", vm.eraserOpacity, { vm.eraserOpacity = it }, 0.01f..1f, "${(vm.eraserOpacity * 100).roundToInt()}%")
                LabeledSlider("Hardness", vm.eraserHardness, { vm.eraserHardness = it }, 0f..1f, "${(vm.eraserHardness * 100).roundToInt()}%")
            }
            vm.tool == Tool.FILL -> {
                LabeledSlider("Tolerance", vm.fillTolerance, { vm.fillTolerance = it }, 0f..255f, "${vm.fillTolerance.roundToInt()}")
                LabeledSlider("Opacity", vm.brushOpacity, { vm.brushOpacity = it }, 0.01f..1f, "${(vm.brushOpacity * 100).roundToInt()}%")
                Hint("Tap an area to fill it on the current layer. Line art on other layers counts as a border.")
            }
            vm.tool == Tool.EYEDROPPER -> Hint("Touch the canvas to pick a color.")
            vm.tool == Tool.SELECT -> SelectPanel(vm)
            vm.tool == Tool.TRANSFORM -> TransformPanel(vm)
            vm.tool == Tool.FRAME -> FramePanel(vm)
            vm.tool == Tool.HAND -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow rotating the view", Modifier.weight(1f), fontSize = 13.sp)
                    Switch(checked = vm.allowViewRotation, onCheckedChange = { vm.allowViewRotation = it })
                }
                Row {
                    TextButton(onClick = vm::fitView) { Text("Fit to screen") }
                }
                Hint("Drag to move. Pinch with two fingers to zoom (works with every tool).")
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun SelectPanel(vm: EditorViewModel) {
    ChipRow {
        FilterChip(selected = vm.selectShape == com.paintlab.app.SelectShape.LASSO, onClick = { vm.selectShape = com.paintlab.app.SelectShape.LASSO }, label = { Text("Lasso") })
        FilterChip(selected = vm.selectShape == com.paintlab.app.SelectShape.RECT, onClick = { vm.selectShape = com.paintlab.app.SelectShape.RECT }, label = { Text("Rectangle") })
        Spacer(Modifier.width(6.dp))
        FilterChip(selected = vm.selectMode == com.paintlab.app.SelectMode.NEW, onClick = { vm.selectMode = com.paintlab.app.SelectMode.NEW }, label = { Text("New") })
        FilterChip(selected = vm.selectMode == com.paintlab.app.SelectMode.ADD, onClick = { vm.selectMode = com.paintlab.app.SelectMode.ADD }, label = { Text("Add") })
        FilterChip(selected = vm.selectMode == com.paintlab.app.SelectMode.SUBTRACT, onClick = { vm.selectMode = com.paintlab.app.SelectMode.SUBTRACT }, label = { Text("Subtract") })
    }
    Text("Smart select", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
    ChipRow {
        for (k in com.paintlab.app.ml.SmartKind.entries) {
            androidx.compose.material3.AssistChip(onClick = { vm.smartSelect(k) }, label = { Text(k.label) })
        }
    }
    ChipRow {
        if (vm.hasSelection) {
            TextButton(onClick = vm::beginAdjust) { Text("Adjust") }
            TextButton(onClick = vm::fillSelection) { Text("Fill") }
            TextButton(onClick = vm::deleteSelected) { Text("Delete") }
            TextButton(onClick = { vm.selectionToLayer(false) }) { Text("Copy → layer") }
            TextButton(onClick = { vm.selectionToLayer(true) }) { Text("Cut → layer") }
            TextButton(onClick = { vm.selectTool(Tool.TRANSFORM) }) { Text("Transform") }
            TextButton(onClick = vm::invertSelection) { Text("Invert") }
            TextButton(onClick = vm::clearSelection) { Text("Deselect") }
        } else {
            TextButton(onClick = vm::selectAll) { Text("Select all") }
            TextButton(onClick = vm::beginAdjust) { Text("Adjust whole layer") }
        }
    }
}

@Composable
private fun AdjustPanel(vm: EditorViewModel) {
    val a = vm.adjust
    Text(
        if (vm.hasSelection) "Adjust selection (current layer)" else "Adjust current layer",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.primary,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .verticalScroll(rememberScrollState())
    ) {
        LabeledSlider("Exposure", a.exposure, { vm.updateAdjust(a.copy(exposure = it)) }, -2f..2f, String.format("%+.2f", a.exposure))
        LabeledSlider("Contrast", a.contrast, { vm.updateAdjust(a.copy(contrast = it)) }, -1f..1f, "${(a.contrast * 100).roundToInt()}")
        LabeledSlider("Saturation", a.saturation, { vm.updateAdjust(a.copy(saturation = it)) }, -1f..1f, "${(a.saturation * 100).roundToInt()}")
        LabeledSlider("Temp", a.temperature, { vm.updateAdjust(a.copy(temperature = it)) }, -1f..1f, "${(a.temperature * 100).roundToInt()}")
        LabeledSlider("Tint", a.tint, { vm.updateAdjust(a.copy(tint = it)) }, -1f..1f, "${(a.tint * 100).roundToInt()}")
        LabeledSlider("Hue", a.hue, { vm.updateAdjust(a.copy(hue = it)) }, -180f..180f, "${a.hue.roundToInt()}°")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { vm.updateAdjust(com.paintlab.app.model.AdjustParams()) }) { Text("Reset") }
        TextButton(onClick = vm::cancelAdjust) { Text("Cancel") }
        TextButton(onClick = vm::applyAdjust) { Text("Apply") }
    }
}

@Composable
private fun TransformPanel(vm: EditorViewModel) {
    if (vm.transform == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Transforms the current layer, or just the selection if there is one.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.selectTool(Tool.TRANSFORM) }) { Text("Start") }
        }
        return
    }
    ChipRow {
        TextButton(onClick = { vm.transformFlip(true) }) { Text("Flip H") }
        TextButton(onClick = { vm.transformFlip(false) }) { Text("Flip V") }
        TextButton(onClick = vm::transformRotate90) { Text("Rotate 90°") }
        TextButton(onClick = vm::transformFit) { Text("Fit canvas") }
        TextButton(onClick = vm::transformReset) { Text("Reset") }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Drag to move · pinch to scale/rotate · handles to stretch",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = vm::cancelTransform) { Text("Cancel") }
        TextButton(onClick = vm::applyTransform) { Text("Apply") }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp)
        TextButton(onClick = { onChange(value - 1) }) { Text("−") }
        Text("$value", fontSize = 14.sp)
        TextButton(onClick = { onChange(value + 1) }) { Text("+") }
    }
}

@Composable
private fun FramePanel(vm: EditorViewModel) {
    if (!vm.frameActive) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("Frame divider is closed.")
            TextButton(onClick = { vm.selectTool(Tool.FRAME) }) { Text("Start again") }
        }
        return
    }
    val minSide = minOf(vm.docWidth, vm.docHeight).toFloat()
    Column(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper("Rows", vm.frameRows) { vm.setFrameGrid(it, vm.frameCols) }
            Spacer(Modifier.width(8.dp))
            Stepper("Columns", vm.frameCols) { vm.setFrameGrid(vm.frameRows, it) }
        }
        LabeledSlider("Gutter", vm.frameGutter, vm::setFrameGutter, 0f..(minSide * 0.12f), "${vm.frameGutter.roundToInt()}")
        LabeledSlider("Border", vm.frameBorder, vm::setFrameBorder, 0f..(minSide * 0.02f).coerceAtLeast(4f), "${vm.frameBorder.roundToInt()}")
        LabeledSlider("Margin", vm.frameMargin, vm::setFrameMargin, 0f..(minSide * 0.15f), "${vm.frameMargin.roundToInt()}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Snap lines straight", Modifier.weight(1f), fontSize = 13.sp)
            Switch(checked = vm.frameSnap, onCheckedChange = { vm.frameSnap = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("White gutters", Modifier.weight(1f), fontSize = 13.sp)
            Switch(checked = vm.frameWhiteGutters, onCheckedChange = vm::setFrameWhiteGutters)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${vm.framePanels.size} panels · drag a line to split",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = vm::frameClearSplits) { Text("Clear") }
        TextButton(onClick = vm::frameCancel) { Text("Cancel") }
        TextButton(onClick = vm::frameDone) { Text("Done") }
    }
}

// =========================================================================
// Dialogs
// =========================================================================

@Composable
private fun ExportDialog(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
    onQuickSave: (ExportFormat, Float, Boolean, Int) -> Unit,
    onSaveAs: (ExportFormat, Float, Boolean, Int) -> Unit,
) {
    var format by remember { mutableStateOf(ExportFormat.PNG) }
    var scaleIdx by remember { mutableIntStateOf(1) }
    var transparent by remember { mutableStateOf(true) }
    var quality by remember { mutableFloatStateOf(92f) }
    val scales = listOf(0.5f, 1f, 2f)
    val scale = scales[scaleIdx]
    val outW = (vm.docWidth * scale).roundToInt()
    val outH = (vm.docHeight * scale).roundToInt()
    val tooBig = outW.toLong() * outH > 64_000_000L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in ExportFormat.entries) {
                        FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Size", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    scales.forEachIndexed { i, s ->
                        FilterChip(selected = scaleIdx == i, onClick = { scaleIdx = i }, label = { Text("${(s * 100).roundToInt()}%") })
                    }
                }
                Text("$outW × $outH px", style = MaterialTheme.typography.bodySmall)
                if (format == ExportFormat.PDF) {
                    val u = vm.docUnit.takeIf { it != com.paintlab.app.model.SizeUnit.PX } ?: com.paintlab.app.model.SizeUnit.CM
                    val pw = Units.trimNumber(Units.fromPx(vm.docWidth, u, vm.docDpi))
                    val ph = Units.trimNumber(Units.fromPx(vm.docHeight, u, vm.docDpi))
                    Text("PDF page: $pw × $ph ${u.label} (${Units.trimNumber(vm.docDpi)} dpi canvas)", style = MaterialTheme.typography.bodySmall)
                }
                if (tooBig) Text("Too large; choose a smaller size.", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                when (format) {
                    ExportFormat.PNG -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Transparent background", Modifier.weight(1f))
                        Switch(checked = transparent, onCheckedChange = { transparent = it })
                    }
                    ExportFormat.JPG -> LabeledSlider("Quality", quality, { quality = it }, 50f..100f, "${quality.roundToInt()}")
                    ExportFormat.PDF -> Text("Single page, white background.", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (format == ExportFormat.PDF) "\"Save to phone\" puts it in Download/PaintLab."
                    else "\"Save to phone\" puts it in Pictures/PaintLab (shows in your gallery).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onQuickSave(format, scale, transparent, quality.roundToInt()) },
                enabled = !tooBig,
            ) { Text("Save to phone") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    onClick = { onSaveAs(format, scale, transparent, quality.roundToInt()) },
                    enabled = !tooBig,
                ) { Text("Save as…") }
            }
        },
    )
}

@Composable
private fun ResizeDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val state = remember { SizeEditorState(vm.docWidth, vm.docHeight, vm.docDpi, vm.docUnit) }
    var scaleContent by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Canvas size") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SizeEditor(state)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { scaleContent = true }) {
                    RadioButton(selected = scaleContent, onClick = { scaleContent = true })
                    Text("Scale the artwork (image size)")
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { scaleContent = false }) {
                    RadioButton(selected = !scaleContent, onClick = { scaleContent = false })
                    Text("Keep artwork size, crop/extend around center")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    vm.resizeCanvas(state.widthPx, state.heightPx, state.dpi, state.unit, scaleContent)
                } catch (e: OutOfMemoryError) {
                    vm.message = "Not enough memory for that size."
                }
                onDismiss()
            }, enabled = !state.tooLarge) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// =========================================================================
// Layers panel
// =========================================================================

@Composable
private fun LayersPanel(vm: EditorViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var renaming by remember { mutableStateOf<com.paintlab.app.Layer?>(null) }
    Surface(
        modifier = modifier.width(280.dp).fillMaxHeight(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = vm::addLayer) { Text("+ New") }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close layers") }
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                val count = vm.layers.size
                items(count, key = { k -> vm.layers[count - 1 - k].id }) { k ->
                    val index = count - 1 - k
                    val layer = vm.layers[index]
                    LayerRow(
                        vm = vm,
                        layer = layer,
                        selected = index == vm.activeIndex,
                        onClick = { vm.setActive(index) },
                        onLongClick = { renaming = layer },
                    )
                }
            }
            val active = vm.activeLayer
            if (active != null) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Opacity", fontSize = 12.sp, modifier = Modifier.width(56.dp))
                    Slider(
                        value = active.opacity,
                        onValueChange = { vm.setOpacity(active, it) },
                        modifier = Modifier.weight(1f),
                    )
                    Text("${(active.opacity * 100).roundToInt()}%", fontSize = 12.sp, modifier = Modifier.width(40.dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { vm.moveLayer(true) }) { Text("Up") }
                    TextButton(onClick = { vm.moveLayer(false) }) { Text("Down") }
                    TextButton(onClick = vm::duplicateLayer) { Text("Duplicate") }
                    TextButton(onClick = vm::mergeDown) { Text("Merge ↓") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { renaming = active }) { Text("Rename") }
                    TextButton(onClick = vm::clearLayer) { Text(if (vm.hasSelection) "Clear sel." else "Clear") }
                    TextButton(onClick = vm::deleteLayer) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    renaming?.let { layer ->
        var name by remember(layer.id) { mutableStateOf(layer.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename layer") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameLayer(layer, name)
                    renaming = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerRow(
    vm: EditorViewModel,
    layer: com.paintlab.app.Layer,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayerThumb(vm, layer)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(layer.name, fontSize = 13.sp, maxLines = 1)
            Text("${(layer.opacity * 100).roundToInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        IconButton(onClick = { vm.toggleVisible(layer) }) {
            Icon(
                if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                contentDescription = if (layer.visible) "Hide layer" else "Show layer",
            )
        }
    }
}

@Composable
private fun LayerThumb(vm: EditorViewModel, layer: com.paintlab.app.Layer) {
    val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    androidx.compose.foundation.Canvas(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFBDBDBD))
    ) {
        @Suppress("UNUSED_VARIABLE")
        val rev = vm.revision
        val bw = layer.bitmap.width.toFloat()
        val bh = layer.bitmap.height.toFloat()
        val s = minOf(size.width / bw, size.height / bh)
        val dw = bw * s
        val dh = bh * s
        val left = (size.width - dw) / 2f
        val top = (size.height - dh) / 2f
        drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(left, top), size = androidx.compose.ui.geometry.Size(dw, dh))
        drawContext.canvas.nativeCanvas.drawBitmap(
            layer.bitmap,
            null,
            android.graphics.RectF(left, top, left + dw, top + dh),
            paint,
        )
    }
}
