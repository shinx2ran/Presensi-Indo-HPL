package com.indohpl.presensi.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Membakar stempel waktu + nama ke dalam foto selfie, lalu menyimpannya sebagai JPEG.
 * Stempel ditulis langsung ke piksel gambar, jadi tetap ada meski file dipindah/dibagikan.
 */
object PhotoStamper {
    private const val MAX_SIDE = 1280
    private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd/MM/yyyy HH:mm:ss")

    /**
     * [extraLines] ditulis di bawah nama & waktu, mis. koordinat GPS dan status lokasi.
     */
    fun stamp(
        source: File,
        target: File,
        employeeName: String,
        capturedAt: LocalDateTime,
        footer: String,
        extraLines: List<String> = emptyList(),
    ): File {
        val bitmap = decodeScaledUpright(source)
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        bitmap.recycle()

        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val textSize = (w * 0.045f).coerceAtLeast(24f)
        val padding = textSize * 0.6f

        val line1 = employeeName
        val smallLines = listOf(capturedAt.format(FMT)) + extraLines + footer

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        val smallPaint = Paint(textPaint).apply {
            this.textSize = textSize * 0.8f
            typeface = Typeface.DEFAULT
        }
        val boxPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }

        val lineGap = textSize * 0.35f
        val boxHeight = padding * 2 + textSize + (smallPaint.textSize + lineGap) * smallLines.size
        canvas.drawRect(0f, h - boxHeight, w, h, boxPaint)

        var y = h - boxHeight + padding + textSize
        canvas.drawText(line1, padding, y, textPaint)
        smallLines.forEach { line ->
            y += smallPaint.textSize + lineGap
            canvas.drawText(line, padding, y, smallPaint)
        }

        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        out.recycle()
        return target
    }

    /** Decode dengan downsampling agar hemat memori, lalu putar sesuai EXIF supaya tegak. */
    private fun decodeScaledUpright(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE * 2 || bounds.outHeight / sample > MAX_SIDE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IllegalStateException("Gagal membaca foto")

        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        val scale = (MAX_SIDE.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
        matrix.postScale(scale, scale)
        val result = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (result !== raw) raw.recycle()
        return result
    }
}
