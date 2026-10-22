package com.indohpl.presensi

import com.indohpl.presensi.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Memastikan varian egkost memakai karyawan dan jam masuk EG Kost. */
class SiteTest {
    @Test
    fun egKostConfig() {
        assertEquals("Presensi EG Kost", Site.appTitle)
        assertEquals("kost", Site.place)
        assertEquals(listOf("Inul", "Bik Nia"), Site.seedEmployees.map { it.name })
        assertEquals(listOf("Laundry", "Kos"), Site.seedEmployees.map { it.role })
        assertEquals(listOf("Inul · Laundry", "Bik Nia · Kos"), Site.seedEmployees.map { it.label })
        assertEquals(Site.seedEmployees.size, Site.seedEmployees.map { it.id }.distinct().size)
        val s = AppSettings()
        assertEquals("07:30", s.workStartFemale)
        assertEquals("07:30", s.workStartMale)
        assertEquals(10, s.toleranceFemale)
        assertEquals(10, s.toleranceMale)
        assertTrue(s.sameRuleForAll)
        assertEquals("07:30 (toleransi 10 mnt)", s.rulesText())
        assertEquals("07:30 (+10 mnt)", s.shortRulesText())
        assertEquals(60, s.photoRetentionDays)
        assertEquals("HP Laundry", Site.defaultDeviceLabel)
    }
}
