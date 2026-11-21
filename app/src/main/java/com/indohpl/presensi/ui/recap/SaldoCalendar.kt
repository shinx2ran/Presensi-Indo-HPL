package com.indohpl.presensi.ui.recap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.indohpl.presensi.domain.DayKind
import com.indohpl.presensi.domain.DayMark
import com.indohpl.presensi.domain.RecapFormatter
import com.indohpl.presensi.ui.theme.Amber
import com.indohpl.presensi.ui.theme.Green
import com.indohpl.presensi.ui.theme.Neutral
import com.indohpl.presensi.ui.theme.NeutralLight
import java.time.LocalDate

private val DAY_NAMES = listOf("Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min")
private val MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")

/**
 * Kalender sebulan "jangan putus rantainya". Hanya tampilan: ketuk tanggal untuk melihat satu baris keterangan.
 * Kolom pertama = Senin.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaldoCalendar(calendar: List<DayMark>, modifier: Modifier = Modifier) {
    if (calendar.isEmpty()) return
    var selected by remember(calendar) { mutableStateOf<LocalDate?>(null) }

    val offset = calendar.first().date.dayOfWeek.value - 1 // Senin = 0
    val cells: List<DayMark?> = List(offset) { null } + calendar
    val rows = cells.chunked(7).map { row -> row + List(7 - row.size) { null } }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            DAY_NAMES.forEach { name ->
                Text(
                    name,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { mark ->
                    if (mark == null) {
                        Spacer(Modifier.weight(1f).height(38.dp))
                    } else {
                        DayCell(
                            mark = mark,
                            isSelected = selected == mark.date,
                            onClick = { selected = if (selected == mark.date) null else mark.date },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        val sel = selected?.let { d -> calendar.firstOrNull { it.date == d } }
        Text(
            if (sel == null) "Ketuk tanggal untuk melihat detail." else dayDetail(sel),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (sel == null) FontWeight.Normal else FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LegendItem("Tepat") { Dot(fill = Green) }
            LegendItem("Telat") { Dot(ring = Amber) }
            LegendItem("Izin/Sakit") { Dot(fill = NeutralLight) }
            LegendItem("Belum/Tidak absen") { Dot(ring = MaterialTheme.colorScheme.outlineVariant) }
        }
    }
}

private fun dayDetail(mark: DayMark): String {
    val d = mark.date
    val label = "${DAY_NAMES[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MONTH_NAMES[d.monthValue - 1]}"
    val text = when (mark.kind) {
        DayKind.EARNED ->
            if (mark.credit > 0L) "Tepat waktu · " + RecapFormatter.rupiahPlus(mark.credit)
            else "Tepat waktu · maksimal tercapai"
        DayKind.LATE -> "Telat · tidak menambah"
        DayKind.EXCUSED -> "Izin/Sakit · tidak dihitung"
        DayKind.ABSENT -> "Tidak absen"
        DayKind.PENDING -> "Hari ini, belum absen"
        DayKind.FUTURE -> "Belum tiba"
        DayKind.OFF -> "Libur"
    }
    return "$label · $text"
}

@Composable
private fun DayCell(mark: DayMark, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var fill = Color.Transparent
    var ring: Color? = null
    var textColor: Color = scheme.onSurface
    var weight = FontWeight.Normal
    var label = mark.date.dayOfMonth.toString()
    when (mark.kind) {
        DayKind.EARNED -> {
            fill = Green
            textColor = Color.White
            weight = FontWeight.Bold
        }
        DayKind.LATE -> {
            ring = Amber
            textColor = Amber
        }
        DayKind.EXCUSED -> {
            fill = NeutralLight
            textColor = Neutral
            label = "–"
        }
        DayKind.ABSENT -> {
            ring = scheme.outlineVariant
            textColor = scheme.onSurfaceVariant
        }
        DayKind.PENDING -> {
            ring = Green
            textColor = Green
            weight = FontWeight.Bold
        }
        DayKind.FUTURE -> {
            ring = scheme.outlineVariant.copy(alpha = 0.5f)
            textColor = scheme.onSurface.copy(alpha = 0.45f)
        }
        DayKind.OFF -> {
            textColor = scheme.onSurface.copy(alpha = 0.3f)
        }
    }
    val ringColor: Color? = ring
    val cellFill: Color = fill
    Box(
        modifier = modifier.height(38.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .then(if (isSelected) Modifier.border(1.5.dp, scheme.onSurface.copy(alpha = 0.6f), CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(cellFill, CircleShape)
                    .then(if (ringColor != null) Modifier.border(1.5.dp, ringColor, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = weight, color = textColor)
            }
        }
    }
}

@Composable
private fun LegendItem(text: String, dot: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        dot()
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Dot(fill: Color = Color.Transparent, ring: Color? = null) {
    Box(
        Modifier
            .size(10.dp)
            .background(fill, CircleShape)
            .then(if (ring != null) Modifier.border(1.5.dp, ring, CircleShape) else Modifier),
    )
}
