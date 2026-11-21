package com.indohpl.presensi.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.indohpl.presensi.data.CheckState
import com.indohpl.presensi.data.Inspector
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.domain.RecapFormatter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pemilih file yang membuka folder cadangan (EXTRA_INITIAL_URI). Hasil null = dibatalkan. */
private class PickBackupContract : ActivityResultContract<Uri, Uri?>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "*/*"))
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private sealed interface StartupState {
    data object None : StartupState
    data object RestoreOffer : StartupState
    data object Restoring : StartupState
    data class RestoreDone(val text: String, val success: Boolean) : StartupState
    data class UpdateSummary(
        val fromVersion: Long?,
        val versionName: String,
        val count: Int,
        val dateRange: Pair<String, String>?,
        val employees: Int,
        val monthlyBonus: Long,
        val problems: Int,
    ) : StartupState
}

private val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", LOCALE_ID)

private fun formatDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DISPLAY_DATE) }.getOrDefault(iso)

/**
 * Dipanggil sekali saat aplikasi dibuka. Setelah pembaruan: konfirmasi data berlanjut.
 * Setelah pasang baru tanpa data: tawarkan pulihkan dari cadangan.
 */
@Composable
fun StartupCheck(
    repository: Repository,
    onOpenSettings: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<StartupState>(StartupState.None) }
    var currentVersion by remember { mutableStateOf(0L) }

    // Android 9 ke bawah: cadangan ke folder Download butuh izin penyimpanan. Minta sekali saat aplikasi dibuka.
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) repository.backupSoon()
    }

    fun markSeenAndClose() {
        state = StartupState.None
        scope.launch { runCatching { repository.settingsStore.setLastSeenVersionCode(currentVersion) } }
    }

    val picker = rememberLauncherForActivityResult(PickBackupContract()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult // dibatalkan: tetap di tawaran
        state = StartupState.Restoring
        scope.launch {
            state = try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw IllegalArgumentException("File tidak bisa dibuka.")
                }
                val r = repository.restoreFromBackup(text, applySettings = true)
                onMessage("Data dipulihkan: ${r.imported} catatan masuk")
                StartupState.RestoreDone(
                    "Masuk ${r.imported} catatan." +
                        (if (r.unmatched > 0) " ${r.unmatched} catatan dilewati karena nama karyawan tidak cocok." else "") +
                        " Pengaturan dipakai. Buat PIN owner di Pengaturan.",
                    success = true,
                )
            } catch (e: IllegalArgumentException) {
                StartupState.RestoreDone(e.message ?: "File cadangan tidak sah.", success = false)
            } catch (e: Exception) {
                StartupState.RestoreDone("Gagal memulihkan: ${e.message ?: "kesalahan tidak dikenal"}", success = false)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (repository.localBackup.needsStoragePermission()) {
            runCatching { storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        }
        try {
            decideStartup(context, repository) { current, newState ->
                currentVersion = current
                state = newState
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            state = StartupState.None // pemeriksaan awal tidak boleh membuat aplikasi tertutup
        }
    }
    when (val s = state) {
        StartupState.None -> Unit

        is StartupState.UpdateSummary -> {
            val title = if (s.fromVersion == null) "Data ditemukan" else "Pembaruan berhasil"
            AlertDialog(
                onDismissRequest = { markSeenAndClose() },
                title = { Text(title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Versi ${s.versionName}.")
                        val range = s.dateRange
                        if (s.count == 0 || range == null) {
                            Text("Belum ada catatan presensi.")
                        } else {
                            Text("Data presensi dilanjutkan: ${s.count} catatan (${formatDate(range.first)} – ${formatDate(range.second)}).")
                        }
                        Text("${s.employees} karyawan · uang rajin maksimal ${RecapFormatter.rupiah(s.monthlyBonus)}/bulan.")
                        Text("Cadangan otomatis aktif ke ${repository.localBackup.folderLabel}.")
                        if (s.problems > 0) {
                            Text("${s.problems} hal perlu dicek di Pengaturan → Pemeriksaan aplikasi.")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { markSeenAndClose() }) { Text("Oke") }
                },
                dismissButton = {
                    if (s.problems > 0) {
                        TextButton(onClick = {
                            markSeenAndClose()
                            onOpenSettings()
                        }) { Text("Lihat pemeriksaan") }
                    }
                },
            )
        }

        StartupState.RestoreOffer -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Selamat datang") },
            text = {
                Text(
                    "Kalau HP ini pernah memakai aplikasi ini, pulihkan data dari cadangan supaya presensi dan saldo berlanjut. " +
                        "File cadangan ada di folder Download/PresensiIndoHPL.",
                )
            },
            confirmButton = {
                TextButton(onClick = { picker.launch(repository.localBackup.pickerInitialUri()) }) {
                    Text("Pulihkan dari cadangan")
                }
            },
            dismissButton = {
                TextButton(onClick = { markSeenAndClose() }) { Text("Mulai baru") }
            },
        )

        StartupState.Restoring -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Memulihkan data") },
            text = { Text("Mohon tunggu sebentar…") },
            confirmButton = {},
        )

        is StartupState.RestoreDone -> if (s.success) {
            AlertDialog(
                onDismissRequest = { markSeenAndClose() },
                title = { Text("Data dipulihkan") },
                text = { Text(s.text) },
                confirmButton = {
                    TextButton(onClick = { markSeenAndClose() }) { Text("Oke") }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { state = StartupState.RestoreOffer },
                title = { Text("Gagal memulihkan") },
                text = { Text(s.text) },
                confirmButton = {
                    TextButton(onClick = { picker.launch(repository.localBackup.pickerInitialUri()) }) {
                        Text("Coba file lain")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { state = StartupState.RestoreOffer }) { Text("Kembali") }
                },
            )
        }
    }
}


/**
 * Menentukan dialog pertama: tawarkan pulihkan (pasang baru tanpa data), ringkasan pembaruan (versi berubah atau data
 * dipulihkan otomatis oleh Android), atau tidak ada apa-apa.
 */
private suspend fun decideStartup(
    context: android.content.Context,
    repository: Repository,
    onResult: (current: Long, state: StartupState) -> Unit,
) {
    val inspector = Inspector(context, repository)
    val current = inspector.versionCode()
    val lastSeen = repository.settingsStore.lastSeenVersionCode()
    val count = repository.attendanceCount()
    val state = when {
        count == 0 && lastSeen == null -> StartupState.RestoreOffer
        lastSeen != current -> StartupState.UpdateSummary(
            fromVersion = lastSeen,
            versionName = inspector.versionName(),
            count = count,
            dateRange = repository.dateRange(),
            employees = repository.employeeDao.getAll().count { it.active },
            monthlyBonus = repository.settingsStore.current().monthlyBonus,
            problems = inspector.run().count { it.state != CheckState.OK },
        )
        else -> StartupState.None
    }
    onResult(current, state)
}
