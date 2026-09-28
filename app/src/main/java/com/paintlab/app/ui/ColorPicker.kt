package com.paintlab.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.Locale

private val PALETTE = listOf(
    0xFF000000, 0xFF404040, 0xFF808080, 0xFFC0C0C0, 0xFFFFFFFF,
    0xFFE5484D, 0xFFF76B15, 0xFFFFC53D, 0xFF46A758, 0xFF12A594,
    0xFF0090FF, 0xFF3E63DD, 0xFF8E4EC6, 0xFFD6409F, 0xFF8D6E63,
    0xFFFFDBC4, 0xFFE8B08A, 0xFFB5764F, 0xFF7A4A2E, 0xFF3D2518,
).map { it.toInt() }

private fun Modifier.dragInside(onPos: (Offset, IntSize) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onPos(down.position, size)
        down.consume()
        while (true) {
            val e = awaitPointerEvent()
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) break
            onPos(c.position, size)
            c.consume()
        }
    }
}

@Composable
fun ColorPickerDialog(
    initial: Int,
    recent: List<Int>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) }
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val current = Color.hsv(hue.coerceIn(0f, 359.99f), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
    var hex by remember { mutableStateOf(toHex(initial)) }

    fun setFromInt(c: Int) {
        val a = FloatArray(3)
        android.graphics.Color.colorToHSV(c, a)
        hue = a[0]
        sat = a[1]
        value = a[2]
        hex = toHex(c)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text("Color", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))

                // Saturation / value square
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .dragInside { p, s ->
                            sat = (p.x / s.width).coerceIn(0f, 1f)
                            value = 1f - (p.y / s.height).coerceIn(0f, 1f)
                            hex = toHex(Color.hsv(hue, sat, value).toArgb())
                        }
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        val cx = sat * size.width
                        val cy = (1f - value) * size.height
                        drawCircle(Color.Black, 11f, Offset(cx, cy), style = Stroke(5f))
                        drawCircle(Color.White, 11f, Offset(cx, cy), style = Stroke(2.5f))
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Hue bar
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .dragInside { p, s ->
                            hue = (p.x / s.width).coerceIn(0f, 1f) * 359.99f
                            hex = toHex(Color.hsv(hue, sat, value).toArgb())
                        }
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        val stops = (0..6).map { Color.hsv((it * 60f).coerceAtMost(359.99f), 1f, 1f) }
                        drawRect(Brush.horizontalGradient(stops))
                        val x = hue / 360f * size.width
                        drawCircle(Color.White, size.height / 2.4f, Offset(x, size.height / 2f), style = Stroke(4f))
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(current)
                            .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { t ->
                            hex = t
                            parseHex(t)?.let { c ->
                                val a = FloatArray(3)
                                android.graphics.Color.colorToHSV(c, a)
                                hue = a[0]
                                sat = a[1]
                                value = a[2]
                            }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (recent.isNotEmpty()) {
                    Text("Recent", style = MaterialTheme.typography.labelMedium)
                    SwatchRow(recent) { setFromInt(it) }
                    Spacer(Modifier.height(6.dp))
                }
                Text("Palette", style = MaterialTheme.typography.labelMedium)
                SwatchRow(PALETTE.subList(0, 10)) { setFromInt(it) }
                SwatchRow(PALETTE.subList(10, 20)) { setFromInt(it) }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(current.toArgb()) }) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun SwatchRow(colors: List<Int>, onClick: (Int) -> Unit) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (c in colors) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    .clickable { onClick(c) }
            )
        }
    }
}

private fun toHex(c: Int): String = String.format(Locale.US, "#%06X", c and 0xFFFFFF)

private fun parseHex(t: String): Int? {
    val s = t.trim().removePrefix("#")
    if (s.length != 6) return null
    return s.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
}
