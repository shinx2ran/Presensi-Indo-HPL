package com.indohpl.presensi.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PinLockoutTest {
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
    fun clockRollbackCannotLockForever() {
        val now = 1_000_000L
        assertEquals(now + 30_000L, PinLockout.effectiveLockedUntil(now + 30_000L, now))
        assertEquals(now + PinLockout.MAX_LOCK_MS, PinLockout.effectiveLockedUntil(now + 365L * 24 * 3600 * 1000, now))
        assertEquals(0L, PinLockout.effectiveLockedUntil(0L, now))
    }
}
