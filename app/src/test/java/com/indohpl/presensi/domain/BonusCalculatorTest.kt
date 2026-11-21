package com.indohpl.presensi.domain

import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Status
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BonusCalculatorTest {
    private val monSat = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
    )
    private val everyDay = DayOfWeek.values().toSet()
    private val sara = Employee(1, "Sara", "P")
    private val evan = Employee(3, "Evan", "L")
    private val config = BonusConfig(monthlyBonus = 350_000, perDayOverride = 0)

    // September 2026: 30 hari, mulai Selasa. Senin–Sabtu = 26 hari kerja. Setiap hari = 30 hari kerja.
    private val sep = YearMonth.of(2026, 9)
    private val afterSep = LocalDate.of(2026, 10, 5)

    private fun rec(emp: Employee, date: LocalDate, status: String, late: Int = 0) = AttendanceRecord(
        employeeId = emp.id, date = date.toString(), timestamp = 0L, timeIn = "08:00:00",
        status = status, lateMinutes = late, photoPath = null,
    )

    private fun recap(records: List<AttendanceRecord>, today: LocalDate = afterSep, days: Set<DayOfWeek> = everyDay,
                      cfg: BonusConfig = config, holidays: Set<LocalDate> = emptySet(), emp: Employee = sara) =
        BonusCalculator.recap(sep, today, listOf(emp), records, days, holidays, cfg).single()

    @Test
    fun workDaysSeptember2026() {
        assertEquals(26, BonusCalculator.workDays(sep, monSat, emptySet()).size)
        assertEquals(30, BonusCalculator.workDays(sep, everyDay, emptySet()).size)
        assertEquals(29, BonusCalculator.workDays(sep, everyDay, setOf(LocalDate.of(2026, 9, 1))).size)
    }

    @Test
    fun saldoGrowsProportionallyAndHitsExactMaximum() {
        // Dihitung tangan: 350.000 x 1/30 = 11.666,67 -> 11.667; x 10/30 = 116.666,67 -> 116.667; x 12/30 = 140.000.
        assertEquals(11_667L, BonusCalculator.saldoFor(config, 30, 1))
        assertEquals(116_667L, BonusCalculator.saldoFor(config, 30, 10))
        assertEquals(140_000L, BonusCalculator.saldoFor(config, 30, 12))
        assertEquals(350_000L, BonusCalculator.saldoFor(config, 30, 30))
        assertEquals(350_000L, BonusCalculator.saldoFor(config, 31, 31))
        assertEquals(350_000L, BonusCalculator.saldoFor(config, 26, 26))
        assertEquals(0L, BonusCalculator.saldoFor(config, 30, 0))
        assertEquals(0L, BonusCalculator.saldoFor(config, 0, 0))
    }

    @Test
    fun fullMonthOnTimeEarnsExactly350kAndCreditsSumUp() {
        val records = BonusCalculator.workDays(sep, everyDay, emptySet()).map { rec(sara, it, Status.TEPAT) }
        val r = recap(records)
        assertEquals(350_000L, r.earned)
        assertEquals(350_000L, r.maxThisMonth)
        assertEquals(30, r.creditedDays)
        assertTrue(r.isFullMonth)
        assertEquals(11_667L, r.perDayAmount)
        assertEquals(r.earned, r.dailyCredits.values.sum())
        assertTrue(r.dailyCredits.values.all { it == 11_666L || it == 11_667L })
        assertEquals(30, r.streak)
        assertEquals(0, r.missedDays)
        assertEquals(0L, r.missed)
        assertTrue(r.calendar.all { it.kind == DayKind.EARNED })
    }

    @Test
    fun lateExcusedSickAndAbsentAddNothing() {
        // Hari ini 6 Sep. 1 tepat, 2 telat, 3 izin, 4 sakit, 5 bolos (tanpa catatan), 6 belum absen.
        val today = LocalDate.of(2026, 9, 6)
        val records = listOf(
            rec(evan, LocalDate.of(2026, 9, 1), Status.TEPAT),
            rec(evan, LocalDate.of(2026, 9, 2), Status.TELAT, 9),
            rec(evan, LocalDate.of(2026, 9, 3), Status.IZIN),
            rec(evan, LocalDate.of(2026, 9, 4), Status.SAKIT),
        )
        val r = recap(records, today = today, emp = evan)
        assertEquals(1, r.creditedDays)
        assertEquals(11_667L, r.earned)
        assertEquals(1, r.alpa)
        assertEquals(4, r.missedDays)                  // telat + izin + sakit + bolos
        assertEquals(46_666L, r.missed)                // saldo(5) = 58.333 dikurangi saldo(1) = 11.667
        assertEquals(0L, r.dailyCredits["2026-09-03"])
        assertEquals(0L, r.dailyCredits["2026-09-04"])
        assertEquals(null, r.dailyCredits["2026-09-05"])
        assertEquals(25, r.remainingDays)              // 6..30
        assertEquals(BonusCalculator.saldoFor(config, 30, 26), r.maxReachable)
        val kinds = r.calendar.take(7).map { it.kind }
        assertEquals(listOf(DayKind.EARNED, DayKind.LATE, DayKind.EXCUSED, DayKind.EXCUSED, DayKind.ABSENT, DayKind.PENDING, DayKind.FUTURE), kinds)
    }

    @Test
    fun lateDayAddsNothingAtMonthEnd() {
        // 26 hari kerja Senin–Sabtu, 2 telat: 350.000 x 24/26 = 323.076,9 -> 323.077.
        val days = BonusCalculator.workDays(sep, monSat, emptySet())
        val records = days.mapIndexed { i, d -> rec(sara, d, if (i < 2) Status.TELAT else Status.TEPAT, if (i < 2) 15 else 0) }
        val r = recap(records, days = monSat)
        assertEquals(323_077L, r.earned)
        assertEquals(24, r.creditedDays)
        assertEquals(r.earned, r.dailyCredits.values.sum())
        assertEquals(30, r.totalLateMinutes)
        assertFalse(r.isFullMonth)
        assertEquals(DayKind.OFF, r.calendar.first { it.date.dayOfWeek == DayOfWeek.SUNDAY }.kind)
    }

    @Test
    fun midMonthStreakAndToday() {
        val today = LocalDate.of(2026, 9, 5)
        val records = (1..3).map { rec(evan, LocalDate.of(2026, 9, it), Status.TEPAT) } + rec(evan, LocalDate.of(2026, 9, 4), Status.TELAT, 7)
        val r = recap(records, today = today, emp = evan)
        assertEquals(35_000L, r.earned)                // 350.000 x 3/30
        assertEquals(0, r.alpa)                        // hari ini belum lewat
        assertEquals(0, r.streak)                      // kemarin telat
        assertEquals(DayKind.PENDING, r.calendar[4].kind)
        val r2 = recap(records + rec(evan, today, Status.TEPAT), today = today, emp = evan)
        assertEquals(46_667L, r2.earned)
        assertEquals(1, r2.streak)
        assertEquals(11_667L, r2.dailyCredits[today.toString()])
        assertEquals(3, recap((1..3).map { rec(sara, LocalDate.of(2026, 9, it), Status.TEPAT) }, today = LocalDate.of(2026, 9, 4)).streak)
    }

    @Test
    fun excusedDaysPauseStreakLateBreaksIt() {
        // 1-3 tepat, 4 sakit, 5-6 tepat, 7 telat, 8-9 tepat; hari ini 10 belum absen.
        val st = mapOf(1 to Status.TEPAT, 2 to Status.TEPAT, 3 to Status.TEPAT, 4 to Status.SAKIT, 5 to Status.TEPAT,
            6 to Status.TEPAT, 7 to Status.TELAT, 8 to Status.TEPAT, 9 to Status.TEPAT)
        val records = st.map { (d, s) -> rec(sara, LocalDate.of(2026, 9, d), s) }
        val r = recap(records, today = LocalDate.of(2026, 9, 10))
        assertEquals(2, r.streak)                      // 8-9 (telat tgl 7 memutus)
        assertEquals(5, r.bestStreak)                  // 1-3 + 5-6, sakit tgl 4 hanya menjeda
        val onlySick = recap(records.filter { it.date <= "2026-09-06" }, today = LocalDate.of(2026, 9, 6))
        assertEquals(5, onlySick.streak)
    }

    @Test
    fun milestonesAndDaysToNext() {
        assertEquals(listOf(100_000L, 200_000L, 300_000L, 350_000L), BonusCalculator.milestonesFor(350_000))
        assertEquals(listOf(100_000L, 200_000L, 250_000L), BonusCalculator.milestonesFor(250_000))
        assertEquals(emptyList<Long>(), BonusCalculator.milestonesFor(0))
        // 1 hari tepat (11.667) pada 30 hari kerja: Rp 100.000 butuh 9 hari total (105.000), jadi 8 lagi.
        val r = recap(listOf(rec(sara, LocalDate.of(2026, 9, 1), Status.TEPAT)), today = LocalDate.of(2026, 9, 1))
        assertEquals(100_000L, r.nextMilestone)
        assertEquals(8, r.daysToNextMilestone)
        // 15 hari tepat = 175.000; Rp 200.000 butuh 18 hari (210.000), jadi 3 lagi.
        val r15 = recap((1..15).map { rec(sara, LocalDate.of(2026, 9, it), Status.TEPAT) }, today = LocalDate.of(2026, 9, 15))
        assertEquals(175_000L, r15.earned)
        assertEquals(200_000L, r15.nextMilestone)
        assertEquals(3, r15.daysToNextMilestone)
        // Sebulan penuh: tidak ada target berikutnya.
        val full = recap(BonusCalculator.workDays(sep, everyDay, emptySet()).map { rec(sara, it, Status.TEPAT) })
        assertEquals(null, full.nextMilestone)
        assertEquals(0, full.daysToNextMilestone)
        // Satu telat di awal: 350.000 tidak terjangkau lagi, target terakhir yang terjangkau tetap ditawarkan.
        val oneLate = recap(listOf(rec(sara, LocalDate.of(2026, 9, 1), Status.TELAT)) + (2..27).map { rec(sara, LocalDate.of(2026, 9, it), Status.TEPAT) }, today = LocalDate.of(2026, 9, 27))
        assertEquals(303_333L, oneLate.earned)         // 26 hari
        assertEquals(338_333L, oneLate.maxReachable)   // 29 hari
        assertEquals(null, oneLate.nextMilestone)      // 350.000 tidak terjangkau, 300.000 sudah lewat
    }

    @Test
    fun nextCreditIsTheExactNextIncrement() {
        // 1 hari: saldo 11.667; hari ke-2 menjadi 23.333 -> tambahan berikutnya 11.666 (bukan 11.667).
        val r1 = recap(listOf(rec(sara, LocalDate.of(2026, 9, 1), Status.TEPAT)), today = LocalDate.of(2026, 9, 1))
        assertEquals(11_666L, r1.nextCredit)
        // Belum absen hari ini: tambahan hari ini.
        assertEquals(11_667L, recap(emptyList(), today = LocalDate.of(2026, 9, 1)).nextCredit)
        // Bulan sudah lewat: tidak ada hari tersisa.
        assertEquals(0L, recap(emptyList()).nextCredit)
        assertFalse(recap(emptyList(), cfg = BonusConfig(0, 0)).isFullMonth)
    }

    @Test
    fun fixedPerDayAmountIsCappedAtMonthlyBonus() {
        val cfg = config.copy(perDayOverride = 15_000)
        val all = BonusCalculator.workDays(sep, everyDay, emptySet()).map { rec(sara, it, Status.TEPAT) }
        val r = recap(all, cfg = cfg)
        assertEquals(15_000L, r.perDayAmount)
        assertEquals(350_000L, r.earned)               // 30 x 15.000 = 450.000, dibatasi 350.000
        assertEquals(r.earned, r.dailyCredits.values.sum())
        assertEquals(180_000L, recap(all.take(12), cfg = cfg).earned)
    }

    @Test
    fun attendanceOnHolidayDoesNotAdd() {
        val h = LocalDate.of(2026, 9, 17)
        val r = recap(listOf(rec(sara, h, Status.TEPAT)), holidays = setOf(h))
        assertEquals(0L, r.earned)
        assertEquals(29, r.workDaysInMonth)
        assertEquals(DayKind.OFF, r.calendar[16].kind)
    }

    @Test
    fun lateRuleRespectsTolerance() {
        assertEquals(0, LateRule.lateMinutes(LocalTime.of(7, 59, 59), "08:00", 0))
        assertEquals(0, LateRule.lateMinutes(LocalTime.of(8, 0, 0), "08:00", 0))
        assertEquals(1, LateRule.lateMinutes(LocalTime.of(8, 0, 1), "08:00", 0))
        assertEquals(0, LateRule.lateMinutes(LocalTime.of(8, 5, 0), "08:00", 5))
        assertEquals(6, LateRule.lateMinutes(LocalTime.of(8, 6, 0), "08:00", 5))
    }

    @Test
    fun genderRulesFromUser() {
        assertEquals(0, LateRule.lateMinutes(LocalTime.of(7, 55, 0), "07:45", 10))
        assertEquals(11, LateRule.lateMinutes(LocalTime.of(7, 56, 0), "07:45", 10))
        assertEquals(0, LateRule.lateMinutes(LocalTime.of(8, 5, 0), "08:00", 5))
        assertEquals(6, LateRule.lateMinutes(LocalTime.of(8, 6, 0), "08:00", 5))
    }

    @Test
    fun csvAndMarkdownShowSaldoAndPlus() {
        val records = listOf(rec(sara, LocalDate.of(2026, 9, 1), Status.TEPAT), rec(sara, LocalDate.of(2026, 9, 2), Status.IZIN))
        val recaps = BonusCalculator.recap(sep, LocalDate.of(2026, 9, 2), listOf(sara), records, everyDay, emptySet(), config)
        val sum = RecapFormatter.summaryCsv(sep, recaps)
        assertTrue(sum.contains(",11667,1,11667,350000,TIDAK"))
        val md = RecapFormatter.markdown(sep, recaps, records, listOf(sara), config, "P 07:45 / L 08:00", "02/09/2026 09:00:00")
        assertTrue(md.contains("**Rp 11.667**"))
        assertTrue(md.contains("| +Rp 11.667 |"))
        assertTrue(md.contains("| +Rp 0 |"))
        assertTrue(md.contains("tidak dihitung"))
        assertEquals("+Rp 11.667", RecapFormatter.rupiahPlus(11_667))
    }
}

class GeoRuleTest {
    // Contoh titik: Monas, Jakarta.
    private val storeLat = -6.175392
    private val storeLon = 106.827153

    @Test
    fun distanceIsZeroAtSamePoint() {
        assertEquals(0.0, GeoRule.distanceMeters(storeLat, storeLon, storeLat, storeLon), 0.001)
    }

    @Test
    fun distanceRoughlyOneKilometreNorth() {
        // 0.009 derajat lintang ≈ 1.000 m
        val d = GeoRule.distanceMeters(storeLat, storeLon, storeLat + 0.009, storeLon)
        assertTrue("d=$d", d > 990 && d < 1010)
    }

    @Test
    fun insideRadiusIsInLocation() {
        val g = GeoRule.check(storeLat + 0.0003, storeLon, 15f, false, storeLat, storeLon, 100)
        assertTrue(g.inLocation)
        assertEquals("di lokasi toko", g.note)
        assertTrue((g.distanceMeters ?: 0) in 25..40)
    }

    @Test
    fun accuracyGivesBenefitOfDoubt() {
        // 120 m dari titik, akurasi ±30 m -> efektif 90 m <= 100 m radius
        val g = GeoRule.check(storeLat + 0.00108, storeLon, 30f, false, storeLat, storeLon, 100)
        assertTrue("d=${g.distanceMeters}", g.inLocation)
        val g2 = GeoRule.check(storeLat + 0.00108, storeLon, 5f, false, storeLat, storeLon, 100)
        assertFalse(g2.inLocation)
        assertEquals("di luar lokasi", g2.note)
    }

    @Test
    fun mockLocationIsRejected() {
        val g = GeoRule.check(storeLat, storeLon, 5f, true, storeLat, storeLon, 100)
        assertFalse(g.inLocation)
        assertEquals("lokasi palsu", g.note)
    }

    @Test
    fun noStoreConfigured() {
        val g = GeoRule.check(storeLat, storeLon, 5f, false, null, null, 100)
        assertFalse(g.inLocation)
        assertEquals(null, g.distanceMeters)
        assertEquals("titik toko belum diatur", g.note)
    }

    @Test
    fun parseAndFormat() {
        assertEquals(-6.2 to 106.816666, GeoRule.parseLatLon("-6.2, 106.816666"))
        assertEquals(-6.2 to 106.816666, GeoRule.parseLatLon("-6.2 106.816666"))
        assertEquals(null, GeoRule.parseLatLon("abc"))
        assertEquals(null, GeoRule.parseLatLon("95, 10"))
        assertEquals("-6.175392, 106.827153", GeoRule.formatCoord(storeLat, storeLon))
        assertEquals("35 m", GeoRule.formatDistance(35))
        assertEquals("1,2 km", GeoRule.formatDistance(1234))
    }

    @Test
    fun outsideLocationCountedInRecap() {
        val emp = Employee(1, "Sara", "P")
        val ym = YearMonth.of(2026, 9)
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
        val records = listOf(
            AttendanceRecord(employeeId = 1, date = "2026-09-01", timestamp = 0, timeIn = "07:50:00", status = Status.TEPAT, lateMinutes = 0, photoPath = null, inLocation = true),
            AttendanceRecord(employeeId = 1, date = "2026-09-02", timestamp = 0, timeIn = "07:50:00", status = Status.TEPAT, lateMinutes = 0, photoPath = null, inLocation = false, locationNote = "di luar lokasi"),
        )
        val r = BonusCalculator.recap(ym, LocalDate.of(2026, 9, 2), listOf(emp), records, days, emptySet(), BonusConfig(250_000, 0)).single()
        assertEquals(1, r.outsideLocation)
        val csv = RecapFormatter.detailCsv(ym, listOf(emp), records)
        assertTrue(csv.contains("2026-09-02,Sara,TEPAT,07:50:00,0,,,,,,,TIDAK,di luar lokasi"))
    }
}

class RetentionTest {
    @Test
    fun cutoffKeepsCurrentPlusTwoPreviousMonths() {
        // 29 Okt 2026, simpan 2 bulan -> yang disimpan Agustus, September, Oktober; Juli dan sebelumnya dihapus.
        assertEquals(LocalDate.of(2026, 8, 1), Retention.cutoffDate(LocalDate.of(2026, 10, 29), 2))
        assertEquals(LocalDate.of(2026, 8, 1), Retention.cutoffDate(LocalDate.of(2026, 10, 1), 2))
        assertEquals(LocalDate.of(2026, 9, 1), Retention.cutoffDate(LocalDate.of(2026, 11, 1), 2))
    }

    @Test
    fun cutoffCrossesYearBoundary() {
        assertEquals(LocalDate.of(2025, 11, 1), Retention.cutoffDate(LocalDate.of(2026, 1, 15), 2))
    }

    @Test
    fun monthsAreClamped() {
        assertEquals(LocalDate.of(2026, 9, 1), Retention.cutoffDate(LocalDate.of(2026, 10, 29), 0))
        assertEquals(LocalDate.of(2024, 10, 1), Retention.cutoffDate(LocalDate.of(2026, 10, 29), 99))
    }

    @Test
    fun nextCleanupTellsWhichMonthGoes() {
        val (date, month) = Retention.nextCleanup(LocalDate.of(2026, 10, 29), 2)
        assertEquals(LocalDate.of(2026, 11, 1), date)
        assertEquals(YearMonth.of(2026, 8), month)
    }
}

class AutoCleanGuardTest {
    @Test
    fun normalUseCleans() {
        assertTrue(Retention.shouldAutoClean(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 30)))
        assertTrue(Retention.shouldAutoClean(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 10, 27))) // tepat 35 hari
    }

    @Test
    fun staleOrClockJumpSkips() {
        assertFalse(Retention.shouldAutoClean(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 10, 26))) // 36 hari
        assertFalse(Retention.shouldAutoClean(LocalDate.of(2030, 1, 1), LocalDate.of(2026, 10, 29)))  // jam HP melompat
        assertFalse(Retention.shouldAutoClean(LocalDate.of(2026, 12, 1), null))                        // belum ada data
    }
}

class CsvImportTest {
    private val headerGps = "tanggal,nama,status,jam_masuk,menit_telat,catatan,foto,latitude,longitude,akurasi_m,jarak_toko_m,di_lokasi,ket_lokasi"

    @Test
    fun parsesCurrentExportFormat() {
        val csv = "$headerGps\n" +
            "2026-10-01,Sara,TEPAT,07:50:12,0,,Sara_20261001_075012.jpg,-6.1,106.8,12,30,YA,di lokasi toko\n" +
            "2026-10-01,Evan,TELAT,08:20:00,20,,Evan.jpg,-6.1,106.8,8,40,TIDAK,di luar lokasi\n" +
            "2026-10-02,Madi,IZIN,,0,\"acara, keluarga\",,,,,,,\n"
        val r = CsvImport.parse(csv)
        assertEquals(emptyList<String>(), r.errors)
        assertEquals(3, r.rows.size)
        val sara = r.rows[0]
        assertEquals(LocalDate.of(2026, 10, 1), sara.date)
        assertEquals("07:50:12", sara.timeIn)
        assertEquals(-6.1, sara.latitude!!, 1e-9)
        assertEquals(30, sara.distanceMeters)
        assertTrue(sara.inLocation)
        assertEquals(20, r.rows[1].lateMinutes)
        assertFalse(r.rows[1].inLocation)
        assertEquals("acara, keluarga", r.rows[2].note)
        assertEquals("", r.rows[2].timeIn)
    }

    @Test
    fun parsesOldFormatWithoutGpsAndOtherPlaceColumn() {
        val old = "tanggal,nama,status,jam_masuk,menit_telat,catatan,foto\n2026-09-29,Roni,TEPAT,07:59:00,0,,Roni.jpg\n"
        val r = CsvImport.parse(old)
        assertEquals(1, r.rows.size)
        assertEquals(null, r.rows[0].latitude)
        val kos = "tanggal,nama,status,jam_masuk,menit_telat,catatan,foto,latitude,longitude,akurasi_m,jarak_kos_m,di_lokasi,ket_lokasi\n" +
            "2026-10-05,Sara,TEPAT,07:40:00,0,,,1.0,2.0,5,15,YA,ok\n"
        assertEquals(15, CsvImport.parse(kos).rows.single().distanceMeters)
    }

    @Test
    fun roundTripWithExporter() {
        val sara = Employee(1, "Sara", "P")
        val ym = YearMonth.of(2026, 10)
        val records = listOf(
            AttendanceRecord(employeeId = 1, date = "2026-10-01", timestamp = 0, timeIn = "07:50:00", status = Status.TEPAT, lateMinutes = 0,
                photoPath = "/x/a.jpg", note = "kata \"kutip\", koma", latitude = -6.2, longitude = 106.8, accuracy = 9f,
                distanceMeters = 12, inLocation = true, locationNote = "di lokasi toko"),
        )
        val r = CsvImport.parse(RecapFormatter.detailCsv(ym, listOf(sara), records)).rows.single()
        assertEquals("kata \"kutip\", koma", r.note)
        assertEquals("07:50:00", r.timeIn)
        assertEquals(12, r.distanceMeters)
        assertTrue(r.inLocation)
    }

    @Test
    fun reportsBadRowsAndWrongFiles() {
        val csv = "$headerGps\n2026-13-01,Sara,TEPAT,07:50:00,0,,,,,,,,\n2026-10-01,Sara,HADIR,07:50:00,0,,,,,,,,\n2026-10-01,Sara,TEPAT,jam,0,,,,,,,,\n\n"
        val r = CsvImport.parse(csv)
        assertEquals(0, r.rows.size)
        assertEquals(3, r.errors.size)
        assertEquals(1, CsvImport.parse("bulan,nama,hadir\n2026-10,Sara,3\n").errors.size) // file rekap, bukan detail
        assertEquals(1, CsvImport.parse("").errors.size)
    }

    @Test
    fun handlesBomAndCrlf() {
        val r = CsvImport.parse("﻿tanggal,nama,status,jam_masuk\r\n2026-10-01,Sara,TEPAT,07:50\r\n")
        assertEquals("07:50:00", r.rows.single().timeIn)
    }
}

class AutoCleanExtraGuardTest {
    @Test
    fun moreThanOneMonthAtOnceSkips() {
        assertTrue(Retention.shouldAutoClean(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 30), 1))
        assertFalse(Retention.shouldAutoClean(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 30), 2))
    }

    @Test
    fun futureDatedRecordSkips() {
        // Satu absen tercatat saat jam HP di 2030, lalu jam dibetulkan: tetap tidak hapus otomatis.
        assertFalse(Retention.shouldAutoClean(LocalDate.of(2026, 12, 1), LocalDate.of(2030, 1, 1), 0))
        // Jam masih di 2030: catatan asli 2026 berarti >1 bulan sekaligus -> lewati.
        assertFalse(Retention.shouldAutoClean(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 1), 3))
    }
}
