package com.indohpl.presensi.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Status
import com.indohpl.presensi.ui.theme.Amber
import com.indohpl.presensi.ui.theme.AmberLight
import com.indohpl.presensi.ui.theme.Blue
import com.indohpl.presensi.ui.theme.BlueLight
import com.indohpl.presensi.ui.theme.Green
import com.indohpl.presensi.ui.theme.GreenLight
import com.indohpl.presensi.ui.theme.Red
import com.indohpl.presensi.ui.theme.RedLight
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LOCALE_ID: Locale = Locale.forLanguageTag("id-ID")
val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", LOCALE_ID)
val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", LOCALE_ID)

fun LocalDate.longLabel(): String = format(LONG_DATE)
fun LocalDate.shortLabel(): String = format(SHORT_DATE)

/** Cari FragmentActivity dari Context Compose (bisa terbungkus ContextWrapper). */
fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Warna latar/teks untuk status presensi. */
fun statusColors(status: String): Pair<Color, Color> = when (status) {
    Status.TEPAT -> GreenLight to Green
    Status.TELAT -> RedLight to Red
    Status.IZIN -> AmberLight to Amber
    Status.SAKIT -> BlueLight to Blue
    else -> AmberLight to Amber
}

fun statusLabel(status: String): String = when (status) {
    Status.TEPAT -> "Tepat waktu"
    Status.TELAT -> "Telat"
    Status.IZIN -> "Izin"
    Status.SAKIT -> "Sakit"
    else -> status
}

@Composable
fun StatusPill(status: String, text: String = statusLabel(status), modifier: Modifier = Modifier) {
    val (bg, fg) = statusColors(status)
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun Avatar(employee: Employee, modifier: Modifier = Modifier) {
    val bg = if (employee.gender == "P") Color(0xFFF6D3E5) else Color(0xFFD5E3F7)
    val fg = if (employee.gender == "P") Color(0xFF8A2C5C) else Color(0xFF1F4A8F)
    Box(
        modifier = modifier.size(44.dp).background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            employee.name.take(1).uppercase(),
            color = fg,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Dialog untuk melihat foto selfie yang tersimpan. */
@Composable
fun PhotoDialog(path: String, title: String, onDismiss: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
        title = { Text(title) },
        text = {
            val bmp = bitmap
            if (bmp == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (File(path).exists()) CircularProgressIndicator() else Text("Foto tidak ditemukan")
                }
            } else {
                Image(
                    bitmap = bmp,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

