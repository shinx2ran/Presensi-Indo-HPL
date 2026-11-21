package com.indohpl.presensi.domain

import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Status
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Parameter uang rajin. Dipisah dari DataStore supaya bisa diuji tanpa Android. */
data class BonusConfig(
    /** Uang rajin maksimal per bulan, mis. 250.000 (didapat penuh kalau semua hari kerja tepat waktu). */
    val monthlyBonus: Long,
    /** Tambahan saldo per hari tepat waktu. 0 = otomatis: [monthlyBonus] dibagi rata ke hari kerja bulan itu. */
    val perDayOverride: Long,
    /** true = hari alpa tidak menambah saldo (bawaan). false = alpa diperlakukan seperti izin (tetap menambah). */
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
    val alpa: Int,                 // hari kerja sebelum hari ini tanpa catatan apa pun
    val outsideLocation: Int,      // hadir tapi selfie di luar radius toko / GPS tidak ada
    val totalLateMinutes: Int,
    /** Tambahan "rata-rata" per hari tepat waktu, untuk ditampilkan (mis. 8.333). */
    val perDayAmount: Long,
    /** Jumlah hari kerja yang menambah saldo (tepat + izin + sakit [+ alpa bila deductAbsent = false]). */
    val creditedDays: Int,
    /** Saldo uang rajin bulan ini sejauh ini (bertambah tiap hari tepat waktu). */
    val earned: Long,
    /** Saldo maksimal bulan ini bila semua hari kerja tepat waktu. */
    val maxThisMonth: Long,
    /** Saldo tertinggi yang masih bisa dicapai bila sisa hari kerja (termasuk hari ini bila belum absen) tepat waktu. */
    val maxReachable: Long,
    /**
     * Tambahan saldo per tanggal (yyyy-MM-dd) untuk hari kerja yang punya catatan: nilai > 0 untuk hari yang menambah,
     * 0 untuk telat. Jumlah semua nilai = [earned]. Hari alpa yang tetap menambah (deductAbsent = false) ikut tercatat.
     */
    val dailyCredits: Map<String, Long>,
    /** Hari kerja berturut-turut tepat waktu sampai hari ini (hari ini dilewati bila belum absen). */
    val streak: Int,
    val isFullMonth: Boolean,      // tepat waktu di semua hari kerja bulan itu
)

object BonusCalculator {

    /** Daftar tanggal hari kerja dalam bulan [ym]. */
    fun workDays(ym: YearMonth, workDayOfWeeks: Set<DayOfWeek>, holidays: Set<LocalDate>): List<LocalDate> =
        (1..ym.lengthOfMonth())
            .map { ym.atDay(it) }
            .filter { it.dayOfWeek in workDayOfWeeks && it !in holidays }

    /**
     * Saldo setelah [credited] hari menambah, dari [workDaysInMonth] hari kerja.
     * Otomatis: dibagi proporsional dan dibulatkan setengah ke atas, jadi semua hari kerja = tepat [BonusConfig.monthlyBonus].
     * Nilai tetap: [credited] x nilai per hari, tidak melebihi [BonusConfig.monthlyBonus].
     */
    fun saldoFor(config: BonusConfig, workDaysInMonth: Int, credited: Int): Long {
        if (workDaysInMonth <= 0 || credited <= 0) return 0L
        val c = credited.coerceAtMost(workDaysInMonth).toLong()
        return if (config.perDayOverride > 0) {
            (c * config.perDayOverride).coerceAtMost(config.monthlyBonus)
        } else {
            val n = workDaysInMonth.toLong()
            (2 * config.monthlyBonus * c + n) / (2 * n) // round(monthlyBonus * c / n), half-up, tanpa double
        }
    }

    /** Tambahan per hari untuk ditampilkan ("+Rp 8.333"). */
    fun perDayAmount(config: BonusConfig, workDaysInMonth: Int): Long = when {
        config.perDayOverride > 0 -> config.perDayOverride
        workDaysInMonth <= 0 -> 0L
        else -> saldoFor(config, workDaysInMonth, 1)
    }

    private fun credits(status: String?): Boolean =
        status == Status.TEPAT || status == Status.IZIN || status == Status.SAKIT

    /**
     * Rekap semua karyawan untuk bulan [ym].
     * [today] menentukan hari kerja mana yang sudah "lewat" (untuk hitung alpa, sisa hari, dan streak).
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
        val n = days.size
        val elapsed = days.filter { !it.isAfter(today) }
        val perDay = perDayAmount(config, n)
        val byEmployee = records.groupBy { it.employeeId }

        return employees.map { emp ->
            val recs = byEmployee[emp.id].orEmpty()
            val byDate = recs.associateBy { it.date }
            // Alpa hanya hari kerja yang SUDAH lewat; hari ini belum alpa karena karyawan masih bisa absen.
            val alpaDates = elapsed.filter { it.isBefore(today) && byDate[it.toString()] == null }.toSet()

            // Urutkan hari kerja yang punya catatan (atau alpa yang tetap menambah) menurut tanggal, lalu bagikan
            // selisih saldo kumulatif ke tiap hari supaya jumlah semua tambahan selalu sama persis dengan saldo.
            val daily = LinkedHashMap<String, Long>()
            var credited = 0
            for (d in days) {
                val key = d.toString()
                val status = byDate[key]?.status
                val gives = credits(status) || (status == null && !config.deductAbsent && d in alpaDates)
                when {
                    gives -> {
                        val before = saldoFor(config, n, credited)
                        credited++
                        daily[key] = saldoFor(config, n, credited) - before
                    }
                    status == Status.TELAT -> daily[key] = 0L
                }
            }
            val earned = saldoFor(config, n, credited)

            // Sisa kesempatan: hari kerja setelah hari ini, ditambah hari ini bila belum ada catatan.
            val remaining = days.count { it.isAfter(today) || (it == today && byDate[it.toString()] == null) }
            val maxReachable = saldoFor(config, n, credited + remaining)

            // Streak: mundur dari hari ini; hari ini yang belum absen dilewati (masih ada kesempatan).
            var streak = 0
            for (d in elapsed.asReversed()) {
                val status = byDate[d.toString()]?.status
                if (d == today && status == null) continue
                if (status == Status.TEPAT) streak++ else break
            }

            val tepat = recs.count { it.status == Status.TEPAT }
            val telat = recs.count { it.status == Status.TELAT }
            EmployeeRecap(
                employee = emp,
                workDaysInMonth = n,
                workDaysElapsed = elapsed.size,
                hadir = tepat + telat,
                tepat = tepat,
                telat = telat,
                izin = recs.count { it.status == Status.IZIN },
                sakit = recs.count { it.status == Status.SAKIT },
                alpa = alpaDates.size,
                outsideLocation = recs.count { (it.status == Status.TEPAT || it.status == Status.TELAT) && !it.inLocation },
                totalLateMinutes = recs.sumOf { it.lateMinutes },
                perDayAmount = perDay,
                creditedDays = credited,
                earned = earned,
                maxThisMonth = saldoFor(config, n, n),
                maxReachable = maxReachable,
                dailyCredits = daily,
                streak = streak,
                isFullMonth = n > 0 && days.all { byDate[it.toString()]?.status == Status.TEPAT },
            )
        }
    }
}
