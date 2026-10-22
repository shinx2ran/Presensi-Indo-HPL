package com.indohpl.presensi.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoRetentionTest {
    private val today = LocalDate.of(2026, 10, 22)

    @Test
    fun cutoffIsTodayMinusDays() {
        assertEquals(LocalDate.of(2026, 8, 23), PhotoRetention.cutoff(today, 60))
        assertEquals(LocalDate.of(2026, 10, 15), PhotoRetention.cutoff(today, 7))
        assertNull(PhotoRetention.cutoff(today, 0))   // 0 = simpan selamanya
        assertNull(PhotoRetention.cutoff(today, -5))
    }

    @Test
    fun photoExpiresOnlyBeforeCutoff() {
        val cutoff = PhotoRetention.cutoff(today, 60)
        assertTrue(PhotoRetention.isExpired("2026-08-22", cutoff))   // 61 hari lalu: dihapus
        assertFalse(PhotoRetention.isExpired("2026-08-23", cutoff))  // tepat 60 hari: disimpan
        assertFalse(PhotoRetention.isExpired("2026-10-22", cutoff))
        assertTrue(PhotoRetention.isExpired("2025-12-31", cutoff))
        assertFalse(PhotoRetention.isExpired("rusak", cutoff))
        assertFalse(PhotoRetention.isExpired("2020-01-01", null))    // simpan selamanya
    }

    @Test
    fun validDaysMatchServerRule() {
        assertTrue(PhotoRetention.isValidDays(0))
        assertFalse(PhotoRetention.isValidDays(1))
        assertFalse(PhotoRetention.isValidDays(6))
        assertTrue(PhotoRetention.isValidDays(7))
        assertTrue(PhotoRetention.isValidDays(60))
        assertTrue(PhotoRetention.isValidDays(3650))
        assertFalse(PhotoRetention.isValidDays(3651))
        assertFalse(PhotoRetention.isValidDays(-1))
        assertEquals(60, PhotoRetention.DEFAULT_DAYS)
    }

    @Test
    fun cutoffMillisIsStartOfDayInPhoneZone() {
        val jakarta = ZoneId.of("Asia/Jakarta")
        val expected = ZonedDateTime.of(2026, 8, 23, 0, 0, 0, 0, jakarta).toInstant().toEpochMilli()
        assertEquals(expected, PhotoRetention.cutoffMillis(LocalDate.of(2026, 8, 23), jakarta))
    }

    @Test
    fun staleCacheAfterOneDay() {
        val now = 1_800_000_000_000L
        assertFalse(PhotoRetention.isStaleCache(now - 60_000, now))
        assertFalse(PhotoRetention.isStaleCache(now - PhotoRetention.STALE_CACHE_MS, now))
        assertTrue(PhotoRetention.isStaleCache(now - PhotoRetention.STALE_CACHE_MS - 1, now))
    }

    @Test
    fun formatsSizeInIndonesian() {
        assertEquals("0 KB", PhotoRetention.formatSize(0))
        assertEquals("850 KB", PhotoRetention.formatSize(850_000))
        assertEquals("1,5 MB", PhotoRetention.formatSize(1_500_000))
        assertEquals("45,6 MB", PhotoRetention.formatSize(45_600_000))
        assertEquals("1,2 GB", PhotoRetention.formatSize(1_234_000_000))
    }
}
