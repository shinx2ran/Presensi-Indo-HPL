package com.indohpl.presensi

import com.indohpl.presensi.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Memastikan varian indohpl tetap memakai konfigurasi toko Indo HPL yang lama. */
class SiteTest {
    @Test
    fun indoHplConfig() {
        assertEquals("Presensi Indo HPL", Site.appTitle)
        assertEquals("toko", Site.place)
        assertEquals(listOf("Sara", "Riyanti", "Evan", "Madi", "Roni"), Site.seedEmployees.map { it.name })
        assertEquals(listOf("P", "P", "L", "L", "L"), Site.seedEmployees.map { it.gender })
        assertEquals(Site.seedEmployees.size, Site.seedEmployees.map { it.id }.distinct().size)
        val s = AppSettings()
        assertEquals("07:45", s.workStartFemale)
        assertEquals(10, s.toleranceFemale)
        assertEquals("08:00", s.workStartMale)
        assertEquals(5, s.toleranceMale)
        assertFalse(s.sameRuleForAll)
        assertEquals("Perempuan 07:45 (toleransi 10 mnt) · Laki-laki 08:00 (toleransi 5 mnt)", s.rulesText())
    }
}
