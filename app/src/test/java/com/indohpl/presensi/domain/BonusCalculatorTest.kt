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
    private val sara = Employee(1, "Sara", "P")
    private val evan = Employee(3, "Evan", "L")
    private val config = BonusConfig(monthlyBonus = 250_000, lateDeductionOverride = 0, deductAbsent = true)

    // September 2026: 30 hari, mulai Selasa. Senin–Sabtu = 26 hari kerja.
    private val sep = YearMonth.of(2026, 9)

    private fun rec(emp: Employee, date: LocalDate, status: String, late: Int = 0) = AttendanceRecord(
        employeeId = emp.id, date = date.toString(), timestamp = 0L, timeIn = "08:00:00",
        status = status, lateMinutes = late, photoPath = null,
    )

    private fun allDays(emp: Employee, status: (LocalDate) -> String) =
        BonusCalculator.workDays(sep, monSat, emptySet()).map { rec(emp, it, status(it)) }

    @Test
    fun workDaysSeptember2026() {
        val days = BonusCalculator.workDays(sep, monSat, emptySet())
        assertEquals(26, days.size)
        assertTrue(days.none { it.dayOfWeek == DayOfWeek.SUNDAY })
    }

    @Test
    fun holidayReducesWorkDays() {
        val days = BonusCalculator.workDays(sep, monSat, setOf(LocalDate.of(2026, 9, 1)))
        assertEquals(25, days.size)
    }

    @Test
    fun fullMonthOnTimeGetsFullBonus() {
        val records = allDays(sara) { Status.TEPAT }
        val r = BonusCalculator.recap(sep, LocalDate.of(2026, 10, 5), listOf(sara), records, monSat, emptySet(), config).single()
        assertEquals(26, r.hadir)
        assertEquals(0, r.alpa)
        assertTrue(r.isFullMonth)
        assertEquals(250_000L, r.bonus)
        assertEquals(9_615L, r.deductionPerDay) // 250000 / 26 dibulatkan
    }

    @Test
    fun twoLateDaysDeductTwice() {
        val days = BonusCalculator.workDays(sep, monSat, emptySet())
        val records = days.mapIndexed { i, d -> rec(sara, d, if (i < 2) Status.TELAT else Status.TEPAT, if (i < 2) 15 else 0) }
        val r = BonusCalculator.recap(sep, LocalDate.of(2026, 10, 5), listOf(sara), records, monSat, emptySet(), config).single()
        assertEquals(2, r.telat)
        assertEquals(2, r.deductedDays)
        assertEquals(2 * 9_615L, r.totalDeduction)
        assertEquals(250_000L - 2 * 9_615L, r.bonus)
        assertEquals(30, r.totalLateMinutes)
        assertFalse(r.isFullMonth)
    }

    @Test
    fun fixedDeductionOverrideIsUsed() {
        val cfg = config.copy(lateDeductionOverride = 10_000)
        val days = BonusCalculator.workDays(sep, monSat, emptySet())
        val records = days.mapIndexed { i, d -> rec(sara, d, if (i == 0) Status.TELAT else Status.TEPAT) }
        val r = BonusCalculator.recap(sep, LocalDate.of(2026, 10, 5), listOf(sara), records, monSat, emptySet(), cfg).single()
        assertEquals(10_000L, r.deductionPerDay)
        assertEquals(240_000L, r.bonus)
    }

    @Test
    fun absentCountsOnlyElapsedWorkDays() {
        // Hari ini 5 Sep 2026 (Sabtu). Hari kerja yang sudah lewat: Sel 1, Rab 2, Kam 3, Jum 4, Sab 5 = 5 hari.
        val today = LocalDate.of(2026, 9, 5)
        val records = listOf(
            rec(evan, LocalDate.of(2026, 9, 1), Status.TEPAT),
            rec(evan, LocalDate.of(2026, 9, 2), Status.TEPAT),
        )
        val r = BonusCalculator.recap(sep, today, listOf(evan), records, monSat, emptySet(), config).single()
        assertEquals(5, r.workDaysElapsed)
        assertEquals(3, r.alpa)
        assertEquals(3, r.deductedDays)
        assertEquals(250_000L - 3 * 9_615L, r.bonus)
    }

    @Test
    fun excusedDaysAreNotDeducted() {
        val today = LocalDate.of(2026, 9, 5)
        val records = listOf(
            rec(evan, LocalDate.of(2026, 9, 1), Status.TEPAT),
            rec(evan, LocalDate.of(2026, 9, 2), Status.IZIN),
            rec(evan, LocalDate.of(2026, 9, 3), Status.SAKIT),
            rec(evan, LocalDate.of(2026, 9, 4), Status.TEPAT),
            rec(evan, LocalDate.of(2026, 9, 5), Status.TEPAT),
        )
        val r = BonusCalculator.recap(sep, today, listOf(evan), records, monSat, emptySet(), config).single()
        assertEquals(1, r.izin)
        assertEquals(1, r.sakit)
        assertEquals(0, r.alpa)
        assertEquals(250_000L, r.bonus)
        assertFalse(r.isFullMonth) // izin/sakit bukan "full bulan"
    }

    @Test
    fun absentNotDeductedWhenDisabled() {
        val cfg = config.copy(deductAbsent = false)
        val r = BonusCalculator.recap(sep, LocalDate.of(2026, 9, 5), listOf(evan), emptyList(), monSat, emptySet(), cfg).single()
        assertEquals(5, r.alpa)
        assertEquals(0, r.deductedDays)
        assertEquals(250_000L, r.bonus)
    }

    @Test
    fun bonusNeverNegative() {
        val cfg = config.copy(lateDeductionOverride = 100_000)
        val records = allDays(sara) { Status.TELAT }
        val r = BonusCalculator.recap(sep, LocalDate.of(2026, 10, 5), listOf(sara), records, monSat, emptySet(), cfg).single()
        assertEquals(0L, r.bonus)
        assertEquals(250_000L, r.totalDeduction)
    }

    @Test
    fun genderRulesFromUser() {
        // Perempuan: 07:45 toleransi 10 -> batas 07:55
        assertEquals(0, LateRule.lateMinutes(LocalTime.of(7, 55, 0), "07:45", 10))
        assertEquals(11, LateRule.lateMinutes(LocalTime.of(7, 56, 0), "07:45", 10))
        // Laki-laki: 08:00 toleransi 5 -> batas 08:05
        assertEquals(0, LateRule.lateMinutes(LocalTime.of(8, 5, 0), "08:00", 5))
        assertEquals(6, LateRule.lateMinutes(LocalTime.of(8, 6, 0), "08:00", 5))
    }

    @Test
    fun sevenDayWorkWeek() {
        val all = java.time.DayOfWeek.values().toSet()
        assertEquals(30, BonusCalculator.workDays(sep, all, emptySet()).size)
        assertEquals(8_333L, BonusCalculator.deductionPerDay(config, 30))
    }

    @Test
    fun lateRuleRespectsTolerance() {
        assertEquals(0, LateRule.lateMinutes(LocalTime.of(7, 59, 59), "08:00", 0))
        assertEquals(0, LateRule.lateMinutes(LocalTime.of(8, 0, 0), "08:00", 0))
        assertEquals(1, LateRule.lateMinutes(LocalTime.of(8, 0, 1), "08:00", 0))
        assertEquals(0, LateRule.lateMinutes(LocalTime.of(8, 5, 0), "08:00", 5))
        assertEquals(6, LateRule.lateMinutes(LocalTime.of(8, 6, 0), "08:00", 5))
        assertEquals(20, LateRule.lateMinutes(LocalTime.of(8, 20, 30), "08:00", 0))
    }

    @Test
    fun csvAndMarkdownContainNamesAndTotals() {
        val records = listOf(rec(sara, LocalDate.of(2026, 9, 1), Status.TELAT, 12))
        val recaps = BonusCalculator.recap(sep, LocalDate.of(2026, 9, 1), listOf(sara), records, monSat, emptySet(), config)
        val csv = RecapFormatter.detailCsv(sep, listOf(sara), records)
        assertTrue(csv.contains("2026-09-01,Sara,TELAT,08:00:00,12,,"))
        val md = RecapFormatter.markdown(sep, recaps, records, listOf(sara), config, "Perempuan 07:45 (toleransi 10 mnt) · Laki-laki 08:00 (toleransi 5 mnt)", "01/09/2026 09:00:00")
        assertTrue(md.contains("# Rekap Presensi September 2026"))
        assertTrue(md.contains("| Sara | 26 |"))
        assertEquals("Rp 250.000", RecapFormatter.rupiah(250_000))
        assertEquals("Rp 9.615", RecapFormatter.rupiah(9_615))
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
        val r = BonusCalculator.recap(ym, LocalDate.of(2026, 9, 2), listOf(emp), records, days, emptySet(), BonusConfig(250_000, 0, true)).single()
        assertEquals(1, r.outsideLocation)
        val csv = RecapFormatter.detailCsv(ym, listOf(emp), records)
        assertTrue(csv.contains("2026-09-02,Sara,TEPAT,07:50:00,0,,,,,,,TIDAK,di luar lokasi"))
    }
}
