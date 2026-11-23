package com.indohpl.presensi.data

import com.indohpl.presensi.data.backup.BackupCodec
import com.indohpl.presensi.data.backup.BackupData
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    private val trickyNote = "Dia bilang \"halo\", lalu\nbaris kedua\ttab \\ garis miring, ünïcödé € 日本語 😀"

    private val full = AttendanceRecord(
        id = 41,
        employeeId = 3,
        date = "2026-11-02",
        timestamp = 1_793_000_000_123L,
        timeIn = "07:59:12",
        status = Status.TEPAT,
        lateMinutes = 0,
        photoPath = "/data/user/0/com.indohpl.presensi/files/photos/2026-11/IMG_0001.jpg",
        note = trickyNote,
        latitude = -6.200123456789,
        longitude = 106.816666,
        accuracy = 3.14159f,
        distanceMeters = 12,
        inLocation = true,
        locationNote = "Dalam radius, GPS \"bagus\"",
    )

    private val excused = AttendanceRecord(
        id = 42,
        employeeId = 7,
        date = "2026-11-03",
        timestamp = 1_793_086_400_000L,
        timeIn = "",
        status = Status.SAKIT,
        lateMinutes = 0,
        photoPath = null,
        note = "",
    )

    private fun sample(settings: AppSettings) = BackupData(
        createdAt = "2026-11-21T10:15:30.123",
        appVersion = "2.0.57",
        employees = listOf(
            Employee(1, "Sara", "P", true),
            Employee(7, "Dewi \"D\", Ü", "P", false),
        ),
        attendance = listOf(full, excused),
        holidays = listOf(Holiday("2026-12-25", "Natal, \"libur\"")),
        settings = settings,
    )

    @Test
    fun roundTripKeepsEveryFieldKind() {
        val settings = AppSettings(
            workStartFemale = "07:30",
            toleranceFemale = 15,
            workStartMale = "08:10",
            toleranceMale = 3,
            monthlyBonus = 400_000,
            perDayAmount = 12_500,
            workDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY),
            storeLat = -6.2,
            storeLon = 106.8,
            radiusMeters = 150,
            requireLocation = false,
            retentionMonths = 6,
        )
        val src = sample(settings)
        val back = BackupCodec.decode(BackupCodec.encode(src))

        assertEquals(src.createdAt, back.createdAt)
        assertEquals(src.appVersion, back.appVersion)
        assertEquals(src.employees, back.employees)
        assertEquals(src.holidays, back.holidays)
        assertEquals(settings, back.settings)

        // id tidak disimpan (jadi 0) dan foto hanya nama file.
        assertEquals(2, back.attendance.size)
        assertEquals(full.copy(id = 0, photoPath = "IMG_0001.jpg"), back.attendance[0])
        assertEquals(excused.copy(id = 0), back.attendance[1])
        assertEquals(trickyNote, back.attendance[0].note)
    }

    @Test
    fun annulTrailSurvivesBackupAndOldBackupsHaveNone() {
        val annulled = full.copy(annulledLateMinutes = 12, annulReason = "Pintu \"belum\" dibuka")
        val back = BackupCodec.decode(BackupCodec.encode(sample(AppSettings()).copy(attendance = listOf(annulled, excused))))
        assertEquals(12, back.attendance[0].annulledLateMinutes)
        assertEquals("Pintu \"belum\" dibuka", back.attendance[0].annulReason)
        assertNull(back.attendance[1].annulledLateMinutes)
        assertEquals("", back.attendance[1].annulReason)

        val old = BackupCodec.decode(
            """{"format":"${BackupCodec.FORMAT}","version":1,"attendance":[{"employeeId":2,"date":"2026-11-05","status":"TEPAT"}]}""",
        ).attendance.single()
        assertNull(old.annulledLateMinutes)
        assertEquals("", old.annulReason)
    }

    @Test
    fun nullablesStayNullAndStoreLocationCanBeNull() {
        val src = sample(AppSettings(storeLat = null, storeLon = null))
        val text = BackupCodec.encode(src)
        assertFalse("null tidak boleh jadi teks \"null\"", text.contains("\"null\""))
        val back = BackupCodec.decode(text)
        assertNull(back.settings.storeLat)
        assertNull(back.settings.storeLon)
        val r = back.attendance[1]
        assertNull(r.photoPath)
        assertNull(r.latitude)
        assertNull(r.longitude)
        assertNull(r.accuracy)
        assertNull(r.distanceMeters)
        assertFalse(r.inLocation)
        assertEquals("", r.locationNote)
    }

    @Test
    fun photoPathIsReducedToFileName() {
        val text = BackupCodec.encode(sample(AppSettings()))
        assertTrue(text.contains("IMG_0001.jpg"))
        assertFalse(text.contains("/data/user/0"))
    }

    @Test
    fun decodeRejectsWrongFormat() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode("""{"format":"lain","version":1}""")
        }
        assertEquals("File ini bukan cadangan Presensi Indo HPL", e.message)
        val e2 = assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("bukan json sama sekali") }
        assertEquals("File ini bukan cadangan Presensi Indo HPL", e2.message)
        val e3 = assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("""{"version":1}""") }
        assertEquals("File ini bukan cadangan Presensi Indo HPL", e3.message)
    }

    @Test
    fun decodeRejectsTooNewVersion() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode("""{"format":"${BackupCodec.FORMAT}","version":${BackupCodec.VERSION + 1}}""")
        }
        assertEquals("Versi cadangan terlalu baru, perbarui aplikasi dulu", e.message)
    }

    @Test
    fun missingOptionalFieldsFallBackToDefaults() {
        val minimal = BackupCodec.decode("""{"format":"${BackupCodec.FORMAT}","version":1}""")
        assertTrue(minimal.employees.isEmpty())
        assertTrue(minimal.attendance.isEmpty())
        assertTrue(minimal.holidays.isEmpty())
        assertEquals(AppSettings(), minimal.settings)
        assertEquals("", minimal.createdAt)

        val partial = BackupCodec.decode(
            """{"format":"${BackupCodec.FORMAT}","version":1,"settings":{"monthlyBonus":500000,"workDays":[1,2,9],"storeLat":null}}""",
        )
        val d = AppSettings()
        assertEquals(500_000L, partial.settings.monthlyBonus)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), partial.settings.workDays)
        assertNull(partial.settings.storeLat)
        assertEquals(d.workStartFemale, partial.settings.workStartFemale)
        assertEquals(d.toleranceMale, partial.settings.toleranceMale)
        assertEquals(d.radiusMeters, partial.settings.radiusMeters)
        assertEquals(d.requireLocation, partial.settings.requireLocation)
        assertEquals(d.retentionMonths, partial.settings.retentionMonths)

        val rec = BackupCodec.decode(
            """{"format":"${BackupCodec.FORMAT}","version":1,"attendance":[{"employeeId":2,"date":"2026-11-05","status":"TELAT"}]}""",
        ).attendance.single()
        assertEquals(2L, rec.employeeId)
        assertEquals("", rec.timeIn)
        assertEquals("", rec.note)
        assertNull(rec.photoPath)
        assertFalse(rec.inLocation)
    }
}
