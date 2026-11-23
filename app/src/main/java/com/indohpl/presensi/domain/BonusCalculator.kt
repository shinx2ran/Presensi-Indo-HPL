package com.indohpl.presensi.domain

import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Status
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Parameter uang rajin. Dipisah dari DataStore supaya bisa diuji tanpa Android. */
data class BonusConfig(
    /** Uang rajin maksimal per bulan, mis. 350.000 (didapat penuh kalau semua hari kerja tepat waktu). */
    val monthlyBonus: Long,
    /** Tambahan saldo per hari tepat waktu. 0 = otomatis: [monthlyBonus] dibagi rata ke hari kerja bulan itu. */
    val perDayOverride: Long,
)

/** Keadaan satu tanggal untuk kalender bulanan ("jangan putus rantainya"). */
enum class DayKind {
    EARNED,   // hari kerja, tepat waktu: saldo bertambah
    LATE,     // hari kerja, telat: +Rp 0
    EXCUSED,  // hari kerja, izin/sakit: +Rp 0 (tidak dihitung)
    ABSENT,   // hari kerja yang sudah lewat tanpa catatan (bolos): +Rp 0
    PENDING,  // hari ini, hari kerja, belum absen: masih ada kesempatan
    FUTURE,   // hari kerja yang belum tiba
    OFF,      // bukan hari kerja / libur toko
}

data class DayMark(val date: java.time.LocalDate, val kind: DayKind, val credit: Long)

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
    val alpa: Int,                 // hari kerja sebelum hari ini tanpa catatan apa pun (bolos)
    val outsideLocation: Int,      // hadir tapi selfie di luar radius toko / GPS tidak ada
    val annulled: Int,             // telat yang dianulir owner (sudah dihitung sebagai tepat waktu)
    val totalLateMinutes: Int,
    /** Tambahan "rata-rata" per hari tepat waktu, untuk ditampilkan (mis. 8.333). */
    val perDayAmount: Long,
    /** Jumlah hari kerja yang menambah saldo (= hari kerja tepat waktu). */
    val creditedDays: Int,
    /** Saldo uang rajin bulan ini sejauh ini (bertambah tiap hari tepat waktu). */
    val earned: Long,
    /** Saldo maksimal bulan ini bila semua hari kerja tepat waktu. */
    val maxThisMonth: Long,
    /** Saldo tertinggi yang masih bisa dicapai bila sisa hari kerja (termasuk hari ini bila belum absen) tepat waktu. */
    val maxReachable: Long,
    /**
     * Tambahan saldo per tanggal (yyyy-MM-dd) untuk hari kerja yang punya catatan: nilai > 0 untuk tepat waktu,
     * 0 untuk telat/izin/sakit. Jumlah semua nilai = [earned]. Tanggal tanpa catatan atau di luar hari kerja tidak ada.
     */
    val dailyCredits: Map<String, Long>,
    /** Hari kerja sampai hari ini yang TIDAK menambah (telat, izin, sakit, bolos). Hari ini yang belum absen tidak dihitung. */
    val missedDays: Int,
    /** Uang yang terlewat dari [missedDays] (saldo seandainya hari-hari itu tepat waktu, dikurangi [earned]). */
    val missed: Long,
    /** Hari kerja yang masih tersisa (setelah hari ini, ditambah hari ini bila belum absen). */
    val remainingDays: Int,
    /** Tambahan PERSIS untuk hari tepat waktu berikutnya (11.667 atau 11.666 karena pembulatan); 0 bila tidak ada sisa hari. */
    val nextCredit: Long,
    /** Kalender seluruh bulan, satu entri per tanggal. */
    val calendar: List<DayMark>,
    /**
     * Hari kerja berturut-turut tepat waktu sampai hari ini. Hari ini yang belum absen dilewati; izin/sakit
     * MENJEDA (tidak menambah, tidak memutus); telat dan bolos memutus.
     */
    val streak: Int,
    /** Rekor beruntun terpanjang di bulan ini (aturan sama dengan [streak]). */
    val bestStreak: Int,
    /** Titik target saldo bulan ini, naik: kelipatan 100.000 di bawah maksimal, lalu maksimal (mis. 100rb, 200rb, 300rb, 350rb). */
    val milestones: List<Long>,
    /** Target berikutnya yang lebih besar dari saldo dan masih bisa dicapai; null bila tidak ada. */
    val nextMilestone: Long?,
    /** Hari tepat waktu yang masih dibutuhkan untuk mencapai [nextMilestone] (0 bila null). */
    val daysToNextMilestone: Int,
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

    /** Kelipatan 100.000 di bawah [max], lalu [max] sendiri. Contoh 350.000 -> [100.000, 200.000, 300.000, 350.000]. */
    fun milestonesFor(max: Long): List<Long> {
        if (max <= 0) return emptyList()
        val step = 100_000L
        return (generateSequence(step) { it + step }.takeWhile { it < max }.toList() + max)
    }

    /** Tambahan per hari untuk ditampilkan ("+Rp 11.667"). */
    fun perDayAmount(config: BonusConfig, workDaysInMonth: Int): Long = when {
        config.perDayOverride > 0 -> config.perDayOverride
        workDaysInMonth <= 0 -> 0L
        else -> saldoFor(config, workDaysInMonth, 1)
    }

    /** Hanya hari kerja TEPAT WAKTU yang menambah saldo. Telat, izin, sakit, dan bolos tidak dihitung. */
    private fun credits(status: String?): Boolean = status == Status.TEPAT

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

            // Urutkan hari kerja menurut tanggal, lalu bagikan selisih saldo kumulatif ke tiap hari tepat waktu
            // supaya jumlah semua tambahan selalu sama persis dengan saldo.
            val daily = LinkedHashMap<String, Long>()
            var credited = 0
            for (d in days) {
                val key = d.toString()
                val status = byDate[key]?.status ?: continue
                if (credits(status)) {
                    val before = saldoFor(config, n, credited)
                    credited++
                    daily[key] = saldoFor(config, n, credited) - before
                } else {
                    daily[key] = 0L // telat / izin / sakit: tercatat tetapi tidak menambah
                }
            }
            val earned = saldoFor(config, n, credited)
            // Hari kerja sampai hari ini yang tidak menambah; hari ini yang belum absen masih kesempatan, jadi tidak dihitung.
            val missedDays = elapsed.count { d ->
                val status = byDate[d.toString()]?.status
                if (status == null) d.isBefore(today) else !credits(status)
            }
            val missed = saldoFor(config, n, credited + missedDays) - earned

            // Sisa kesempatan: hari kerja setelah hari ini, ditambah hari ini bila belum ada catatan.
            val remaining = days.count { it.isAfter(today) || (it == today && byDate[it.toString()] == null) }
            val maxReachable = saldoFor(config, n, credited + remaining)

            // Streak: mundur dari hari ini; hari ini yang belum absen dilewati (masih ada kesempatan),
            // izin/sakit menjeda, telat/bolos memutus.
            var streak = 0
            for (d in elapsed.asReversed()) {
                val status = byDate[d.toString()]?.status
                if (d == today && status == null) continue
                when (status) {
                    Status.TEPAT -> streak++
                    Status.IZIN, Status.SAKIT -> continue
                    else -> break
                }
            }
            var bestStreak = 0
            var run = 0
            for (d in elapsed) {
                val status = byDate[d.toString()]?.status
                when {
                    status == Status.TEPAT -> { run++; if (run > bestStreak) bestStreak = run }
                    status == Status.IZIN || status == Status.SAKIT -> {}
                    d == today && status == null -> {}
                    else -> run = 0
                }
            }

            // Target saldo dan berapa hari tepat waktu lagi untuk mencapainya (dihitung dengan saldoFor, jadi selalu benar).
            val max = saldoFor(config, n, n)
            val milestones = milestonesFor(max)
            val next = milestones.firstOrNull { it > earned && it <= maxReachable }
            val daysToNext = if (next == null) 0 else
                (1..remaining).firstOrNull { k -> saldoFor(config, n, credited + k) >= next } ?: 0

            val workSet = days.toHashSet()
            val calendar = (1..ym.lengthOfMonth()).map { dom ->
                val d = ym.atDay(dom)
                val key = d.toString()
                val status = byDate[key]?.status
                val kind = when {
                    d !in workSet -> DayKind.OFF
                    status == Status.TEPAT -> DayKind.EARNED
                    status == Status.TELAT -> DayKind.LATE
                    status == Status.IZIN || status == Status.SAKIT -> DayKind.EXCUSED
                    d.isBefore(today) -> DayKind.ABSENT
                    d == today -> DayKind.PENDING
                    else -> DayKind.FUTURE
                }
                DayMark(d, kind, daily[key] ?: 0L)
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
                annulled = recs.count { it.annulledLateMinutes != null },
                outsideLocation = recs.count { (it.status == Status.TEPAT || it.status == Status.TELAT) && !it.inLocation },
                totalLateMinutes = recs.sumOf { it.lateMinutes },
                perDayAmount = perDay,
                creditedDays = credited,
                earned = earned,
                maxThisMonth = max,
                maxReachable = maxReachable,
                dailyCredits = daily,
                missedDays = missedDays,
                missed = missed,
                remainingDays = remaining,
                nextCredit = if (remaining > 0) saldoFor(config, n, credited + 1) - earned else 0L,
                streak = streak,
                bestStreak = bestStreak,
                milestones = milestones,
                nextMilestone = if (daysToNext > 0) next else null,
                daysToNextMilestone = daysToNext,
                calendar = calendar,
                isFullMonth = n > 0 && max > 0 && days.all { byDate[it.toString()]?.status == Status.TEPAT },
            )
        }
    }
}
