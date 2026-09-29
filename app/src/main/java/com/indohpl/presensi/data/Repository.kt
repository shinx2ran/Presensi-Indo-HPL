package com.indohpl.presensi.data

import android.content.Context
import com.indohpl.presensi.domain.GeoCheck
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
        val s = settingsStore.current()
        val late = LateRule.lateMinutes(capturedAt.toLocalTime(), s.workStart, s.toleranceMinutes)
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
}
