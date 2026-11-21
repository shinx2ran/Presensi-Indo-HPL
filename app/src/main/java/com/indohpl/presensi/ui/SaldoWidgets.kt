package com.indohpl.presensi.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.indohpl.presensi.domain.RecapFormatter
import com.indohpl.presensi.ui.theme.Amber
import com.indohpl.presensi.ui.theme.AmberLight
import com.indohpl.presensi.ui.theme.Green
import com.indohpl.presensi.ui.theme.GreenLight
import kotlin.math.roundToLong

/*
 * Komponen kecil untuk menampilkan saldo uang rajin yang BERTAMBAH.
 * Hijau = uang masuk. Dipakai di Beranda, layar hasil absen, dan tab Saldo supaya tampilannya seragam.
 */

/** Pil hijau "↑ +Rp 8.333". Untuk tambahan 0 pakai [NoGainChip]. */
@Composable
fun PlusChip(amount: Long, modifier: Modifier = Modifier, big: Boolean = false) {
    Row(
        modifier = modifier
            .background(GreenLight, RoundedCornerShape(50))
            .padding(horizontal = if (big) 14.dp else 10.dp, vertical = if (big) 6.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = Green, modifier = Modifier.size(if (big) 18.dp else 14.dp))
        Text(
            RecapFormatter.rupiahPlus(amount),
            color = Green,
            fontWeight = FontWeight.Bold,
            style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
        )
    }
}

/** Pil abu/kuning untuk hari yang tidak menambah saldo, mis. "+Rp 0 · telat". */
@Composable
fun NoGainChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Amber,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .background(AmberLight, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * Angka rupiah yang "menghitung naik" dari [from] ke [target] saat pertama tampil atau saat [target] berubah.
 * Angka akhir selalu persis [target].
 */
@Composable
fun AnimatedRupiah(
    target: Long,
    modifier: Modifier = Modifier,
    from: Long = 0L,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = Green,
    durationMillis: Int = 900,
) {
    val anim = remember { Animatable(from.toFloat()) }
    LaunchedEffect(target) {
        anim.animateTo(target.toFloat(), tween(durationMillis, easing = FastOutSlowInEasing))
        anim.snapTo(target.toFloat())
    }
    // Selama belum sampai tujuan tampilkan nilai animasi (mulai dari [from]); setelah selesai tampilkan target persis.
    val shown = if (!anim.isRunning && anim.value == target.toFloat()) target else anim.value.roundToLong()
    Text(RecapFormatter.rupiah(shown), modifier = modifier, style = style, color = color, fontWeight = FontWeight.ExtraBold)
}

/**
 * Bilah kemajuan saldo menuju maksimal bulan ini (hijau), dengan tanda target di [milestones] (mis. 100rb, 200rb,
 * 300rb, 350rb). Label target yang sudah tercapai berwarna hijau; target terakhir ditandai 🏆.
 */
@Composable
fun SaldoProgress(earned: Long, max: Long, modifier: Modifier = Modifier, milestones: List<Long> = emptyList()) {
    val target = if (max <= 0) 0f else (earned.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    val fraction by animateFloatAsState(target, tween(900, easing = FastOutSlowInEasing), label = "saldo")
    val tickColor = MaterialTheme.colorScheme.surface
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val radius = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(GreenLight, cornerRadius = radius)
            if (fraction > 0f) drawRoundRect(Green, size = Size(size.width * fraction, size.height), cornerRadius = radius)
            if (max > 0) {
                milestones.filter { it in 1 until max }.forEach { m ->
                    val x = size.width * (m.toFloat() / max.toFloat())
                    drawLine(tickColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                }
            }
        }
        if (max > 0 && milestones.isNotEmpty()) MilestoneLabels(milestones, max, earned)
    }
}

/** "100rb" untuk 100.000. */
fun shortRupiah(v: Long): String = if (v % 1000L == 0L) "${v / 1000}rb" else RecapFormatter.rupiah(v)

/** Label target diletakkan tepat di bawah tandanya, tidak keluar dari tepi. */
@Composable
private fun MilestoneLabels(milestones: List<Long>, max: Long, earned: Long) {
    val reached = Green
    val pending = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        content = {
            milestones.forEach { m ->
                Text(
                    if (m >= max) "\uD83C\uDFC6" else shortRupiah(m),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (earned >= m) reached else pending,
                    fontWeight = if (earned >= m) FontWeight.Bold else FontWeight.Normal,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                val center = (width * (milestones[i].toFloat() / max.toFloat())).toInt()
                val x = (center - p.width / 2).coerceIn(0, (width - p.width).coerceAtLeast(0))
                p.placeRelative(x, 0)
            }
        }
    }
}

/** "🔥 5 hari beruntun" bila streak ≥ 2; tidak tampil bila kurang. */
@Composable
fun StreakChip(streak: Int, modifier: Modifier = Modifier) {
    if (streak < 2) return
    Text(
        "🔥 $streak hari beruntun tepat waktu",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = Amber,
        modifier = modifier
            .background(AmberLight, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Baris kecil "label ...... nilai" untuk ringkasan. */
@Composable
fun LabelValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
