package com.indohpl.presensi.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.indohpl.presensi.domain.LateRule
import com.indohpl.presensi.util.Biometric
import com.indohpl.presensi.util.LocationHelper
import java.security.MessageDigest
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

enum class CheckState { OK, WARN, FAIL }

/** Perbaikan yang bisa ditawarkan Inspector. [needsActivity] = harus ditangani layar (Intent / izin), bukan [Inspector.fix]. */
enum class FixAction(val needsActivity: Boolean) {
    RESEED_EMPLOYEES(false),
    CLEAR_MISSING_PHOTO_LINKS(false),
    DELETE_ORPHAN_PHOTOS(false),
    RUN_CLEANUP_NOW(false),
    BACKUP_NOW(false),
    OPEN_APP_SETTINGS(true),
    OPEN_DATE_SETTINGS(true),
    REQUEST_STORAGE_PERMISSION(true),
    OPEN_UPDATE_LINK(true),
}

data class CheckResult(
    val id: String,
    val title: String,
    val state: CheckState,
    val detail: String,
    val fix: FixAction?,
    val fixLabel: String?,
)

/** Memeriksa kesehatan aplikasi + HP dan menawarkan perbaikan. Tidak pernah melempar error. */
class Inspector(private val context: Context, private val repository: Repository) {
    companion object {
        const val PERMANENT_CERT_SHA256 = "97e5c5e2c65bdf65837760d5a5277055e4e7a533d8133ce474426a6470c51861"
        const val UPDATE_URL = "https://github.com/shinx2ran/Presensi-Indo-HPL/raw/apk-indohpl/PresensiIndoHPL.apk"

        private const val MB = 1024L * 1024L
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }

    private val idLocale: Locale = Locale.forLanguageTag("id-ID")

    @Suppress("DEPRECATION")
    private fun packageInfo() = context.packageManager.getPackageInfo(context.packageName, 0)

    fun versionName(): String = runCatching { packageInfo().versionName }.getOrNull() ?: "?"

    fun versionCode(): Long = runCatching { PackageInfoCompat.getLongVersionCode(packageInfo()) }.getOrDefault(0L)

    /** SHA-256 (hex huruf kecil) sertifikat penanda tangan APK yang terpasang; null jika tidak terbaca. */
    @Suppress("DEPRECATION")
    fun signingCertSha256(): String? = try {
        val pm = context.packageManager
        val bytes: ByteArray? = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()?.toByteArray()
        }
        bytes?.let { b -> MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } }
    } catch (e: Exception) {
        null
    }

    suspend fun run(today: LocalDate = LocalDate.now()): List<CheckResult> = listOf(
        safe("versi", "Versi & kunci") { checkVersion() },
        safe("database", "Database") { checkDatabase() },
        safe("karyawan", "Karyawan") { checkEmployees() },
        safe("pengaturan", "Pengaturan") { checkSettings() },
        safe("izin", "Izin HP") { checkPermissions() },
        safe("jam", "Jam HP") { checkClock(today) },
        safe("foto", "Foto selfie") { checkPhotos() },
        safe("pembersihan", "Pembersihan otomatis") { checkCleanup() },
        safe("cadangan", "Cadangan") { checkBackup() },
        safe("ruang", "Ruang HP") { checkSpace() },
    )

    private suspend fun safe(id: String, title: String, block: suspend () -> CheckResult): CheckResult =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckResult(id, title, CheckState.FAIL, "Pemeriksaan gagal: ${e.message ?: e.javaClass.simpleName}", null, null)
        }

    // ---------- Pemeriksaan ----------

    private fun checkVersion(): CheckResult {
        val ok = signingCertSha256() == PERMANENT_CERT_SHA256
        return CheckResult(
            id = "versi",
            title = "Versi & kunci",
            state = if (ok) CheckState.OK else CheckState.WARN,
            detail = if (ok) {
                "Versi ${versionName()}. Pembaruan bisa dipasang menimpa, data tetap."
            } else {
                "APK ini tidak memakai kunci permanen; pembaruan resmi tidak bisa menimpa."
            },
            fix = FixAction.OPEN_UPDATE_LINK,
            fixLabel = "Unduh versi terbaru",
        )
    }

    private suspend fun checkDatabase(): CheckResult {
        val integrity = repository.integrityCheck()
        if (!integrity.equals("ok", ignoreCase = true)) {
            return CheckResult(
                "database", "Database", CheckState.FAIL,
                "Database bermasalah: $integrity. Ekspor rekap dan hubungi pembuat aplikasi", null, null,
            )
        }
        val n = repository.attendanceCount()
        val range = repository.dateRange()
        val detail = if (n > 0 && range != null) "Sehat · $n catatan (${range.first} s/d ${range.second})" else "Sehat · belum ada catatan"
        return CheckResult("database", "Database", CheckState.OK, detail, null, null)
    }

    private suspend fun checkEmployees(): CheckResult {
        val byId = repository.employeeDao.getAll().associateBy { it.id }
        val defaults = Repository.DEFAULT_EMPLOYEES
        val missing = defaults.count { byId[it.id] == null }
        if (missing > 0) {
            return CheckResult(
                "karyawan", "Karyawan", CheckState.WARN, "$missing karyawan bawaan hilang",
                FixAction.RESEED_EMPLOYEES, "Kembalikan karyawan",
            )
        }
        val inactive = defaults.count { byId[it.id]?.active == false }
        if (inactive > 0) {
            return CheckResult("karyawan", "Karyawan", CheckState.WARN, "$inactive karyawan bawaan berstatus nonaktif", null, null)
        }
        return CheckResult("karyawan", "Karyawan", CheckState.OK, "${defaults.size} karyawan bawaan lengkap dan aktif", null, null)
    }

    private suspend fun checkSettings(): CheckResult {
        val s = repository.settingsStore.current()
        val problems = mutableListOf<String>()
        if (LateRule.parseWorkStart(s.workStartFemale) == null) problems += "jam masuk perempuan tidak valid"
        if (LateRule.parseWorkStart(s.workStartMale) == null) problems += "jam masuk laki-laki tidak valid"
        if (s.monthlyBonus <= 0) problems += "uang rajin belum diisi"
        if (s.perDayAmount > s.monthlyBonus) problems += "tambahan per hari lebih besar dari uang rajin"
        if (s.workDays.isEmpty()) problems += "hari kerja kosong"
        if (!s.hasStoreLocation) problems += "lokasi toko belum diatur"
        if (problems.isNotEmpty()) {
            return CheckResult(
                "pengaturan", "Pengaturan", CheckState.WARN,
                "Perlu dicek: ${problems.joinToString(", ")}. Atur di bagian bawah Pengaturan", null, null,
            )
        }
        val rupiah = NumberFormat.getInstance(idLocale).format(s.monthlyBonus)
        return CheckResult(
            "pengaturan", "Pengaturan", CheckState.OK,
            "Uang rajin Rp $rupiah, jam masuk P ${s.workStartFemale} / L ${s.workStartMale}", null, null,
        )
    }

    private fun checkPermissions(): CheckResult {
        val problems = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            problems += "izin kamera belum diberikan"
        }
        if (!LocationHelper.hasPermission(context)) problems += "izin lokasi belum diberikan"
        Biometric.availabilityProblem(context)?.let { problems += it }
        if (problems.isEmpty()) {
            return CheckResult("izin", "Izin HP", CheckState.OK, "Kamera, lokasi, dan sidik jari / PIN HP siap", null, null)
        }
        return CheckResult(
            "izin", "Izin HP", CheckState.WARN, "Belum lengkap: ${problems.joinToString("; ")}",
            FixAction.OPEN_APP_SETTINGS, "Buka izin aplikasi",
        )
    }

    private suspend fun checkClock(today: LocalDate): CheckResult {
        val autoTimeOff = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 1) == 0
        }.getOrDefault(false)
        val newest = repository.attendanceDao.newestDate()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (newest != null && newest.isAfter(today)) {
            val extra = if (autoTimeOff) ". Tanggal & waktu otomatis mati" else ""
            return CheckResult("jam", "Jam HP", CheckState.FAIL, "Ada absen bertanggal masa depan: $newest$extra", null, null)
        }
        if (autoTimeOff) {
            return CheckResult(
                "jam", "Jam HP", CheckState.WARN, "Tanggal & waktu otomatis mati",
                FixAction.OPEN_DATE_SETTINGS, "Buka setelan jam",
            )
        }
        return CheckResult("jam", "Jam HP", CheckState.OK, "Tanggal & waktu otomatis aktif", null, null)
    }

    private suspend fun checkPhotos(): CheckResult {
        val missing = repository.missingPhotoRecords()
        if (missing.isNotEmpty()) {
            return CheckResult(
                "foto", "Foto selfie", CheckState.WARN,
                "${missing.size} catatan punya foto yang filenya sudah hilang dari HP",
                FixAction.CLEAR_MISSING_PHOTO_LINKS, "Rapikan tautan foto",
            )
        }
        val orphans = repository.orphanPhotos()
        if (orphans.isNotEmpty()) {
            val size = formatMb(orphans.sumOf { it.length() })
            return CheckResult(
                "foto", "Foto selfie", CheckState.WARN,
                "${orphans.size} file foto tidak dipakai catatan mana pun ($size)",
                FixAction.DELETE_ORPHAN_PHOTOS, "Hapus foto yatim (${orphans.size} file, $size)",
            )
        }
        return CheckResult("foto", "Foto selfie", CheckState.OK, "Semua foto tertaut dengan benar", null, null)
    }

    private suspend fun checkCleanup(): CheckResult {
        val at = repository.settingsStore.lastCleanupAt()
        if (at != null && System.currentTimeMillis() - at <= 3 * DAY_MS) {
            return CheckResult("pembersihan", "Pembersihan otomatis", CheckState.OK, "Terakhir ${formatTime(at)}", null, null)
        }
        val detail = if (at == null) "Belum pernah berjalan" else "Terakhir ${formatTime(at)}, sudah lebih dari 3 hari"
        return CheckResult(
            "pembersihan", "Pembersihan otomatis", CheckState.WARN, detail,
            FixAction.RUN_CLEANUP_NOW, "Jalankan sekarang",
        )
    }

    private suspend fun checkBackup(): CheckResult {
        val backup = repository.localBackup
        val last = repository.settingsStore.lastBackup.first()
        if (backup.needsStoragePermission()) {
            return CheckResult(
                "cadangan", "Cadangan",
                if (last == null) CheckState.FAIL else CheckState.WARN,
                "Izin penyimpanan belum diberikan, cadangan ke ${backup.folderLabel} tidak bisa dibuat",
                FixAction.REQUEST_STORAGE_PERMISSION, "Izinkan penyimpanan",
            )
        }
        if (last == null && repository.attendanceCount() == 0) {
            return CheckResult(
                "cadangan", "Cadangan", CheckState.OK,
                "Cadangan dibuat otomatis setelah ada catatan presensi pertama", null, null,
            )
        }
        if (last == null) {
            return CheckResult(
                "cadangan", "Cadangan", CheckState.WARN, "Belum ada cadangan",
                FixAction.BACKUP_NOW, "Cadangkan sekarang",
            )
        }
        val (at, file) = last
        if (System.currentTimeMillis() - at > 2 * DAY_MS) {
            return CheckResult(
                "cadangan", "Cadangan", CheckState.WARN, "Cadangan terakhir ${formatTime(at)}, sudah lebih dari 2 hari",
                FixAction.BACKUP_NOW, "Cadangkan sekarang",
            )
        }
        return CheckResult(
            "cadangan", "Cadangan", CheckState.OK, "Terakhir ${formatTime(at)} · ${backup.folderLabel}/$file", null, null,
        )
    }

    private fun checkSpace(): CheckResult {
        val freeMb = context.filesDir.usableSpace / MB
        return if (freeMb < 200) {
            CheckResult("ruang", "Ruang HP", CheckState.WARN, "Ruang bebas tinggal $freeMb MB. Kosongkan memori HP", null, null)
        } else {
            CheckResult("ruang", "Ruang HP", CheckState.OK, "Ruang bebas $freeMb MB", null, null)
        }
    }

    // ---------- Perbaikan ----------

    /** Jalankan perbaikan yang tidak butuh layar. Mengembalikan pesan hasil untuk ditampilkan. */
    suspend fun fix(action: FixAction): String {
        if (action.needsActivity) return "Butuh layar"
        return try {
            when (action) {
                FixAction.RESEED_EMPLOYEES -> "${repository.seedMissingEmployees()} karyawan dikembalikan"
                FixAction.CLEAR_MISSING_PHOTO_LINKS -> "${repository.clearMissingPhotoLinks()} tautan foto dirapikan"
                FixAction.DELETE_ORPHAN_PHOTOS -> {
                    val (n, bytes) = repository.deleteOrphanPhotos()
                    "$n foto dihapus, ${formatMb(bytes)} dibebaskan"
                }
                FixAction.RUN_CLEANUP_NOW -> {
                    // Pakai pengaman otomatis (jam HP salah / >1 bulan sekaligus tidak dihapus). Hapus paksa hanya lewat
                    // tombol "Bersihkan sekarang" berkonfirmasi di bagian Pembersihan otomatis.
                    val n = repository.cleanupOldData(automatic = true)
                    if (n > 0) "Pembersihan dijalankan: $n catatan lama dihapus" else "Pembersihan diperiksa: tidak ada yang perlu dihapus sekarang"
                }
                FixAction.BACKUP_NOW -> repository.backupNow().fold(
                    onSuccess = { "Cadangan disimpan: $it" },
                    onFailure = { "Cadangan belum dibuat: ${it.message ?: "terjadi kesalahan"}" },
                )
                else -> "Butuh layar"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Gagal: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun formatMb(bytes: Long): String = String.format(idLocale, "%.1f MB", bytes / MB.toDouble())

    private fun formatTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", idLocale))
}
