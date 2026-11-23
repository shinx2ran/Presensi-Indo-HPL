package com.indohpl.presensi.domain

/**
 * Kunci PIN owner setelah salah berulang. Hitungan salah disimpan permanen (tidak reset saat pindah tab,
 * menutup dialog, atau menutup aplikasi), dan lama kunci naik dua kali lipat tiap 5 kali salah:
 * 30 dtk, 1 mnt, 2 mnt, 4 mnt, 8 mnt, lalu 15 mnt. PIN 4 angka jadi butuh ratusan jam untuk ditebak.
 * Murni Kotlin supaya bisa diuji.
 */
object PinLockout {
    const val ATTEMPTS_PER_ROUND = 5
    const val BASE_LOCK_MS = 30_000L
    const val MAX_LOCK_MS = 15 * 60_000L

    /** Lama kunci tepat setelah salah ke-[failCount] berturut-turut; 0 = belum dikunci. */
    fun lockMsAfter(failCount: Int): Long {
        if (failCount <= 0 || failCount % ATTEMPTS_PER_ROUND != 0) return 0L
        val round = failCount / ATTEMPTS_PER_ROUND
        val shift = (round - 1).coerceIn(0, 10)
        return (BASE_LOCK_MS shl shift).coerceAtMost(MAX_LOCK_MS)
    }

    /** Sisa percobaan sebelum kunci berikutnya. */
    fun attemptsLeft(failCount: Int): Int = ATTEMPTS_PER_ROUND - (failCount.coerceAtLeast(0) % ATTEMPTS_PER_ROUND)

    /**
     * Batas kunci yang berlaku. Bila jam HP pernah dimundurkan, batas tersimpan bisa jauh di masa depan;
     * dibatasi paling lama [MAX_LOCK_MS] dari sekarang supaya owner tidak terkunci bertahun-tahun.
     */
    fun effectiveLockedUntil(storedUntil: Long, now: Long): Long = storedUntil.coerceAtMost(now + MAX_LOCK_MS)
}
