package com.indohpl.presensi.domain

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Aturan "telat": jam masuk lebih dari (jam kerja + toleransi). Murni Kotlin agar mudah diuji. */
object LateRule {
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun parseWorkStart(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim(), HHMM) }.getOrNull()

    /**
     * Menit keterlambatan, dibulatkan ke bawah, minimal 0.
     * Contoh: jam masuk 08:00, toleransi 5, datang 08:05:59 -> 0; datang 08:06:00 -> 6.
     */
    fun lateMinutes(arrival: LocalTime, workStart: String, toleranceMinutes: Int): Int {
        val start = parseWorkStart(workStart) ?: LocalTime.of(8, 0)
        val limit = start.plusMinutes(toleranceMinutes.toLong().coerceAtLeast(0))
        if (!arrival.isAfter(limit)) return 0
        // Dihitung dari jam masuk resmi (bukan dari batas toleransi) agar terlihat telat berapa menit sebenarnya.
        return ChronoUnit.MINUTES.between(start, arrival).toInt().coerceAtLeast(1)
    }
}
