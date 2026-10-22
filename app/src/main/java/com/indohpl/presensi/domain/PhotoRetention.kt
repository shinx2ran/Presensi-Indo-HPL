package com.indohpl.presensi.domain

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Aturan "simpan foto selfie N hari" (murni Kotlin, diuji unit test).
 * Foto presensi yang tanggalnya lebih lama dari N hari dihapus dari HP supaya memori tidak penuh;
 * data presensinya (jam, status, koordinat) tetap ada. 0 = simpan selamanya.
 */
object PhotoRetention {
    const val DEFAULT_DAYS = 60
    const val MIN_DAYS = 7
    const val MAX_DAYS = 3650

    /** Berkas sementara di cache (foto kamera mentah, ekspor) dianggap basi setelah 1 hari. */
    const val STALE_CACHE_MS = 24L * 60 * 60 * 1000

    private val LOCALE_ID: Locale = Locale.forLanguageTag("id-ID")

    /** 0 (selamanya) atau 7–3650 hari; sama dengan validasi server. */
    fun isValidDays(days: Int): Boolean = days == 0 || days in MIN_DAYS..MAX_DAYS

    /** Tanggal batas: foto presensi dengan tanggal SEBELUM tanggal ini dihapus. null = simpan selamanya. */
    fun cutoff(today: LocalDate, days: Int): LocalDate? = if (days <= 0) null else today.minusDays(days.toLong())

    /** true bila foto presensi bertanggal [recordDate] (yyyy-MM-dd) sudah lewat batas simpan. */
    fun isExpired(recordDate: String, cutoff: LocalDate?): Boolean {
        if (cutoff == null) return false
        val d = runCatching { LocalDate.parse(recordDate) }.getOrNull() ?: return false
        return d.isBefore(cutoff)
    }

    /** Awal hari [cutoff] (zona HP) dalam epoch milidetik; berkas yang lastModified-nya sebelum ini sudah lewat batas. */
    fun cutoffMillis(cutoff: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        cutoff.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Berkas cache sementara yang umurnya sudah lebih dari 1 hari. */
    fun isStaleCache(lastModified: Long, now: Long): Boolean = now - lastModified > STALE_CACHE_MS

    /** Ukuran ramah baca: "850 KB", "45,6 MB", "1,2 GB". */
    fun formatSize(bytes: Long): String = when {
        bytes < 1_000_000L -> "${(bytes + 999) / 1000} KB"
        bytes < 1_000_000_000L -> String.format(LOCALE_ID, "%.1f MB", bytes / 1_000_000.0)
        else -> String.format(LOCALE_ID, "%.1f GB", bytes / 1_000_000_000.0)
    }
}
