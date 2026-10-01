package com.indohpl.presensi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.data.AppSettings
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.domain.BonusCalculator
import com.indohpl.presensi.domain.RecapFormatter
import com.indohpl.presensi.ui.SourceChips
import com.indohpl.presensi.ui.loadCloudMonth
import com.indohpl.presensi.util.Export
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ekspor rekap bulanan (khusus owner, ada di dalam Pengaturan). */
@Composable
fun ExportPanel(repository: Repository, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var month by remember { mutableStateOf(YearMonth.now()) }
    val isCurrentMonth = month == YearMonth.now()
    val employees by repository.observeEmployees().collectAsStateWithLifecycle(emptyList())
    val holidays by repository.observeHolidays().collectAsStateWithLifecycle(emptyList())
    val settings by repository.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
    val cloudConfig by repository.settingsStore.cloud.collectAsStateWithLifecycle(null)
    val cloudAvailable = cloudConfig?.let { it.enabled && it.isComplete } == true
    var useCloud by remember { mutableStateOf(false) }

    suspend fun buildAll(): Triple<String, String, String>? {
        val records = if (useCloud) {
            val m = loadCloudMonth(context, repository, month)
            if (m.error != null) { onMessage(m.error); return null }
            m.records
        } else {
            repository.getMonth(month)
        }
        val recaps = BonusCalculator.recap(
            ym = month, today = LocalDate.now(), employees = employees.filter { it.active }, records = records,
            workDayOfWeeks = settings.workDays, holidays = holidays.map { LocalDate.parse(it.date) }.toSet(),
            config = settings.bonusConfig(),
        )
        val md = RecapFormatter.markdown(
            ym = month, recaps = recaps, records = records, employees = employees, config = settings.bonusConfig(),
            rulesText = settings.rulesText(), generatedAt = LocalDateTime.now().format(RecapFormatter.DATE_TIME_FMT),
        )
        return Triple(RecapFormatter.detailCsv(month, employees, records), RecapFormatter.summaryCsv(month, recaps), md)
    }

    Column {
        Text("Ekspor rekap bulanan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (cloudAvailable) SourceChips(useCloud = useCloud, onChange = { useCloud = it })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { month = month.minusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Bulan sebelumnya")
            }
            Text(
                RecapFormatter.monthLabel(month),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = { month = month.plusMonths(1) }, enabled = !isCurrentMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Bulan berikutnya")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        val (detail, summary, md) = buildAll() ?: return@launch
                        val files = withContext(Dispatchers.IO) {
                            listOf(
                                Export.writeText(context, "presensi_${month}_detail.csv", detail),
                                Export.writeText(context, "presensi_${month}_rekap.csv", summary),
                                Export.writeText(context, "presensi_${month}_rekap.md", md),
                            )
                        }
                        Export.shareFiles(context, files, "text/*", "Rekap presensi ${RecapFormatter.monthLabel(month)}")
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Share, null)
                Spacer(Modifier.width(6.dp))
                Text("Bagikan file")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val (_, _, md) = buildAll() ?: return@launch
                        Export.copyToClipboard(context, "Rekap presensi", md)
                        onMessage("Rekap disalin. Tempel ke Claude.")
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.ContentCopy, null)
                Spacer(Modifier.width(6.dp))
                Text("Salin teks")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "\"Bagikan file\" mengirim 3 file: detail harian (CSV), rekap per karyawan (CSV), dan rekap teks (Markdown). Pilih aplikasi Claude di menu bagikan, atau simpan ke Drive.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
