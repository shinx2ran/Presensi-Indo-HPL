package com.indohpl.presensi.domain

import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Status
import com.indohpl.presensi.data.isAnnulled
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnulRuleTest {
    private val everyDay = DayOfWeek.values().toSet()
    private val evan = Employee(3, "Evan", "L")
    private val config = BonusConfig(monthlyBonus = 350_000, perDayOverride = 0)
    private val sep = YearMonth.of(2026, 9)          // 30 hari kerja (Senin–Minggu)
    private val afterSep = LocalDate.of(2026, 10, 5)

    private fun rec(day: Int, status: String, late: Int = 0) = AttendanceRecord(
        id = day.toLong(), employeeId = evan.id, date = sep.atDay(day).toString(), timestamp = 0L,
        timeIn = "08:12:00", status = status, lateMinutes = late, photoPath = null,
    )

    private fun recap(records: List<AttendanceRecord>, today: LocalDate = afterSep, holidays: Set<LocalDate> = emptySet()) =
        BonusCalculator.recap(sep, today, listOf(evan), records, everyDay, holidays, config).single()

    @Test
    fun annulTurnsLateIntoOnTimeAndKeepsTrail() {
        val late = rec(10, Status.TELAT, late = 12)
        val a = AnnulRule.annul(late, "  Pintu toko belum dibuka ")!!
        assertEquals(Status.TEPAT, a.status)
        assertEquals(0, a.lateMinutes)
        assertEquals(12, a.annulledLateMinutes)
        assertEquals("Pintu toko belum dibuka", a.annulReason)
        assertTrue(a.isAnnulled)
        // Bagian lain tidak berubah.
        assertEquals(late.copy(status = a.status, lateMinutes = 0, annulledLateMinutes = 12, annulReason = a.annulReason), a)
        assertEquals(AnnulRule.DEFAULT_REASON, AnnulRule.annul(late, "   ")!!.annulReason)
    }

    @Test
    fun undoRestoresTheOriginalExactly() {
        val late = rec(10, Status.TELAT, late = 12)
        assertEquals(late, AnnulRule.undo(AnnulRule.annul(late, "x")!!))
    }

    @Test
    fun onlyLateRecordsCanBeAnnulledAndOnlyAnnulledCanBeUndone() {
        assertNull(AnnulRule.annul(rec(1, Status.TEPAT), "x"))
        assertNull(AnnulRule.annul(rec(1, Status.IZIN), "x"))
        assertNull(AnnulRule.annul(rec(1, Status.SAKIT), "x"))
        assertNull(AnnulRule.undo(rec(1, Status.TEPAT)))
        assertNull(AnnulRule.undo(rec(1, Status.TELAT, 5)))
        val a = AnnulRule.annul(rec(1, Status.TELAT, 5), "x")!!
        assertNull("tidak boleh dianulir dua kali", AnnulRule.annul(a, "y"))
        assertFalse(AnnulRule.canAnnul(a))
        assertTrue(AnnulRule.canUndo(a))
    }

    @Test
    fun workedExampleNineOnTimePlusOneAnnulled() {
        // 9 hari tepat + 1 telat: 350.000 x 9 / 30 = 105.000. Setelah anulir: 10 hari = 116.666,67 -> 116.667.
        val onTime = (1..9).map { rec(it, Status.TEPAT) }
        val late = rec(10, Status.TELAT, late = 12)
        val before = recap(onTime + late)
        assertEquals(105_000L, before.earned)
        assertEquals(1, before.telat)
        assertEquals(12, before.totalLateMinutes)

        val annulled = AnnulRule.annul(late, AnnulRule.DEFAULT_REASON)!!
        val after = recap(onTime + annulled)
        assertEquals(116_667L, after.earned)
        assertEquals(10, after.tepat)
        assertEquals(0, after.telat)
        assertEquals(1, after.annulled)
        assertEquals(0, after.totalLateMinutes)
        assertEquals(10, after.creditedDays)
        assertEquals(after.earned, after.dailyCredits.values.sum())

        val gain = AnnulRule.saldoChange(evan, onTime + late, late, annulled, afterSep, everyDay, emptySet(), config)
        assertEquals(11_667L, gain)
        val loss = AnnulRule.saldoChange(evan, onTime + annulled, annulled, late, afterSep, everyDay, emptySet(), config)
        assertEquals(-11_667L, loss)
    }

    @Test
    fun annulOnHolidayAddsNothing() {
        val late = rec(10, Status.TELAT, late = 3)
        val annulled = AnnulRule.annul(late, "x")!!
        val holiday = setOf(sep.atDay(10))
        assertEquals(0L, AnnulRule.saldoChange(evan, listOf(late), late, annulled, afterSep, everyDay, holiday, config))
    }

    @Test
    fun annulTodayCountsImmediately() {
        val today = sep.atDay(10)
        val late = rec(10, Status.TELAT, late = 7)
        val annulled = AnnulRule.annul(late, "x")!!
        // 1 hari dari 30: 350.000 / 30 = 11.666,67 -> 11.667.
        assertEquals(11_667L, AnnulRule.saldoChange(evan, listOf(late), late, annulled, today, everyDay, emptySet(), config))
        assertEquals(11_667L, recap(listOf(annulled), today = today).earned)
    }

    @Test
    fun exportsShowTheTrailAndCsvRoundTrips() {
        val annulled = AnnulRule.annul(rec(10, Status.TELAT, late = 12), "Pintu, \"belum\" dibuka")!!
        val csv = RecapFormatter.detailCsv(sep, listOf(evan), listOf(annulled, rec(11, Status.TELAT, late = 4)))
        val rows = CsvImport.parse(csv).rows
        assertEquals(12, rows[0].annulledLateMinutes)
        assertEquals("Pintu, \"belum\" dibuka", rows[0].annulReason)
        assertEquals(Status.TEPAT, rows[0].status)
        assertNull(rows[1].annulledLateMinutes)
        assertEquals("", rows[1].annulReason)

        val r = recap(listOf(annulled))
        val md = RecapFormatter.markdown(sep, listOf(r), listOf(annulled), listOf(evan), config, "aturan", "now")
        assertTrue(md.contains("dianulir, telat 12 mnt"))
        assertTrue(md.contains("dianulir owner (dihitung tepat waktu): 1 kali"))
        assertTrue(RecapFormatter.summaryCsv(sep, listOf(r)).lines()[1].endsWith(",1"))
    }

    @Test
    fun markdownCellsStayIntactWithPipesNewlinesAndBlankReason() {
        val piped = AnnulRule.annul(rec(10, Status.TELAT, late = 12), "pintu | kunci\nhilang")!!
        val blank = rec(11, Status.TELAT, late = 3).copy(status = Status.TEPAT, lateMinutes = 0, annulledLateMinutes = 3, annulReason = "")
        val r = recap(listOf(piped, blank))
        val md = RecapFormatter.markdown(sep, listOf(r), listOf(piped, blank), listOf(evan.copy(name = "Evan | E")), config, "aturan", "now")
        val header = md.lines().first { it.startsWith("| Tanggal |") }
        val rows = md.lines().filter { it.startsWith("| 2026-09-1") }
        assertEquals(2, rows.size)
        rows.forEach { assertEquals(header.count { c -> c == '|' }, it.count { c -> c == '|' }) }
        assertTrue(rows[0].contains("dianulir, telat 12 mnt: pintu / kunci hilang"))
        assertTrue(rows[1].contains("dianulir, telat 3 mnt)"))
        val summary = md.lines().first { it.startsWith("| Evan") }
        assertEquals(md.lines().first { it.startsWith("| Nama |") }.count { it == '|' }, summary.count { it == '|' })
    }

    @Test
    fun oldCsvWithoutAnnulColumnsStillImports() {
        val old = "tanggal,nama,status,jam_masuk,menit_telat\n2026-09-10,Evan,TELAT,08:12:00,12\n"
        val row = CsvImport.parse(old).rows.single()
        assertNull(row.annulledLateMinutes)
        assertEquals("", row.annulReason)
    }
}
