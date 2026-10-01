package com.indohpl.presensi.ui

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.indohpl.presensi.cloud.CloudRecord
import com.indohpl.presensi.cloud.FirebaseCloud
import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Repository
import java.io.File
import java.time.YearMonth

/** Pemilih sumber data rekap: database HP ini atau Firestore. */
@Composable
fun SourceChips(useCloud: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        FilterChip(selected = !useCloud, onClick = { onChange(false) }, label = { Text("HP ini") })
        FilterChip(selected = useCloud, onClick = { onChange(true) }, label = { Text("Cloud") })
    }
}

/** Hasil pemuatan data cloud satu bulan. */
data class CloudMonth(val records: List<AttendanceRecord>, val photos: Map<String, String>, val error: String?)

suspend fun loadCloudMonth(context: Context, repository: Repository, ym: YearMonth): CloudMonth {
    val cfg = repository.settingsStore.currentCloud()
    if (!cfg.enabled || !cfg.isComplete) return CloudMonth(emptyList(), emptyMap(), "Cloud belum diatur di Pengaturan.")
    return try {
        val list: List<CloudRecord> = FirebaseCloud(context, cfg).getMonth(ym)
        CloudMonth(
            records = list.map { it.record },
            photos = list.mapNotNull { c -> c.photoBase64?.let { c.record.cloudId to it } }.toMap(),
            error = null,
        )
    } catch (e: Exception) {
        CloudMonth(emptyList(), emptyMap(), "Gagal memuat cloud: ${e.message ?: e.javaClass.simpleName}")
    }
}

/** Simpan foto base64 dari cloud ke cache supaya bisa dibuka PhotoDialog. */
fun cloudPhotoToFile(context: Context, cloudId: String, base64: String): String {
    val f = File(context.cacheDir, "cloud_$cloudId.jpg")
    if (!f.exists()) f.writeBytes(Base64.decode(base64, Base64.NO_WRAP))
    return f.absolutePath
}
