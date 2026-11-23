package com.indohpl.presensi.domain

import com.indohpl.presensi.domain.PinLockout.Clock
import com.indohpl.presensi.domain.PinLockout.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockoutTest {
    private val min = 60_000L
    private val year = 365L * 24 * 60 * min

    /** Simulasi penyimpanan: tiap percobaan memakai dan menyimpan state, seperti SettingsStore.checkOwnerPin. */
    private class Phone(var state: State = State()) {
        fun tryPin(correct: Boolean, now: Clock): PinCheck {
            val (next, r) = PinLockout.attempt(state, correct, now)
            state = next
            return r
        }
        /** Seperti SettingsStore.ownerPinLockMsLeft: rapikan, simpan, kembalikan sisa. */
        fun open(now: Clock): Long {
            state = PinLockout.normalize(state, now)
            return PinLockout.remainingMs(state, now)
        }
    }

    @Test
    fun locksEveryFifthFailureAndDoubles() {
        assertEquals(0L, PinLockout.lockMsAfter(0))
        for (n in 1..4) assertEquals(0L, PinLockout.lockMsAfter(n))
        assertEquals(30_000L, PinLockout.lockMsAfter(5))
        assertEquals(0L, PinLockout.lockMsAfter(6))
        assertEquals(60_000L, PinLockout.lockMsAfter(10))
        assertEquals(120_000L, PinLockout.lockMsAfter(15))
        assertEquals(240_000L, PinLockout.lockMsAfter(20))
        assertEquals(480_000L, PinLockout.lockMsAfter(25))
        assertEquals(900_000L, PinLockout.lockMsAfter(30))     // 960.000 dibatasi 15 menit
        assertEquals(900_000L, PinLockout.lockMsAfter(5_000))  // tidak meluap
    }

    @Test
    fun attemptsLeftCountsDownPerRound() {
        assertEquals(5, PinLockout.attemptsLeft(0))
        assertEquals(4, PinLockout.attemptsLeft(1))
        assertEquals(1, PinLockout.attemptsLeft(4))
        assertEquals(5, PinLockout.attemptsLeft(5))
        assertEquals(4, PinLockout.attemptsLeft(6))
    }

    @Test
    fun fiveWrongLockThirtySecondsAndCorrectPinIsRefusedWhileLocked() {
        val p = Phone()
        val t = Clock(1_000_000L, 50_000L)
        repeat(4) { i ->
            val r = p.tryPin(false, t)
            assertFalse(r.ok)
            assertEquals(4 - i, r.attemptsLeft)
            assertEquals(0L, r.lockedMsLeft)
        }
        val fifth = p.tryPin(false, t)
        assertEquals(30_000L, fifth.lockedMsLeft)
        val whileLocked = p.tryPin(true, Clock(t.wallMs + 10_000, t.elapsedMs + 10_000))
        assertFalse("PIN benar pun ditolak saat terkunci", whileLocked.ok)
        assertEquals(20_000L, whileLocked.lockedMsLeft)
        assertEquals("percobaan saat terkunci tidak menambah hitungan", 5, p.state.fails)

        val after = p.tryPin(true, Clock(t.wallMs + 30_000, t.elapsedMs + 30_000))
        assertTrue(after.ok)
        assertEquals(5, after.failedBefore)
        assertEquals(State(), p.state)
    }

    @Test
    fun counterSurvivesBetweenAttemptsAndOnlyResetsOnSuccess() {
        val p = Phone()
        var t = Clock(0L, 0L)
        repeat(5) { p.tryPin(false, t) }                     // kunci 30 dtk
        t = Clock(t.wallMs + 31_000, t.elapsedMs + 31_000)
        repeat(4) { p.tryPin(false, t) }
        assertEquals(9, p.state.fails)
        assertEquals(60_000L, p.tryPin(false, t).lockedMsLeft) // ronde 2: 1 menit
    }

    @Test
    fun movingClockForwardDoesNotUnlockWithoutReboot() {
        val p = Phone(State(fails = 29))
        val t = Clock(10 * year, 3_600_000L)
        assertEquals(15 * min, p.tryPin(false, t).lockedMsLeft)
        // Jam HP dimajukan 1 jam, padahal baru lewat 1 detik.
        val cheat = Clock(t.wallMs + 60 * min, t.elapsedMs + 1_000)
        assertEquals(15 * min - 1_000, p.open(cheat))
        assertFalse(p.tryPin(true, cheat).ok)
        // Setelah 15 menit sungguhan, terbuka.
        assertTrue(p.tryPin(true, Clock(cheat.wallMs + 15 * min, t.elapsedMs + 15 * min)).ok)
    }

    @Test
    fun clockSetAheadThenBackCannotLockOwnerForever() {
        // Skenario reviewer: jam HP dimajukan setahun, 5 kali salah, lalu jam dikembalikan.
        val p = Phone()
        val real = 10 * year
        val ahead = Clock(real + year, 1_000_000L)
        repeat(5) { p.tryPin(false, ahead) }
        // Jam dikembalikan (elapsed terus berjalan). Owner membuka layar PIN.
        val back = Clock(real, ahead.elapsedMs + 5_000)
        val shown = p.open(back)
        assertTrue(shown <= PinLockout.MAX_LOCK_MS)
        assertTrue("batas yang dipangkas disimpan", p.state.wallUntil <= back.wallMs + PinLockout.MAX_LOCK_MS)
        // 15 menit kemudian PIN benar diterima.
        val later = Clock(back.wallMs + 15 * min, back.elapsedMs + 15 * min)
        assertTrue(p.tryPin(true, later).ok)
    }

    @Test
    fun clockBackWithoutOpeningScreenFirstAlsoEnds() {
        val p = Phone()
        val real = 10 * year
        repeat(5) { p.tryPin(false, Clock(real + year, 1_000_000L)) }
        // Percobaan pertama setelah jam dikembalikan juga memangkas dan menyimpan batasnya.
        val back = Clock(real, 1_005_000L)
        assertTrue(p.tryPin(true, back).lockedMsLeft <= PinLockout.MAX_LOCK_MS)
        assertTrue(p.tryPin(true, Clock(real + 15 * min, 1_005_000L + 15 * min)).ok)
    }

    @Test
    fun rebootRestartsTheLockInsteadOfDroppingIt() {
        val p = Phone(State(fails = 29))
        val t = Clock(10 * year, 7_200_000L)
        p.tryPin(false, t)                                    // kunci 15 menit
        // HP dinyalakan ulang: elapsed mulai dari kecil lagi. Kunci dimulai ulang penuh 15 menit.
        val rebooted = Clock(t.wallMs + 2 * min, 30_000L)
        assertEquals(15 * min, p.open(rebooted))
        assertEquals(30_000L, p.state.elapsedStart)
        assertFalse(p.tryPin(true, Clock(t.wallMs + 15 * min, 30_000L + 13 * min)).ok)
        assertTrue(p.tryPin(true, Clock(t.wallMs + 17 * min, 30_000L + 15 * min)).ok)
    }

    @Test
    fun rebootPlusClockForwardDoesNotGiveFreeGuesses() {
        // Temuan review: kunci di uptime 64 dtk, jam HP dimajukan 1 jam, lalu HP dinyalakan ulang.
        val p = Phone()
        val t = Clock(10 * year, 64_000L)
        repeat(5) { p.tryPin(false, t) }                      // kunci 30 dtk
        val ahead = 60 * min
        assertFalse(p.tryPin(false, Clock(t.wallMs + ahead, 70_000L)).ok)
        assertEquals("ditolak tanpa dihitung", 5, p.state.fails)
        // Setelah restart (uptime 40 dtk): tetap terkunci 30 dtk, tebakan tidak diperiksa.
        val afterBoot = p.tryPin(true, Clock(t.wallMs + ahead + 60_000, 40_000L))
        assertFalse(afterBoot.ok)
        assertEquals(30_000L, afterBoot.lockedMsLeft)
        assertEquals(5, p.state.fails)
        assertTrue(p.tryPin(true, Clock(t.wallMs + ahead + 90_000, 70_000L)).ok)
    }

    @Test
    fun repeatedRebootsNeverShortenTheLock() {
        val p = Phone(State(fails = 29))
        var wall = 10 * year
        p.tryPin(false, Clock(wall, 500_000L))                // kunci 15 menit
        // Lima kali restart cepat, masing-masing setelah 1 menit menyala, jam HP terus dimajukan.
        repeat(5) {
            wall += 60 * min
            val left = p.open(Clock(wall, 20_000L))           // tepat setelah menyala
            assertEquals(15 * min, left)
            wall += min
            assertFalse(p.tryPin(true, Clock(wall, 80_000L)).ok)
        }
    }

    @Test
    fun longUptimeAfterRebootStillWaitsAtLeastTheLockLength() {
        // HP menyala lagi dan tidak ada yang membuka PIN sampai uptime melewati awal kunci:
        // sisa dihitung dari elapsed, jadi total tunggu sejak menyala >= lama kunci.
        val p = Phone(State(fails = 29))
        p.tryPin(false, Clock(10 * year, 100_000L))           // kunci 15 menit: elapsed 100 dtk .. 1000 dtk
        // Jam HP dimajukan 1 jam, jadi hanya uptime yang menahan.
        val left = p.open(Clock(10 * year + 60 * min, 400_000L))
        assertEquals(600_000L, left)                          // sudah menyala 400 dtk + sisa 600 dtk = 1000 dtk >= 900 dtk
        // Dengan jam jujur (+2 menit), kunci jam dinding (13 menit) yang lebih lama berlaku.
        val honest = Phone(State(fails = 29))
        honest.tryPin(false, Clock(10 * year, 100_000L))
        assertEquals(13 * min, honest.open(Clock(10 * year + 2 * min, 400_000L)))
    }

    @Test
    fun expiredLockIsClearedButFailsKept() {
        val s = PinLockout.normalize(State(fails = 7, wallUntil = 100L, elapsedStart = 0L, elapsedUntil = 100L), Clock(200L, 200L))
        assertEquals(State(fails = 7), s)
    }

    @Test
    fun remainingNeverExceedsMax() {
        val s = State(fails = 5, wallUntil = Long.MAX_VALUE, elapsedStart = 0L, elapsedUntil = Long.MAX_VALUE)
        assertEquals(PinLockout.MAX_LOCK_MS, PinLockout.remainingMs(s, Clock(0L, 0L)))
    }
}
