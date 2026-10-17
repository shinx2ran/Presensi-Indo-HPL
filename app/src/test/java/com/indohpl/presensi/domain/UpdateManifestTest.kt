package com.indohpl.presensi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    private val json = """
        {"versionCode":115,"versionName":"1.2.115",
         "apkUrl":"https://raw.githubusercontent.com/shinx2ran/Presensi-Indo-HPL/apk-build/PresensiEGKost-115.apk",
         "sha256":"ABCDEF0123","sizeBytes":25378792,"notes":"Perbaikan GPS","commit":"4d9d531"}
    """.trimIndent()

    @Test
    fun parsesManifestPublishedByCi() {
        val m = UpdateManifest.parse(json)!!
        assertEquals(115, m.versionCode)
        assertEquals("1.2.115", m.versionName)
        assertTrue(m.apkUrl.endsWith("PresensiEGKost-115.apk"))
        assertEquals("abcdef0123", m.sha256) // dinormalkan ke huruf kecil
        assertEquals(25_378_792L, m.sizeBytes)
        assertEquals("Perbaikan GPS", m.notes)
    }

    @Test
    fun rejectsBrokenOrUnsafeManifest() {
        assertNull(UpdateManifest.parse(null))
        assertNull(UpdateManifest.parse(""))
        assertNull(UpdateManifest.parse("<html>404</html>"))
        assertNull(UpdateManifest.parse("""{"versionName":"1.2"}"""))
        assertNull(UpdateManifest.parse("""{"versionCode":0,"apkUrl":"https://x/a.apk"}"""))
        assertNull(UpdateManifest.parse("""{"versionCode":5,"apkUrl":"http://x/a.apk"}"""))
    }

    @Test
    fun optionalFieldsHaveDefaults() {
        val m = UpdateManifest.parse("""{"versionCode":7,"apkUrl":"https://x/a.apk"}""")!!
        assertEquals("7", m.versionName)
        assertEquals("", m.sha256)
        assertEquals(0L, m.sizeBytes)
    }

    @Test
    fun newerOnlyWhenVersionCodeIsHigher() {
        val m = UpdateManifest.parse(json)!!
        assertTrue(UpdateManifest.isNewer(m, 3))
        assertTrue(UpdateManifest.isNewer(m, 114))
        assertFalse(UpdateManifest.isNewer(m, 115))
        assertFalse(UpdateManifest.isNewer(m, 200))
    }
}
