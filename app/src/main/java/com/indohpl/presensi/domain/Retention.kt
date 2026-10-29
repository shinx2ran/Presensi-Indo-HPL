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

    /** Jika catatan terbaru lebih tua dari ini, pembersihan OTOMATIS dilewati (jam HP salah / aplikasi lama tak dipakai). */
    const val STALE_DAYS = 35L

    /**
     * Pengaman pembersihan otomatis. Dalam pemakaian normal ada absen hampir tiap hari, jadi catatan terbaru
     * paling lama beberapa hari lalu. Kalau catatan terbaru jauh lebih tua dari [today] (jam HP melompat maju,
     * HP lama tidak dipakai, atau data baru diimpor setelah lama), hapus otomatis ditunda; owner tetap bisa
     * membersihkan manual dari Pengaturan.
     */
    fun shouldAutoClean(today: LocalDate, newestRecordDate: LocalDate?, monthsToDelete: Int = 1): Boolean =
        newestRecordDate != null &&
            !newestRecordDate.isBefore(today.minusDays(STALE_DAYS)) &&
            !newestRecordDate.isAfter(today) &&
            // Pemakaian normal: tiap tanggal 1 hanya satu bulan yang lewat batas. Lebih dari satu sekaligus
            // berarti ada yang aneh (jam HP pernah melompat, batas diturunkan) -> tunggu keputusan owner.
            monthsToDelete <= 1

    /** Tanggal pembersihan berikutnya (awal bulan depan) dan bulan yang akan terhapus saat itu. */
    fun nextCleanup(today: LocalDate, months: Int): Pair<LocalDate, YearMonth> {
        val next = YearMonth.from(today).plusMonths(1).atDay(1)
        return next to YearMonth.from(cutoffDate(next, months)).minusMonths(1)
    }
}
