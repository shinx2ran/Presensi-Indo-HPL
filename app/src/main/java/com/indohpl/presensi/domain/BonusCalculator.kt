package com.indohpl.presensi.domain

import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Status
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Parameter uang rajin. Dipisah dari DataStore supaya bisa diuji tanpa Android. */
data class BonusConfig(
    /** Uang rajin penuh per bulan, mis. 250.000. */
    val monthlyBonus: Long,
    /** Potongan per hari telat. 0 = otomatis: bonus dibagi jumlah hari kerja bulan itu. */
    val lateDeductionOverride: Long,
    /** Apakah hari alpa (tidak hadir tanpa izin/sakit) juga dipotong. */
    val deductAbsent: Boolean,
)

/** Hasil rekap satu karyawan untuk satu bulan. */
data class EmployeeRecap(
    val employee: Employee,
    val workDaysInMonth: Int,      // total hari kerja bulan itu (dikurangi libur)
    val workDaysElapsed: Int,      // hari kerja yang sudah lewat (<= hari ini)
    val hadir: Int,                // tepat + telat
    val tepat: Int,
    val telat: Int,
    val izin: Int,
    val sakit: Int,
    val alpa: Int,                 // hari kerja lewat tanpa catatan apa pun
    val totalLateMinutes: Int,
    val deductionPerDay: Long,
    val deductedDays: Int,         // telat (+ alpa jika diaktifkan)
    val totalDeduction: Long,
    val bonus: Long,               // uang rajin yang diterima (>= 0)
    val isFullMonth: Boolean,      // hadir tepat waktu di semua hari kerja bulan itu
)

object BonusCalculator {

    /** Daftar tanggal hari kerja dalam bulan [ym]. */
    fun workDays(ym: YearMonth, workDayOfWeeks: Set<DayOfWeek>, holidays: Set<LocalDate>): List<LocalDate> =
        (1..ym.lengthOfMonth())
            .map { ym.atDay(it) }
            .filter { it.dayOfWeek in workDayOfWeeks && it !in holidays }

    fun deductionPerDay(config: BonusConfig, workDaysInMonth: Int): Long =
        when {
            config.lateDeductionOverride > 0 -> config.lateDeductionOverride
            workDaysInMonth <= 0 -> 0L
            else -> Math.round(config.monthlyBonus.toDouble() / workDaysInMonth)
        }

    /**
     * Rekap semua karyawan untuk bulan [ym].
     * [today] menentukan hari kerja mana yang sudah "lewat" (untuk hitung alpa di bulan berjalan).
     */
    fun recap(
        ym: YearMonth,
        today: LocalDate,
        employees: List<Employee>,
        records: List<AttendanceRecord>,
        workDayOfWeeks: Set<DayOfWeek>,
        holidays: Set<LocalDate>,
        config: BonusConfig,
    ): List<EmployeeRecap> {
        val days = workDays(ym, workDayOfWeeks, holidays)
        val elapsed = days.filter { !it.isAfter(today) }
        val perDay = deductionPerDay(config, days.size)
        val byEmployee = records.groupBy { it.employeeId }

        return employees.map { emp ->
            val recs = byEmployee[emp.id].orEmpty()
            val byDate = recs.associateBy { it.date }
            val tepat = recs.count { it.status == Status.TEPAT }
            val telat = recs.count { it.status == Status.TELAT }
            val izin = recs.count { it.status == Status.IZIN }
            val sakit = recs.count { it.status == Status.SAKIT }
            val alpa = elapsed.count { byDate[it.toString()] == null }
            val deductedDays = telat + if (config.deductAbsent) alpa else 0
            val totalDeduction = (perDay * deductedDays).coerceAtMost(config.monthlyBonus)
            val fullMonth = days.isNotEmpty() &&
                days.all { byDate[it.toString()]?.status == Status.TEPAT }
            EmployeeRecap(
                employee = emp,
                workDaysInMonth = days.size,
                workDaysElapsed = elapsed.size,
                hadir = tepat + telat,
                tepat = tepat,
                telat = telat,
                izin = izin,
                sakit = sakit,
                alpa = alpa,
                totalLateMinutes = recs.sumOf { it.lateMinutes },
                deductionPerDay = perDay,
                deductedDays = deductedDays,
                totalDeduction = totalDeduction,
                bonus = (config.monthlyBonus - totalDeduction).coerceAtLeast(0),
                isFullMonth = fullMonth,
            )
        }
    }
}
