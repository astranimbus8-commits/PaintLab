package com.paintlab.app.io

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ExportFormat(val label: String, val mime: String, val ext: String) {
    PNG("PNG", "image/png", "png"),
    JPG("JPG", "image/jpeg", "jpg"),
    PDF("PDF", "application/pdf", "pdf"),
}

object ImageIO {

    /** Decodes any image the system understands into a mutable sRGB ARGB_8888 bitmap. */
    fun decode(
        ctx: Context,
        uri: Uri,
        maxSide: Int = 8192,
        // Keep ARGB_8888 under the ~100 MB limit of hardware-accelerated drawBitmap
        // (RecordingCanvas throws "trying to draw too large bitmap" above it).
        maxPixels: Long = 24_000_000L,
    ): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val w = info.size.width
            val h = info.size.height
            var s = 1.0
            if (max(w, h) > maxSide) s = maxSide.toDouble() / max(w, h)
            if (w.toLong() * h * s * s > maxPixels) s = sqrt(maxPixels.toDouble() / (w.toLong() * h))
            if (s < 1.0) {
                decoder.setTargetSize(
                    max(1, (w * s).roundToInt()),
                    max(1, (h * s).roundToInt()),
                )
            }
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888 && bmp.isMutable) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, true)
    }

    /** Writes [bmp] in the given format. Page size of a PDF comes from the canvas DPI. */
    fun write(out: OutputStream, bmp: Bitmap, format: ExportFormat, jpgQuality: Int, dpi: Double) {
        when (format) {
            ExportFormat.PNG -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            ExportFormat.JPG -> {
                val flat = if (bmp.hasAlpha()) onWhite(bmp) else bmp
                flat.compress(Bitmap.CompressFormat.JPEG, jpgQuality.coerceIn(1, 100), out)
            }
            ExportFormat.PDF -> writePdf(out, bmp, dpi)
        }
        out.flush()
    }

    private fun onWhite(bmp: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        c.drawBitmap(bmp, 0f, 0f, null)
        return b
    }

    private fun writePdf(out: OutputStream, bmp: Bitmap, dpi: Double) {
        // PDF units are points (1/72 inch). Cap to the PDF maximum page size.
        var wPt = (bmp.width / dpi * 72.0)
        var hPt = (bmp.height / dpi * 72.0)
        val maxPt = 14400.0
        val fit = minOf(1.0, maxPt / wPt, maxPt / hPt)
        wPt *= fit
        hPt *= fit
        val pw = max(1, wPt.roundToInt())
        val ph = max(1, hPt.roundToInt())
        val doc = PdfDocument()
        try {
            val info = PdfDocument.PageInfo.Builder(pw, ph, 1).create()
            val page = doc.startPage(info)
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawBitmap(bmp, null, Rect(0, 0, pw, ph), Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** Quick save: images go to Pictures/PaintLab, PDFs to Download/PaintLab. */
    fun saveToMediaStore(
        ctx: Context,
        bmp: Bitmap,
        format: ExportFormat,
        jpgQuality: Int,
        dpi: Double,
        baseName: String,
    ): String {
        val resolver = ctx.contentResolver
        val isPdf = format == ExportFormat.PDF
        val collection = if (isPdf) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = (if (isPdf) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_PICTURES) + "/PaintLab"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.${format.ext}")
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Could not create file")
        try {
            resolver.openOutputStream(uri)?.use { write(it, bmp, format, jpgQuality, dpi) }
                ?: error("Could not open file")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "$folder/$baseName.${format.ext}"
    }
}
