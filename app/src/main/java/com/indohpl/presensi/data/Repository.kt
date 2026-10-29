package com.indohpl.presensi.data

import android.content.Context
import com.indohpl.presensi.domain.CsvImport
import com.indohpl.presensi.domain.GeoCheck
import com.indohpl.presensi.domain.Retention
import com.indohpl.presensi.domain.LateRule
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow

/** Pintu tunggal ke database + pengaturan. Dibuat sekali di [com.indohpl.presensi.PresensiApp]. */
class Repository(context: Context) {
    private val db = AppDatabase.get(context)
    val settingsStore = SettingsStore(context)
    val employeeDao = db.employeeDao()
    val attendanceDao = db.attendanceDao()
    val holidayDao = db.holidayDao()

    /** Folder foto selfie: <filesDir>/photos/yyyy-MM/ */
    val photoRoot: File = File(context.filesDir, "photos")

    /** Daftar karyawan awal. Bisa diubah nanti lewat database tanpa mengubah kode. */
    suspend fun ensureSeeded() {
        if (employeeDao.count() == 0) {
            employeeDao.insertAll(
                listOf(
                    Employee(1, "Sara", "P"),
                    Employee(2, "Riyanti", "P"),
                    Employee(3, "Evan", "L"),
                    Employee(4, "Madi", "L"),
                    Employee(5, "Roni", "L"),
                ),
            )
        }
    }

    fun observeEmployees(): Flow<List<Employee>> = employeeDao.observeAll()
    fun observeToday(date: LocalDate): Flow<List<AttendanceRecord>> = attendanceDao.observeByDate(date.toString())
    fun observeMonth(ym: YearMonth): Flow<List<AttendanceRecord>> =
        attendanceDao.observeBetween(ym.atDay(1).toString(), ym.atEndOfMonth().toString())
    suspend fun getMonth(ym: YearMonth): List<AttendanceRecord> =
        attendanceDao.getBetween(ym.atDay(1).toString(), ym.atEndOfMonth().toString())
    fun observeHolidays(): Flow<List<Holiday>> = holidayDao.observeAll()

    /**
     * Simpan presensi hadir. [capturedAt] adalah waktu saat foto diambil (jam HP kasir).
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
        // Aturan per gender: perempuan 07:45 (+10), laki-laki 08:00 (+5) secara bawaan.
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
        return rec.copy(id = id)
    }

    /** Tandai izin/sakit secara manual (tanpa selfie). */
    suspend fun recordExcused(employeeId: Long, date: LocalDate, status: String, note: String): Boolean {
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
                note = note,
            ),
        )
        return true
    }

    suspend fun deleteRecord(record: AttendanceRecord) {
        attendanceDao.delete(record)
        record.photoPath?.let { runCatching { File(it).delete() } }
    }

    data class ImportResult(
        val imported: Int,
        val duplicates: Int,
        val unknownNames: Set<String>,
        val tooOld: Int,
        val errors: List<String>,
    )

    /**
     * Impor catatan dari file CSV detail (ekspor aplikasi versi lama). Dipakai untuk pindah ke APK berkunci
     * permanen tanpa komputer. Foto tidak ikut (CSV hanya menyimpan nama file). Catatan yang sudah ada
     * (karyawan + tanggal sama) dilewati; yang lebih lama dari batas simpan juga dilewati supaya tidak
     * langsung terhapus lagi oleh pembersihan otomatis.
     */
    suspend fun importCsv(files: List<Pair<String, String>>, today: LocalDate = LocalDate.now()): ImportResult {
        val employees = employeeDao.getAll()
        val byName = employees.associateBy { it.name.trim().lowercase() }
        val cutoff = Retention.cutoffDate(today, settingsStore.current().retentionMonths)
        var imported = 0
        var duplicates = 0
        var tooOld = 0
        val unknown = linkedSetOf<String>()
        val errors = mutableListOf<String>()
        files.forEach { (fileName, text) ->
            val parsed = CsvImport.parse(text)
            errors += parsed.errors.map { "$fileName: $it" }
            for (r in parsed.rows) {
                val emp = byName[r.name.trim().lowercase()]
                if (emp == null) { unknown += r.name; continue }
                if (r.date.isBefore(cutoff)) { tooOld++; continue }
                val time = if (r.timeIn.isNotEmpty()) java.time.LocalTime.parse(r.timeIn) else java.time.LocalTime.MIDNIGHT
                val id = attendanceDao.insertIgnore(
                    AttendanceRecord(
                        employeeId = emp.id,
                        date = r.date.toString(),
                        timestamp = r.date.atTime(time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        timeIn = r.timeIn,
                        status = r.status,
                        lateMinutes = r.lateMinutes,
                        photoPath = null,
                        note = r.note,
                        latitude = r.latitude,
                        longitude = r.longitude,
                        accuracy = r.accuracy,
                        distanceMeters = r.distanceMeters,
                        inLocation = r.inLocation,
                        locationNote = r.locationNote,
                    ),
                )
                if (id == -1L) duplicates++ else imported++
            }
        }
        return ImportResult(imported, duplicates, unknown, tooOld, errors)
    }

    /**
     * Pembersihan otomatis: hapus catatan (dan fotonya) yang lebih lama dari [AppSettings.retentionMonths].
     * Mengembalikan jumlah catatan yang dihapus.
     */
    suspend fun cleanupOldData(
        today: LocalDate = LocalDate.now(),
        months: Int? = null,
        automatic: Boolean = false,
    ): Int {
        val months = months ?: settingsStore.current().retentionMonths
        val cutoff = Retention.cutoffDate(today, months).toString()
        if (automatic) {
            val newest = attendanceDao.newestDate()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (!Retention.shouldAutoClean(today, newest, attendanceDao.monthsBefore(cutoff).size)) return 0
        }
        val old = attendanceDao.getBefore(cutoff)
        old.forEach { r -> r.photoPath?.let { runCatching { File(it).delete() } } }
        val deleted = attendanceDao.deleteBefore(cutoff)
        holidayDao.deleteBefore(cutoff)
        // Folder foto per bulan yang sudah kosong / lebih lama dari batas ikut dibuang.
        photoRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name < cutoff.take(7)) dir.deleteRecursively()
        }
        return deleted
    }
}
