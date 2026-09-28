@file:OptIn(ExperimentalMaterial3Api::class)

package com.paintlab.app.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.paintlab.app.EditorViewModel
import com.paintlab.app.io.ImageIO
import com.paintlab.app.model.CANVAS_PRESETS
import com.paintlab.app.model.SizeEditorState
import com.paintlab.app.model.SizeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Bg(val label: String, val color: Int?) {
    WHITE("White", AColor.WHITE),
    TRANSPARENT("Transparent", null),
    BLACK("Black", AColor.BLACK),
}

@Composable
fun SetupScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val size = remember { SizeEditorState(2048, 2048, 300.0, SizeUnit.PX) }
    var bg by remember { mutableStateOf(Bg.WHITE) }
    var picture by remember { mutableStateOf<Bitmap?>(null) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            loading = true
            error = null
            scope.launch {
                try {
                    val bmp = withContext(Dispatchers.IO) { ImageIO.decode(ctx, uri) }
                    val ts = 256f / maxOf(bmp.width, bmp.height)
                    thumb = if (ts < 1f) Bitmap.createScaledBitmap(
                        bmp, maxOf(1, (bmp.width * ts).toInt()), maxOf(1, (bmp.height * ts).toInt()), true,
                    ) else bmp
                    picture = bmp
                    size.set(bmp.width, bmp.height, size.dpi, SizeUnit.PX)
                } catch (e: Throwable) {
                    error = "Couldn't open that picture: ${e.message}"
                } finally {
                    loading = false
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("PaintLab", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("New canvas", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        Text("Presets", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (p in CANVAS_PRESETS) {
                AssistChip(onClick = { size.applyPreset(p) }, label = { Text(p.name) })
            }
        }
        Spacer(Modifier.height(12.dp))

        SizeEditor(size)
        Spacer(Modifier.height(16.dp))

        Text("Background", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in Bg.entries) {
                FilterChip(selected = bg == b, onClick = { bg = b }, label = { Text(b.label) })
            }
        }
        Spacer(Modifier.height(16.dp))

        val pic = picture
        if (pic != null) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        bitmap = (thumb ?: pic).asImageBitmap(),
                        contentDescription = "Imported picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Picture: ${pic.width} × ${pic.height} px")
                        Text(
                            "The canvas was set to the picture size. Change it above if you like; " +
                                "the picture is fitted inside.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { picture = null }) { Text("Remove") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = !loading,
            ) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import picture")
            }
            Spacer(Modifier.width(12.dp))
            if (loading) CircularProgressIndicator(Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                try {
                    vm.newDocument(size.widthPx, size.heightPx, size.dpi, size.unit, bg.color, picture)
                } catch (e: OutOfMemoryError) {
                    vm.closeDocument()
                    error = "Not enough memory for a canvas this large. Try a smaller size."
                }
            },
            enabled = !loading && !size.tooLarge,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Create canvas")
        }
    }
}
