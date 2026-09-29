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
        val md = RecapFormatter.markdown(sep, recaps, records, listOf(sara), config, "08:00", 0, "01/09/2026 09:00:00")
        assertTrue(md.contains("# Rekap Presensi September 2026"))
        assertTrue(md.contains("| Sara | 26 |"))
        assertEquals("Rp 250.000", RecapFormatter.rupiah(250_000))
        assertEquals("Rp 9.615", RecapFormatter.rupiah(9_615))
    }
}
