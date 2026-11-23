package com.indohpl.presensi.domain

/** Hasil satu kali cek PIN owner. */
data class PinCheck(
    val ok: Boolean,
    /** > 0: sedang terkunci selama sekian milidetik lagi; PIN tidak diperiksa. */
    val lockedMsLeft: Long = 0L,
    val attemptsLeft: Int = PinLockout.ATTEMPTS_PER_ROUND,
    /** Jumlah PIN salah sebelum berhasil kali ini, supaya owner tahu bila ada yang mencoba-coba. */
    val failedBefore: Int = 0,
)

/**
 * Kunci PIN owner setelah salah berulang. Murni Kotlin supaya seluruh skenario bisa diuji; SettingsStore
 * hanya menyimpan [State] dan memberi waktu sekarang.
 *
 * - Hitungan salah disimpan permanen (tidak reset saat pindah tab, menutup dialog, atau menutup aplikasi).
 * - Tiap 5 kali salah terkunci, dan lamanya naik dua kali lipat: 30 dtk, 1, 2, 4, 8, lalu paling lama 15 menit.
 * - Kunci diukur dengan jam sejak HP menyala (elapsedRealtime, tidak bisa diubah pengguna), bukan jam HP.
 *   Memajukan jam HP tidak membuka kunci; memundurkannya tidak memperpanjang kunci.
 * - Menyalakan ulang HP tidak membuka kunci: elapsedRealtime mulai dari 0 lagi, jadi kunci dimulai ulang
 *   dengan lama penuhnya (paling lama 15 menit) sejak pengecekan pertama setelah HP menyala.
 * - Kunci yang sudah habis dihapus begitu terlihat (saat aplikasi dibuka, layar PIN dibuka, atau tepat setelah
 *   habis), supaya restart HP berikutnya tidak menghidupkannya lagi.
 */
object PinLockout {
    const val ATTEMPTS_PER_ROUND = 5
    const val BASE_LOCK_MS = 30_000L
    const val MAX_LOCK_MS = 15 * 60_000L

    /** Waktu sekarang: [wallMs] = System.currentTimeMillis, [elapsedMs] = SystemClock.elapsedRealtime. */
    data class Clock(val wallMs: Long, val elapsedMs: Long)

    /** Yang disimpan. Nilai 0 = tidak ada kunci. */
    data class State(
        val fails: Int = 0,
        val wallUntil: Long = 0L,
        val elapsedStart: Long = 0L,
        val elapsedUntil: Long = 0L,
    )

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
     * HP sudah dinyalakan ulang sejak kunci dimulai: elapsedRealtime sekarang lebih kecil dari awal kunci.
     * (Bila HP menyala lagi cukup lama sampai melewati awal kunci, dianggap HP yang sama; sisa kuncinya tetap
     * dihitung dari elapsedRealtime, jadi total tunggu sejak HP menyala tetap tidak lebih pendek dari lama kunci.)
     */
    private fun rebooted(s: State, now: Clock): Boolean = s.elapsedUntil > 0L && now.elapsedMs < s.elapsedStart

    private fun lockLength(s: State): Long = (s.elapsedUntil - s.elapsedStart).coerceIn(0L, MAX_LOCK_MS)

    /**
     * Sisa waktu kunci (ms); 0 = tidak terkunci. Tidak pernah lebih dari lama kuncinya (maks. [MAX_LOCK_MS]).
     * Acuannya elapsedRealtime (tidak bisa diubah pengguna), jadi mengubah jam HP ke depan atau ke belakang tidak
     * memendekkan maupun memperpanjang kunci. Jam dinding hanya cadangan untuk catatan tanpa data elapsed.
     */
    fun remainingMs(s: State, now: Clock): Long = when {
        s.elapsedUntil > 0L && rebooted(s, now) -> lockLength(s) // dimulai ulang penuh, lihat [normalize]
        s.elapsedUntil > 0L -> (s.elapsedUntil - now.elapsedMs).coerceIn(0L, MAX_LOCK_MS)
        s.wallUntil > 0L -> (s.wallUntil - now.wallMs).coerceIn(0L, MAX_LOCK_MS)
        else -> 0L
    }

    /**
     * Merapikan [State] sebelum dipakai dan disimpan: kunci yang tercatat dari sebelum HP dinyalakan ulang dimulai
     * ulang dengan lama penuhnya, kunci yang sudah habis dihapus, dan batas jam dinding dipangkas ke paling lama
     * 15 menit dari sekarang. Hitungan salah tetap.
     */
    fun normalize(s: State, now: Clock): State {
        if (rebooted(s, now)) {
            // Lama kunci tidak bisa diukur melewati restart, jadi kunci dimulai ulang dengan lama penuhnya.
            val lock = lockLength(s)
            return State(s.fails, now.wallMs + lock, now.elapsedMs, now.elapsedMs + lock)
        }
        if (remainingMs(s, now) == 0L) return State(fails = s.fails)
        val cappedWall = if (s.wallUntil > 0L) s.wallUntil.coerceAtMost(now.wallMs + MAX_LOCK_MS) else 0L
        return s.copy(wallUntil = if (cappedWall > now.wallMs) cappedWall else 0L)
    }

    /** Satu percobaan PIN. Mengembalikan state baru (untuk disimpan) dan hasilnya. */
    fun attempt(state: State, pinCorrect: Boolean, now: Clock): Pair<State, PinCheck> {
        val s = normalize(state, now)
        val left = remainingMs(s, now)
        if (left > 0L) return s to PinCheck(ok = false, lockedMsLeft = left, attemptsLeft = 0)
        if (pinCorrect) return State() to PinCheck(ok = true, failedBefore = s.fails)
        val n = s.fails + 1
        val lock = lockMsAfter(n)
        return if (lock > 0L) {
            State(n, now.wallMs + lock, now.elapsedMs, now.elapsedMs + lock) to
                PinCheck(ok = false, lockedMsLeft = lock, attemptsLeft = 0)
        } else {
            State(fails = n) to PinCheck(ok = false, attemptsLeft = attemptsLeft(n))
        }
    }
}
