package com.indohpl.presensi.data

import com.indohpl.presensi.data.backup.BackupCodec
import com.indohpl.presensi.data.backup.BackupData
import com.indohpl.presensi.domain.AbsenceItem
import com.indohpl.presensi.domain.BonusShare
import com.indohpl.presensi.domain.SalesBonusConfig
import com.indohpl.presensi.domain.SalesBonusPerson
import com.indohpl.presensi.domain.SalesBonusResult
import com.indohpl.presensi.domain.SalesTier
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesSnapshotTest {
    private val result = SalesBonusResult(
        ym = YearMonth.of(2026, 12),
        netSales = 650_000_000L,
        tier = SalesTier(650_000_000L, 700_000L),
        nextTier = SalesTier(700_000_000L, 900_000L),
        shortfall = 50_000_000L,
        participants = 2,
        pool = 1_400_000L,
        people = listOf(
            SalesBonusPerson(Employee(1, "Sara", "P"), true, 0, emptyList(), 0, BonusShare.FULL, BonusShare.FULL, 800_000L),
            SalesBonusPerson(
                Employee(3, "Evan \"E\"", "L"), true, 2,
                listOf(AbsenceItem(LocalDate.of(2026, 12, 10), 2, "izin")), 1, BonusShare.THREE_QUARTERS, BonusShare.THREE_QUARTERS, 600_000L,
            ),
        ),
    )

    @Test
    fun snapshotRoundTrip() {
        assertEquals(result, SalesSnapshot.decode(SalesSnapshot.encode(result)))
        assertEquals(result.copy(nextTier = null, tier = null), SalesSnapshot.decode(SalesSnapshot.encode(result.copy(nextTier = null, tier = null))))
        assertNull(SalesSnapshot.decode(""))
        assertNull(SalesSnapshot.decode("{rusak"))
    }

    @Test
    fun backupCarriesSalesDataAndOldBackupsHaveNone() {
        val month = SalesMonth("2026-12", 700_000_000L, 50_000_000L, "2026-12-20", "catatan, \"kutip\"", "5", 123L, SalesSnapshot.encode(result), 456L)
        val event = BonusEvent(9, 3, "2026-12-05", BonusEventType.TEGURAN, "main HP", "Koko", 789L)
        val cfg = SalesBonusConfig(listOf(SalesTier(500_000_000L, 400_000L)), YearMonth.of(2027, 1))
        val data = BackupData("now", "2.0.40", listOf(Employee(3, "Evan", "L")), emptyList(), emptyList(), AppSettings(), listOf(month), listOf(event), cfg)
        val back = BackupCodec.decode(BackupCodec.encode(data))
        assertEquals(listOf(month), back.salesMonths)
        assertEquals(listOf(event.copy(id = 0)), back.bonusEvents)
        assertEquals(cfg, back.salesConfig)

        val old = BackupCodec.decode("""{"format":"${BackupCodec.FORMAT}","version":1}""")
        assertTrue(old.salesMonths.isEmpty())
        assertTrue(old.bonusEvents.isEmpty())
        assertNull(old.salesConfig)
    }
}
