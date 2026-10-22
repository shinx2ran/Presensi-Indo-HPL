package com.indohpl.presensi.data

import android.content.Context
import androidx.room.withTransaction
import com.indohpl.presensi.Site
import com.indohpl.presensi.cloud.CloudSync
import com.indohpl.presensi.domain.GeoCheck
import com.indohpl.presensi.domain.LateRule
import com.indohpl.presensi.domain.PhotoRetention
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Hasil pembersihan foto lama. Data presensi tidak ikut terhapus, hanya berkas fotonya. */
data class PhotoCleanupResult(
    val photosDeleted: Int,     // foto presensi yang lewat batas simpan
    val orphansDeleted: Int,    // foto lama tanpa catatan presensi (sisa hapus / ulang foto)
    val cacheDeleted: Int,      // berkas sementara (foto kamera mentah, ekspor) yang basi
    val photoBytesFreed: Long,  // ukuran foto selfie yang dihapus (dua baris pertama)
    val cacheBytesFreed: Long,  // ukuran berkas sementara yang dihapus
) {
    val bytesFreed: Long get() = photoBytesFreed + cacheBytesFreed
    val totalDeleted: Int get() = photosDeleted + orphansDeleted + cacheDeleted
}

/** Isi folder foto selfie di HP. */
data class PhotoStorage(val count: Int, val bytes: Long)

/** Pintu tunggal ke database + pengaturan. Dibuat sekali di [com.indohpl.presensi.PresensiApp]. */
/** Panjang catatan izin/sakit maksimum (cloud menyimpan 200 huruf). */
const val NOTE_MAX = 200

class Repository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(context)
    val settingsStore = SettingsStore(context)
    val employeeDao = db.employeeDao()
    val attendanceDao = db.attendanceDao()
    val holidayDao = db.holidayDao()
    val cloudDeletionDao = db.cloudDeletionDao()

    /** Folder foto selfie: <filesDir>/photos/yyyy-MM/ */
    val photoRoot: File = File(context.filesDir, "photos")

    private val cleanupLock = Mutex()

    /**
     * Satu kunci untuk semua perubahan pengaturan + hari libur: perubahan owner di HP dan penerapan versi cloud
     * tidak boleh saling menyela (kalau tidak, hari libur yang baru ditambah di HP bisa hilang tertimpa).
     */
    private val settingsLock = Mutex()

    /**
     * Pastikan semua karyawan awal varian ini ada di database (lihat `Site.seedEmployees` di src/<varian>/java).
     * Dipanggil setiap aplikasi dibuka; karyawan yang sudah ada tidak diubah (OnConflictStrategy.IGNORE),
     * jadi menambah baris baru di Site.kt cukup untuk memunculkan karyawan baru di HP yang sudah terpasang.
     */
    suspend fun ensureSeeded() {
        employeeDao.insertAll(Site.seedEmployees)
    }

    fun observeEmployees(): Flow<List<Employee>> = employeeDao.observeAll()
    fun observeToday(date: LocalDate): Flow<List<AttendanceRecord>> = attendanceDao.observeByDate(date.toString())
    fun observeMonth(ym: YearMonth): Flow<List<AttendanceRecord>> =
        attendanceDao.observeBetween(ym.atDay(1).toString(), ym.atEndOfMonth().toString())
    suspend fun getMonth(ym: YearMonth): List<AttendanceRecord> =
        attendanceDao.getBetween(ym.atDay(1).toString(), ym.atEndOfMonth().toString())
    fun observeHolidays(): Flow<List<Holiday>> = holidayDao.observeAll()

    /**
     * Simpan presensi hadir. [capturedAt] adalah waktu saat foto diambil (jam HP presensi).
     * Mengembalikan null jika karyawan sudah absen hari itu.
     */
    suspend fun recordCheckIn(
        employeeId: Long,
        capturedAt: LocalDateTime,
        photoPath: String,
        geo: GeoCheck?,
    ): AttendanceRecord? {
        val date = capturedAt.toLocalDate().toString()
        if (attendanceDao.find(employeeId, date) != null) return null
        val employee = employeeDao.getById(employeeId) ?: return null
        val s = settingsStore.current()
        // Aturan jam masuk per gender; nilai bawaan per varian ada di Site, bisa diubah owner di Pengaturan.
        val late = LateRule.lateMinutes(
            capturedAt.toLocalTime(),
            s.workStartFor(employee.gender),
            s.toleranceFor(employee.gender),
        )
        val rec = AttendanceRecord(
            employeeId = employeeId,
            date = date,
            timestamp = capturedAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            timeIn = capturedAt.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            status = if (late > 0) Status.TELAT else Status.TEPAT,
            lateMinutes = late,
            photoPath = photoPath,
            latitude = geo?.latitude,
            longitude = geo?.longitude,
            accuracy = geo?.accuracy,
            distanceMeters = geo?.distanceMeters,
            inLocation = geo?.inLocation ?: false,
            locationNote = geo?.note ?: "GPS tidak didapat",
        )
        val id = attendanceDao.insert(rec)
        CloudSync.schedule(appContext)
        return rec.copy(id = id)
    }

    /** Tandai izin/sakit secara manual (tanpa selfie). */
    suspend fun recordExcused(employeeId: Long, date: LocalDate, status: String, note: String): Boolean {
        val cleanNote = note.trim().take(NOTE_MAX) // sama dengan batas yang disimpan cloud
        if (attendanceDao.find(employeeId, date.toString()) != null) return false
        attendanceDao.insert(
            AttendanceRecord(
                employeeId = employeeId,
                date = date.toString(),
                timestamp = System.currentTimeMillis(),
                timeIn = "",
                status = status,
                lateMinutes = 0,
                photoPath = null,
                note = cleanNote,
            ),
        )
        CloudSync.schedule(appContext)
        return true
    }

    suspend fun deleteRecord(record: AttendanceRecord) {
        attendanceDao.delete(record)
        record.photoPath?.let { runCatching { File(it).delete() } }
        // Antrekan hapus di cloud bila HP tersambung, apa pun penanda "synced" di salinan ini: penanda itu bisa basi
        // (dialog menyimpan salinan lama, atau unggahan sedang berjalan). Server mengabaikan hapus untuk baris yang
        // belum pernah terkirim, dan hapus selalu diproses sebelum presensi baru di hari yang sama.
        if (record.synced || settingsStore.currentCloud().isLinked) {
            cloudDeletionDao.insert(CloudDeletion(record.cloudId))
            CloudSync.schedule(appContext)
        }
    }

    /** Kirim ulang semua presensi ke cloud (mis. bila data di dashboard tidak lengkap). */
    suspend fun resyncAll() {
        attendanceDao.markAllUnsynced()
        CloudSync.syncSoon(appContext, force = true)
    }

    // ---------- Pengaturan yang diubah owner di HP (ikut dikirim ke dashboard bila tersambung) ----------

    suspend fun saveSettingsByOwner(s: AppSettings) {
        val linked = settingsLock.withLock { settingsStore.saveByOwner(s) }
        if (linked) CloudSync.schedule(appContext)
    }

    suspend fun addHoliday(holiday: Holiday) {
        settingsLock.withLock { holidayDao.upsert(holiday) }
        holidaysChangedByOwner()
    }

    suspend fun removeHoliday(holiday: Holiday) {
        settingsLock.withLock { holidayDao.delete(holiday) }
        holidaysChangedByOwner()
    }

    private suspend fun holidaysChangedByOwner() {
        if (settingsLock.withLock { settingsStore.markSettingsDirtyIfLinked() }) CloudSync.schedule(appContext)
    }

    /**
     * Terapkan pengaturan dari dashboard (versi [version]) bila lebih baru dan HP tidak punya perubahan yang belum
     * terkirim. Diperiksa ulang di dalam kunci yang sama dengan perubahan owner di HP. true = diterapkan.
     */
    suspend fun applyCloudSettings(version: Long, data: JSONObject): Boolean = settingsLock.withLock {
        val state = settingsStore.currentCloudState()
        if (version <= state.appliedVersion || state.settingsDirty) return@withLock false
        val (newSettings, newHolidays) = SettingsJson.fromJson(data, settingsStore.current(), holidayDao.getAll())
        replaceHolidays(newHolidays)
        settingsStore.applyCloudSettings(newSettings, version)
    }

    // ---------- Dipakai sinkron cloud ----------

    /** Ganti seluruh daftar hari libur dengan versi dari dashboard (satu transaksi). */
    suspend fun replaceHolidays(holidays: List<Holiday>) {
        db.withTransaction {
            holidayDao.deleteAll()
            holidayDao.insertAll(holidays)
        }
    }

    /**
     * Terapkan daftar karyawan dari dashboard: karyawan baru ditambahkan, yang berubah (nama, gender, bagian, aktif)
     * diperbarui. Karyawan di HP tidak pernah dihapus. Mengembalikan jumlah yang ditambah/diubah.
     */
    suspend fun upsertEmployees(employees: List<Employee>): Int {
        var changed = 0
        for (e in employees) {
            val local = employeeDao.getById(e.id)
            if (local == null) {
                employeeDao.insertAll(listOf(e))
                changed++
            } else if (local != e) {
                employeeDao.update(e)
                changed++
            }
        }
        return changed
    }

    // ---------- Foto selfie: hemat memori HP ----------

    /** Jumlah dan ukuran foto selfie yang tersimpan di HP. */
    suspend fun photoStorage(): PhotoStorage = withContext(Dispatchers.IO) {
        var count = 0
        var bytes = 0L
        photoRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".jpg", ignoreCase = true) }.forEach {
            count++
            bytes += it.length()
        }
        PhotoStorage(count, bytes)
    }

    /**
     * Hapus foto selfie yang lebih lama dari batas simpan (Pengaturan, bawaan 60 hari). Data presensinya tetap ada;
     * hanya kolom photoPath yang dikosongkan. Juga menghapus foto lama tanpa catatan (sisa salah tekan / ulang foto),
     * folder bulan yang kosong, dan berkas sementara di cache yang umurnya lebih dari 1 hari.
     */
    suspend fun cleanupOldPhotos(today: LocalDate = LocalDate.now()): PhotoCleanupResult = cleanupLock.withLock {
        withContext(Dispatchers.IO) {
            var photos = 0
            var orphans = 0
            var cache = 0
            var bytes = 0L       // foto selfie
            var cacheBytes = 0L  // berkas sementara
            val cutoff = PhotoRetention.cutoff(today, settingsStore.current().photoRetentionDays)
            if (cutoff != null) {
                // 1) Foto presensi yang tanggalnya sebelum batas.
                for (r in attendanceDao.getWithPhotoBefore(cutoff.toString())) {
                    val path = r.photoPath ?: continue
                    if (!PhotoRetention.isExpired(r.date, cutoff)) continue
                    val f = File(path)
                    val size = if (f.isFile) f.length() else 0L
                    val gone = !f.exists() || f.delete()
                    if (gone) {
                        attendanceDao.clearPhotoPath(r.id)
                        if (size > 0) {
                            photos++
                            bytes += size
                        }
                    }
                }
                // 2) Foto lama yang tidak dipakai catatan mana pun.
                val referenced = attendanceDao.getWithPhoto().mapNotNull { it.photoPath?.let(::photoKey) }.toHashSet()
                val cutoffMs = PhotoRetention.cutoffMillis(cutoff)
                photoRoot.walkBottomUp()
                    .filter { it.isFile && it.name.endsWith(".jpg", ignoreCase = true) }
                    .filter { it.lastModified() < cutoffMs && photoKey(it.path) !in referenced }
                    .toList()
                    .forEach { f ->
                        val size = f.length()
                        if (f.delete()) {
                            orphans++
                            bytes += size
                        }
                    }
                // 3) Folder bulan (yyyy-MM) yang sudah kosong.
                photoRoot.listFiles()?.filter { it.isDirectory && it.list()?.isEmpty() == true }?.forEach { it.delete() }
            }
            // 4) Berkas sementara: foto kamera mentah (capture_*.jpg), foto cloud lama (cloud_*.jpg), ekspor rekap.
            val now = System.currentTimeMillis()
            val cacheDir = appContext.cacheDir
            val stale = mutableListOf<File>()
            cacheDir.listFiles()?.filterTo(stale) { f ->
                f.isFile && f.name.endsWith(".jpg", ignoreCase = true) &&
                    (f.name.startsWith("capture_") || f.name.startsWith("cloud_")) &&
                    PhotoRetention.isStaleCache(f.lastModified(), now)
            }
            File(cacheDir, "exports").listFiles()?.filterTo(stale) { f -> f.isFile && PhotoRetention.isStaleCache(f.lastModified(), now) }
            for (f in stale) {
                val size = f.length()
                if (f.delete()) {
                    cache++
                    cacheBytes += size
                }
            }
            PhotoCleanupResult(photos, orphans, cache, photoBytesFreed = bytes, cacheBytesFreed = cacheBytes)
        }
    }

    /** Kunci pembanding foto: "yyyy-MM/nama.jpg" (tidak terpengaruh perbedaan penulisan folder data aplikasi). */
    private fun photoKey(path: String): String {
        val f = File(path)
        return "${f.parentFile?.name.orEmpty()}/${f.name}"
    }
}
