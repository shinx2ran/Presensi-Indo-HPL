package com.indohpl.presensi.domain

import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Status
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Anulir telat oleh owner: karyawan sebenarnya datang tepat waktu tetapi tidak bisa absen
 * (mis. HP kasir di dalam toko dan pintu belum dibuka). Catatan TELAT menjadi TEPAT, sehingga
 * hari itu menambah saldo seperti hari tepat waktu biasa. Menit telat asli dan alasannya disimpan
 * sebagai jejak, dan anulir bisa dibatalkan. Murni Kotlin supaya bisa diuji.
 */
object AnnulRule {
    const val DEFAULT_REASON = "Pintu toko belum dibuka"

    fun canAnnul(r: AttendanceRecord): Boolean = r.status == Status.TELAT && r.annulledLateMinutes == null

    fun canUndo(r: AttendanceRecord): Boolean = r.status == Status.TEPAT && r.annulledLateMinutes != null

    /** Catatan setelah dianulir, atau null bila bukan catatan telat (atau sudah dianulir). */
    fun annul(r: AttendanceRecord, reason: String): AttendanceRecord? {
        if (!canAnnul(r)) return null
        return r.copy(
            status = Status.TEPAT,
            lateMinutes = 0,
            annulledLateMinutes = r.lateMinutes,
            annulReason = reason.trim().ifEmpty { DEFAULT_REASON },
        )
    }

    /** Catatan setelah anulir dibatalkan (kembali TELAT dengan menit asli), atau null bila tidak dianulir. */
    fun undo(r: AttendanceRecord): AttendanceRecord? {
        if (!canUndo(r)) return null
        return r.copy(
            status = Status.TELAT,
            lateMinutes = r.annulledLateMinutes ?: 0,
            annulledLateMinutes = null,
            annulReason = "",
        )
    }

    /**
     * Perubahan saldo bulan itu bila [before] diganti [after] (positif saat anulir, negatif saat batal).
     * Dihitung ulang lewat [BonusCalculator.recap] supaya pembulatan, hari libur, dan batas maksimal sama persis
     * dengan saldo yang ditampilkan. Hari libur atau di luar hari kerja menghasilkan 0.
     */
    fun saldoChange(
        employee: Employee,
        monthRecords: List<AttendanceRecord>,
        before: AttendanceRecord,
        after: AttendanceRecord,
        today: LocalDate,
        workDayOfWeeks: Set<DayOfWeek>,
        holidays: Set<LocalDate>,
        config: BonusConfig,
    ): Long {
        val ym = YearMonth.from(LocalDate.parse(before.date))
        // Hari yang dianulir sudah lewat atau hari ini, jadi pastikan "today" tidak lebih awal dari tanggal itu.
        val asOf = maxOf(today, LocalDate.parse(before.date))
        val own = monthRecords.filter { it.employeeId == employee.id && it.date != before.date }
        fun earned(rec: AttendanceRecord) =
            BonusCalculator.recap(ym, asOf, listOf(employee), own + rec, workDayOfWeeks, holidays, config).single().earned
        return earned(after) - earned(before)
    }
}
