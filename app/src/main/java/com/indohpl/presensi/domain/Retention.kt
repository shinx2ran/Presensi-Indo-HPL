package com.indohpl.presensi.domain

import java.time.LocalDate
import java.time.YearMonth

/**
 * Aturan pembersihan otomatis. Data disimpan untuk bulan berjalan + [months] bulan sebelumnya;
 * yang lebih lama dihapus. Murni Kotlin agar bisa diuji.
 */
object Retention {
    const val MIN_MONTHS = 1
    const val MAX_MONTHS = 24

    /** Tanggal pertama yang masih disimpan. Semua catatan dengan tanggal < nilai ini dihapus. */
    fun cutoffDate(today: LocalDate, months: Int): LocalDate =
        YearMonth.from(today).minusMonths(months.coerceIn(MIN_MONTHS, MAX_MONTHS).toLong()).atDay(1)

    /** Tanggal pembersihan berikutnya (awal bulan depan) dan bulan yang akan terhapus saat itu. */
    fun nextCleanup(today: LocalDate, months: Int): Pair<LocalDate, YearMonth> {
        val next = YearMonth.from(today).plusMonths(1).atDay(1)
        return next to YearMonth.from(cutoffDate(next, months)).minusMonths(1)
    }
}
