@file:OptIn(ExperimentalMaterial3Api::class)

package com.paintlab.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paintlab.app.model.SizeEditorState
import com.paintlab.app.model.SizeUnit
import com.paintlab.app.model.Units

val PaintLabColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF6EA8FF),
    onPrimary = Color(0xFF002A60),
    secondary = Color(0xFFFFB86B),
    surface = Color(0xFF1E1F23),
    surfaceVariant = Color(0xFF2B2D33),
    background = Color(0xFF151619),
)

@Composable
fun PaintLabTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PaintLabColors, content = content)
}

val EraserIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Eraser",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(14.5f, 3.5f)
            lineTo(20.5f, 9.5f)
            lineTo(12f, 18f)
            lineTo(6f, 12f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 13f)
            lineTo(11f, 19f)
            lineTo(9.5f, 20.5f)
            lineTo(3.5f, 14.5f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 20f)
            lineTo(21f, 20f)
            lineTo(21f, 21.5f)
            lineTo(12f, 21.5f)
            close()
        }
    }.build()
}

/** A compact "label  [slider]  value" row. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(78.dp), fontSize = 13.sp)
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(valueText, Modifier.width(52.dp).padding(start = 6.dp), fontSize = 12.sp)
    }
}

/** Width / height / unit / DPI editor used for new canvases and resizing. */
@Composable
fun SizeEditor(state: SizeEditorState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.widthText,
                onValueChange = state::onWidthText,
                label = { Text("Width (${state.unit.label})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = state::swap) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap width and height")
            }
            OutlinedTextField(
                value = state.heightText,
                onValueChange = state::onHeightText,
                label = { Text("Height (${state.unit.label})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = state::toggleLock) {
                Icon(
                    if (state.lockAspect) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = "Lock aspect ratio",
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Unit", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (u in SizeUnit.entries) {
                FilterChip(
                    selected = state.unit == u,
                    onClick = { state.changeUnit(u) },
                    label = { Text(unitName(u)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.dpiText,
            onValueChange = state::onDpiText,
            label = { Text("Resolution (DPI)") },
            supportingText = {
                Text(
                    if (state.unit == SizeUnit.PX) "Changing DPI keeps the pixel size."
                    else "Changing DPI keeps the ${unitName(state.unit).lowercase()} size and changes pixels."
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(state.summary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        val mp = state.megapixels
        if (state.tooLarge) {
            Text(
                "Too large: ${Units.trimNumber(mp)} MP. The maximum is 25 MP " +
                    "(e.g. 5000 × 5000 px, or A4 at 300 dpi).",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (mp > 16) {
            Text(
                "Large canvas (${Units.trimNumber(mp)} MP). Each layer uses ${Units.trimNumber(mp * 4)} MB, " +
                    "so keep the layer count modest.",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

fun unitName(u: SizeUnit): String = when (u) {
    SizeUnit.PX -> "Pixels"
    SizeUnit.IN -> "Inches"
    SizeUnit.CM -> "Centimeters"
    SizeUnit.MM -> "Millimeters"
    SizeUnit.PT -> "Points"
}
