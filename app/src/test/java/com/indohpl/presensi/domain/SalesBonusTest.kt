package com.indohpl.presensi.domain

import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.BonusEvent
import com.indohpl.presensi.data.BonusEventType
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Status
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesBonusTest {
    private val everyDay = DayOfWeek.values().toSet()
    private val config = SalesBonusConfig()
    private val dec = YearMonth.of(2026, 12)             // 31 hari, semua hari kerja
    private val afterDec = LocalDate.of(2027, 1, 1)
    private val sara = Employee(1, "Sara", "P")
    private val riyanti = Employee(2, "Riyanti", "P")
    private val evan = Employee(3, "Evan", "L")
    private val madi = Employee(4, "Madi", "L")
    private val roni = Employee(5, "Roni", "L")
    private val team = listOf(sara, riyanti, evan, madi, roni)

    private fun rec(emp: Employee, day: Int, status: String = Status.TEPAT, late: Int = 0, annulled: Int? = null) = AttendanceRecord(
        employeeId = emp.id, date = dec.atDay(day).toString(), timestamp = 0L, timeIn = "08:00:00",
        status = status, lateMinutes = late, photoPath = null, annulledLateMinutes = annulled,
    )

    /** Semua karyawan tepat waktu setiap hari, lalu [changes] mengganti/menghapus catatan tertentu. */
    private fun month(vararg changes: Pair<Pair<Employee, Int>, AttendanceRecord?>): List<AttendanceRecord> {
        val map = LinkedHashMap<Pair<Long, Int>, AttendanceRecord>()
        for (e in team) for (d in 1..31) map[e.id to d] = rec(e, d)
        for ((key, r) in changes) {
            val k = key.first.id to key.second
            if (r == null) map.remove(k) else map[k] = r
        }
        return map.values.toList()
    }

    private fun event(emp: Employee, day: Int, type: String, at: Long = day.toLong()) =
        BonusEvent(employeeId = emp.id, date = dec.atDay(day).toString(), type = type, note = "", byWhom = "Koko", createdAt = at)

    private fun calc(
        records: List<AttendanceRecord>,
        sales: Long = 650_000_000L,
        events: List<BonusEvent> = emptyList(),
        excluded: Set<Long> = emptySet(),
        today: LocalDate = afterDec,
        holidays: Set<LocalDate> = emptySet(),
        ym: YearMonth = dec,
    ) = SalesBonus.calculate(ym, today, sales, config, team, excluded, records, events, everyDay, holidays)

    private fun SalesBonusResult.payout(e: Employee) = people.single { it.employee.id == e.id }.payout
    private fun SalesBonusResult.person(e: Employee) = people.single { it.employee.id == e.id }

    @Test
    fun tierBoundaries() {
        assertNull(SalesBonus.tierFor(config, 599_999_999L))
        assertEquals(500_000L, SalesBonus.tierFor(config, 600_000_000L)!!.perPerson)
        assertEquals(500_000L, SalesBonus.tierFor(config, 649_999_999L)!!.perPerson)
        assertEquals(700_000L, SalesBonus.tierFor(config, 650_000_000L)!!.perPerson)
        assertEquals(900_000L, SalesBonus.tierFor(config, 700_000_000L)!!.perPerson)
        assertEquals(1_100_000L, SalesBonus.tierFor(config, 750_000_000L)!!.perPerson)
        assertEquals(1_100_000L, SalesBonus.tierFor(config, 2_000_000_000L)!!.perPerson)
        assertEquals(650_000_000L, SalesBonus.nextTierFor(config, 612_500_000L)!!.minSales)
        assertNull(SalesBonus.nextTierFor(config, 750_000_000L))
    }

    @Test
    fun shareFromAbsenceAndWarnings() {
        assertEquals(BonusShare.FULL, SalesBonus.finalShare(0, 0))
        assertEquals(BonusShare.THREE_QUARTERS, SalesBonus.finalShare(1, 0))  // setengah hari
        assertEquals(BonusShare.THREE_QUARTERS, SalesBonus.finalShare(2, 0))  // 1 hari
        assertEquals(BonusShare.ONE_QUARTER, SalesBonus.finalShare(3, 0))     // 1,5 hari
        assertEquals(BonusShare.ONE_QUARTER, SalesBonus.finalShare(4, 0))     // 2 hari
        assertEquals(BonusShare.NONE, SalesBonus.finalShare(5, 0))            // lebih dari 2 hari
        assertEquals(BonusShare.FULL, SalesBonus.finalShare(0, 1))            // teguran pertama: peringatan saja
        assertEquals(BonusShare.NONE, SalesBonus.finalShare(0, 2))            // teguran kedua: tidak dapat
    }

    @Test
    fun everyoneAttends650MillionGives700kEach() {
        val r = calc(month())
        assertEquals(650_000_000L, r.tier!!.minSales)
        assertEquals(5, r.participants)
        assertEquals(3_500_000L, r.pool)
        team.forEach { assertEquals(700_000L, r.payout(it)) }
        assertEquals(3_500_000L, r.totalPaid)
    }

    @Test
    fun announcementExampleOneAbsentOneDay() {
        // Contoh pengumuman: 650 juta, 1 orang tidak masuk 1 hari -> 4 orang ±737.000, dia ±553.000.
        val r = calc(month((evan to 10) to rec(evan, 10, Status.IZIN)))
        assertEquals(BonusShare.THREE_QUARTERS, r.person(evan).share)
        // 3.500.000 x 4/19 = 736.842,1 ; 3.500.000 x 3/19 = 552.631,6
        listOf(sara, riyanti, madi, roni).forEach { assertEquals(736_842L, r.payout(it)) }
        assertEquals(552_632L, r.payout(evan))
        assertEquals(3_500_000L, r.totalPaid)
    }

    @Test
    fun announcementExampleOneAbsentTwoDays() {
        // Contoh pengumuman: 1 orang tidak masuk 2 hari -> 4 orang ±824.000, dia ±206.000.
        val r = calc(month((evan to 10) to rec(evan, 10, Status.SAKIT), (evan to 11) to null))
        assertEquals(4, r.person(evan).absentHalfDays)
        assertEquals(BonusShare.ONE_QUARTER, r.person(evan).share)
        // 3.500.000 x 4/17 = 823.529,4 ; 3.500.000 x 1/17 = 205.882,4
        listOf(sara, riyanti, madi, roni).forEach { assertEquals(823_529L, r.payout(it)) }
        assertEquals(205_882L, r.payout(evan))
        assertEquals(3_499_998L, r.totalPaid) // selisih pembulatan Rp 2
    }

    @Test
    fun moreThanTwoDaysGetsNothingAndTheShareGoesToOthers() {
        val r = calc(month((madi to 3) to null, (madi to 4) to null, (madi to 5) to rec(madi, 5, Status.TELAT, late = 130)))
        assertEquals(5, r.person(madi).absentHalfDays) // 2 + 2 + 1
        assertEquals(BonusShare.NONE, r.person(madi).share)
        assertEquals(0L, r.payout(madi))
        listOf(sara, riyanti, evan, roni).forEach { assertEquals(875_000L, r.payout(it)) } // 3.500.000 / 4
    }

    @Test
    fun halfDayRules() {
        val holiday = dec.atDay(25)
        val r = calc(
            month(
                (sara to 2) to rec(sara, 2, Status.TELAT, late = 121),             // > 2 jam: 1/2
                (sara to 3) to rec(sara, 3, Status.TELAT, late = 120),             // tepat 2 jam: tidak
                (riyanti to 4) to rec(riyanti, 4, Status.TEPAT, annulled = 180),    // dianulir: tidak
                (roni to 6) to rec(roni, 6, Status.TELAT, late = 200),             // telat > 2 jam + setengah hari = 1 hari
            ),
            events = listOf(
                event(evan, 7, BonusEventType.SETENGAH_HARI),                       // pulang cepat: 1/2
                event(roni, 6, BonusEventType.SETENGAH_HARI),
                event(madi, 25, BonusEventType.SETENGAH_HARI),                      // hari libur: tidak dihitung
            ),
            holidays = setOf(holiday),
        )
        assertEquals(1, r.person(sara).absentHalfDays)
        assertEquals("telat 2 jam 1 mnt", r.person(sara).absences.single().reason)
        assertEquals(0, r.person(riyanti).absentHalfDays)
        assertEquals(1, r.person(evan).absentHalfDays)
        assertEquals(2, r.person(roni).absentHalfDays)
        assertEquals("telat 3 jam 20 mnt + setengah hari", r.person(roni).absences.single().reason)
        assertEquals(0, r.person(madi).absentHalfDays)
        assertEquals(BonusShare.THREE_QUARTERS, r.person(sara).share)
        assertEquals(BonusShare.THREE_QUARTERS, r.person(roni).share)
    }

    @Test
    fun midMonthTodayWithoutCheckInAndFutureDaysAreNotCounted() {
        val today = dec.atDay(15)
        val records = month().filter { LocalDate.parse(it.date).isBefore(today) } // belum ada yang absen hari ini
        val r = calc(records, today = today, events = listOf(event(evan, 20, BonusEventType.SETENGAH_HARI)))
        team.forEach { assertEquals(0, r.person(it).absentHalfDays) }
        // Kemarin tanpa catatan = tidak masuk.
        val r2 = calc(records.filterNot { it.employeeId == sara.id && it.date == dec.atDay(14).toString() }, today = today)
        assertEquals(2, r2.person(sara).absentHalfDays)
    }

    @Test
    fun secondWarningForfeitsFirstIsOnlyAWarning() {
        val r = calc(
            month(),
            events = listOf(
                event(madi, 3, BonusEventType.TEGURAN), event(madi, 20, BonusEventType.TEGURAN),
                event(roni, 8, BonusEventType.TEGURAN),
            ),
        )
        assertEquals(2, r.person(madi).warnings)
        assertEquals(BonusShare.FULL, r.person(madi).attendanceShare) // kehadirannya tetap penuh
        assertEquals(BonusShare.NONE, r.person(madi).share)
        assertEquals(0L, r.payout(madi))
        assertEquals(BonusShare.FULL, r.person(roni).share)
        listOf(sara, riyanti, evan, roni).forEach { assertEquals(875_000L, r.payout(it)) }
    }

    @Test
    fun warningsInAnotherMonthDoNotCount() {
        val nov = BonusEvent(employeeId = madi.id, date = "2026-11-30", type = BonusEventType.TEGURAN, note = "", byWhom = "Akong", createdAt = 1)
        val r = calc(month(), events = listOf(nov, event(madi, 3, BonusEventType.TEGURAN)))
        assertEquals(1, r.person(madi).warnings)
        assertEquals(700_000L, r.payout(madi))
    }

    @Test
    fun excludedEmployeeDoesNotAddToThePool() {
        val r = calc(month(), excluded = setOf(roni.id))
        assertEquals(4, r.participants)
        assertEquals(2_800_000L, r.pool)
        assertFalse(r.person(roni).participating)
        assertEquals(0L, r.payout(roni))
        listOf(sara, riyanti, evan, madi).forEach { assertEquals(700_000L, r.payout(it)) }
    }

    @Test
    fun belowTargetBeforeStartAndNobodyEligible() {
        val below = calc(month(), sales = 599_999_999L)
        assertNull(below.tier)
        assertEquals(1L, below.shortfall)
        assertEquals(0L, below.pool)
        assertEquals(0L, below.totalPaid)

        val nov = calc(emptyList(), sales = 800_000_000L, ym = YearMonth.of(2026, 11), today = LocalDate.of(2026, 12, 1))
        assertNull("program mulai Desember 2026", nov.tier)
        assertEquals(0L, nov.pool)

        val allAbsent = calc(emptyList())
        assertEquals(3_500_000L, allAbsent.pool)
        assertEquals(0L, allAbsent.totalPaid)
    }

    @Test
    fun netSalesExcludesProjectAndNeverNegative() {
        assertEquals(640_000_000L, SalesBonus.netSales(700_000_000L, 60_000_000L))
        assertEquals(0L, SalesBonus.netSales(10L, 20L))
    }

    @Test
    fun distributeIsFairAndCloseToPool() {
        val weights = listOf(4, 3, 1, 0)
        var seed = 7L
        repeat(2_000) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val n = 1 + ((seed ushr 33) % 8).toInt()
            val q = (0 until n).associate { i -> i.toLong() to weights[((seed ushr (i * 4 + 1)) and 3).toInt()] }
            val pool = 100_000L * (1 + ((seed ushr 20) % 60))
            val out = SalesBonus.distribute(pool, q)
            val total = q.values.sum()
            if (total == 0) {
                assertTrue(out.values.all { it == 0L })
            } else {
                // Bobot sama -> jumlah sama; bobot lebih besar -> tidak lebih kecil.
                q.forEach { (a, qa) -> q.forEach { (b, qb) -> if (qa == qb) assertEquals(out[a], out[b]) else if (qa > qb) assertTrue(out[a]!! >= out[b]!!) } }
                assertTrue("selisih pembulatan kecil", kotlin.math.abs(out.values.sum() - pool) <= n)
            }
        }
    }

    @Test
    fun announcementTexts() {
        val progress = calc(month(), sales = 612_500_000L, today = dec.atDay(20))
        val t1 = SalesBonus.announcement(progress, config, dec.atDay(20), final = false)
        assertTrue(t1, t1.contains("sampai 20 Desember"))
        assertTrue(t1, t1.contains("Penjualan: *Rp612.500.000*"))
        assertTrue(t1, t1.contains("✅ Target 600 juta tercapai → *Rp500.000 / orang*"))
        assertTrue(t1, t1.contains("🎯 Kurang *Rp37.500.000* lagi ke 650 juta (Rp700.000 / orang)"))

        val top = SalesBonus.announcement(calc(month(), sales = 760_000_000L), config, dec.atDay(31), final = true)
        assertTrue(top, top.contains("Hasil akhir Desember 2026"))
        assertTrue(top, top.contains("Target 750 juta tercapai"))
        assertTrue(top, top.contains("dicairkan tanggal 1 Januari 2027"))
        assertFalse(top, top.contains("Kurang"))

        val miss = SalesBonus.announcement(calc(month(), sales = 580_000_000L), config, dec.atDay(31), final = true)
        assertTrue(miss, miss.contains("Belum mencapai target 600 juta (kurang Rp20.000.000)"))
    }

    @Test
    fun markdownSectionForClaude() {
        val r = calc(month((evan to 10) to rec(evan, 10, Status.IZIN)))
        val md = RecapFormatter.salesBonusMarkdown(r, config, "2026-12-31", paid = true)
        assertTrue(md, md.contains("**Rp 650.000.000**"))
        assertTrue(md, md.contains("sudah dibayar"))
        assertTrue(md, md.contains("Rp 700.000/orang x 5 peserta = uang bonus **Rp 3.500.000**"))
        assertTrue(md, md.contains("| Evan | ya | 1 | 0 | ¾ | **Rp 552.632** |"))
        assertTrue(md, md.contains("- 2026-12-10 Evan: 1 hari (izin)"))
        val below = RecapFormatter.salesBonusMarkdown(calc(month(), sales = 590_000_000L), config, "", paid = false)
        assertTrue(below, below.contains("Belum mencapai target 600 juta (kurang Rp 10.000.000)"))
    }

    @Test
    fun textHelpers() {
        assertEquals("600 juta", SalesBonus.juta(600_000_000L))
        assertEquals("612,5 juta", SalesBonus.juta(612_500_000L))
        assertEquals("1.250 juta", SalesBonus.juta(1_250_000_000L))
        assertEquals("Rp1.100.000", SalesBonus.rp(1_100_000L))
        assertEquals(612_500_000L, SalesBonus.parseRupiah("Rp 612.500.000"))
        assertNull(SalesBonus.parseRupiah("  "))
        assertEquals(999_999_999_999_999L, SalesBonus.parseRupiah("9999999999999999")) // dipotong 15 digit
        // Angka ditempel dari laporan: sen dibuang, tidak jadi 100x.
        assertEquals(612_500_000L, SalesBonus.parseRupiah("Rp 612.500.000,00"))
        assertEquals(612_500_000L, SalesBonus.parseRupiah("612,500,000.00"))
        assertEquals(612_500_000L, SalesBonus.parseRupiah("612.500.000,5"))
        assertEquals(1_250L, SalesBonus.parseRupiah("1.250"))   // 3 digit setelah titik = ribuan
        assertEquals("612500000", SalesBonus.rupiahDigits("612.500.000,00"))
        assertEquals("", SalesBonus.rupiahDigits("Rp"))
    }

    @Test
    fun tiersRoundTripAndGarbageFallsBack() {
        val custom = listOf(SalesTier(500_000_000L, 400_000L), SalesTier(800_000_000L, 1_500_000L))
        assertEquals(custom, SalesBonus.decodeTiers(SalesBonus.encodeTiers(custom)))
        assertEquals(SalesBonus.DEFAULT_TIERS, SalesBonus.decodeTiers(null))
        assertEquals(SalesBonus.DEFAULT_TIERS, SalesBonus.decodeTiers("rusak;;x:y"))
        assertEquals(listOf(SalesTier(1L, 2L)), SalesBonus.decodeTiers("1:2;0:5;7:-1"))
    }
}
