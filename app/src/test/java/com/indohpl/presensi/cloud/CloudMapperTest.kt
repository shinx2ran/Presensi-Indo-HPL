package com.indohpl.presensi.cloud

import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Status
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudMapperTest {
    private val checkIn = AttendanceRecord(
        id = 7, employeeId = 2, date = "2026-10-22", timestamp = 1_792_540_090_000L, timeIn = "07:28:10",
        status = Status.TEPAT, lateMinutes = 0, photoPath = "/data/x/photos/2026-10/Bik Nia_20261022_072810.jpg",
        note = "", latitude = -6.2001, longitude = 106.8001, accuracy = 12.5f, distanceMeters = 15,
        inLocation = true, locationNote = "di lokasi kost", synced = false,
    )

    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    @Test
    fun attendanceHasExactlyTheContractKeysAndNoPhoto() {
        val o = CloudMapper.attendanceJson(checkIn)
        assertEquals(
            setOf(
                "employeeId", "date", "timeIn", "timestamp", "status", "lateMinutes", "note",
                "latitude", "longitude", "accuracy", "distanceMeters", "inLocation", "locationNote",
            ),
            keys(o),
        )
        assertFalse(o.toString().contains("photo"))
        assertFalse(o.has("device"))
        assertFalse(o.has("uploadedAt"))
        // Nilai lewat teks JSON (seperti yang diterima server).
        val back = JSONObject(o.toString())
        assertEquals(2L, back.getLong("employeeId"))
        assertEquals("2026-10-22", back.getString("date"))
        assertEquals("07:28:10", back.getString("timeIn"))
        assertEquals(1_792_540_090_000L, back.getLong("timestamp"))
        assertEquals("TEPAT", back.getString("status"))
        assertEquals(0, back.getInt("lateMinutes"))
        assertEquals("", back.getString("note"))
        assertEquals(-6.2001, back.getDouble("latitude"), 0.0)
        assertEquals(106.8001, back.getDouble("longitude"), 0.0)
        assertEquals(12.5, back.getDouble("accuracy"), 0.0)
        assertEquals(15, back.getInt("distanceMeters"))
        assertTrue(back.getBoolean("inLocation"))
        assertEquals("di lokasi kost", back.getString("locationNote"))
    }

    @Test
    fun accuracyIsAPlainDouble() {
        val o = CloudMapper.attendanceJson(checkIn.copy(accuracy = 12.3f))
        assertEquals(12.3, o.get("accuracy") as Double, 0.0)
        assertTrue(o.toString().contains("\"accuracy\":12.3"))
        assertNull(CloudMapper.floatToDouble(Float.NaN))
    }

    @Test
    fun excusedRecordSendsJsonNulls() {
        val izin = AttendanceRecord(
            employeeId = 1, date = "2026-10-21", timestamp = 1_792_480_000_000L, timeIn = "",
            status = Status.IZIN, lateMinutes = 0, photoPath = null, note = "ke dokter",
        )
        val o = CloudMapper.attendanceJson(izin)
        for (k in listOf("latitude", "longitude", "accuracy", "distanceMeters")) {
            assertTrue("$k ada", o.has(k))
            assertTrue("$k null", o.isNull(k))
        }
        assertEquals("", o.getString("timeIn"))
        assertEquals("IZIN", o.getString("status"))
        assertFalse(o.getBoolean("inLocation"))
        assertTrue(JSONObject(o.toString()).isNull("latitude"))
        assertTrue(o.toString().contains("\"latitude\":null"))
    }

    @Test
    fun attendanceArrayKeepsOrder() {
        val arr = CloudMapper.attendanceArray(listOf(checkIn, checkIn.copy(employeeId = 1)))
        assertEquals(2, arr.length())
        assertEquals(2L, arr.getJSONObject(0).getLong("employeeId"))
        assertEquals(1L, arr.getJSONObject(1).getLong("employeeId"))
    }

    @Test
    fun deletionQueueIdToContractObject() {
        assertEquals("2026-10-22_2", checkIn.cloudId)
        val d = CloudMapper.deletionJson(checkIn.cloudId)!!
        assertEquals(setOf("employeeId", "date"), keys(d))
        assertEquals(2L, d.getLong("employeeId"))
        assertEquals("2026-10-22", d.getString("date"))
        assertEquals(15L, CloudMapper.deletionJson("2026-01-05_15")!!.getLong("employeeId"))
        assertNull(CloudMapper.deletionJson(""))
        assertNull(CloudMapper.deletionJson("2026-10-22"))
        assertNull(CloudMapper.deletionJson("2026-10-22_"))
        assertNull(CloudMapper.deletionJson("2026-10-22_abc"))
        assertNull(CloudMapper.deletionJson("22-10-2026_1"))
        assertNull(CloudMapper.deletionJson("_1"))
        assertNull(CloudMapper.deletionJson("2026-10-22_0"))
    }

    @Test
    fun employeesRoundTrip() {
        val list = listOf(
            Employee(1, "Inul", "P", role = "Laundry"),
            Employee(2, "Bik Nia", "P", active = false, role = "Kos"),
            Employee(3, "Evan", "L"),
        )
        val arr = CloudMapper.employeesJson(list)
        assertEquals(setOf("id", "name", "gender", "role", "active"), keys(arr.getJSONObject(0)))
        assertEquals(list, CloudMapper.employeesFromJson(JSONArray(arr.toString())))
    }

    @Test
    fun employeesFromServerAreSanitized() {
        val arr = JSONArray(
            """[{"id":1,"name":" Inul ","gender":"P","role":null,"active":true},
                {"id":2,"name":"Roni","gender":"X"},
                {"id":0,"name":"Tanpa id","gender":"L","role":"","active":true},
                {"id":4,"name":"   ","gender":"L","role":"","active":true},
                "bukan objek",
                {"id":5.0,"name":"Madi","gender":"L","role":"Gudang","active":false}]""",
        )
        val list = CloudMapper.employeesFromJson(arr)
        assertEquals(
            listOf(
                Employee(1, "Inul", "P", active = true, role = ""),
                Employee(2, "Roni", "P", active = true, role = ""),
                Employee(5, "Madi", "L", active = false, role = "Gudang"),
            ),
            list,
        )
        assertEquals(emptyList<Employee>(), CloudMapper.employeesFromJson(null))
    }

    @Test
    fun seedListSkipsInvalidAndTrimsToServerLimits() {
        val long = "N".repeat(70)
        val arr = CloudMapper.employeesJson(
            listOf(Employee(1, long, "P", role = "R".repeat(50)), Employee(2, "  ", "L"), Employee(0, "Nol", "L")),
        )
        assertEquals(1, arr.length())
        assertEquals(60, arr.getJSONObject(0).getString("name").length)
        assertEquals(40, arr.getJSONObject(0).getString("role").length)
    }

    @Test
    fun uploadBatchesRespectServerLimitAndDeleteOrder() {
        assertEquals(emptyList<UploadBatch<String, Int>>(), CloudMapper.uploadBatches(emptyList<String>(), emptyList<Int>()))

        // Hapus terkirim bersama presensi pertama (server menandai hapus dulu, baru menyimpan).
        assertEquals(
            listOf(UploadBatch(listOf("d1", "d2"), listOf(1, 2)), UploadBatch(emptyList(), listOf(3))),
            CloudMapper.uploadBatches(listOf("d1", "d2"), listOf(1, 2, 3), max = 2),
        )
        // Antrean hapus lebih panjang dari satu kiriman: semua hapus terkirim sebelum presensi mana pun.
        assertEquals(
            listOf(UploadBatch(listOf("d1", "d2"), emptyList()), UploadBatch(listOf("d3"), listOf(1))),
            CloudMapper.uploadBatches(listOf("d1", "d2", "d3"), listOf(1), max = 2),
        )
        assertEquals(listOf(UploadBatch(listOf("d"), emptyList<Int>())), CloudMapper.uploadBatches(listOf("d"), emptyList<Int>()))

        val rows = (1..1001).toList()
        val batches = CloudMapper.uploadBatches(emptyList<String>(), rows)
        assertEquals(listOf(500, 500, 1), batches.map { it.rows.size })
        assertTrue(batches.all { it.deletes.isEmpty() && it.rows.size <= CloudMapper.MAX_UPLOAD })
        assertEquals(rows, batches.flatMap { it.rows })
    }
}

class SupabaseRpcTest {
    @Test
    fun parsesPostgrestError() {
        val e = SupabaseRpc.parseError(
            403,
            """{"code":"28000","details":null,"hint":null,"message":"HP ini belum/tidak lagi terhubung. Hubungkan ulang dengan kode dari dashboard owner."}""",
        )
        assertEquals(403, e.httpStatus)
        assertEquals("28000", e.code)
        assertEquals("HP ini belum/tidak lagi terhubung. Hubungkan ulang dengan kode dari dashboard owner.", e.message)
        assertTrue(e.isAuthError)
        assertTrue(e.isKeyRejected)
        assertFalse(e.isTemporary)
    }

    @Test
    fun invalidDataIsNotAnAuthError() {
        val e = SupabaseRpc.parseError(400, """{"code":"22023","message":"Kode harus 8 huruf/angka, contoh K7P2-9XQM."}""")
        assertEquals("22023", e.code)
        assertFalse(e.isAuthError)
        assertFalse(e.isKeyRejected)
        assertFalse(e.isTemporary)
    }

    @Test
    fun gatewayErrorsWithoutPostgrestBody() {
        val e = SupabaseRpc.parseError(502, "<html>Bad gateway</html>")
        assertEquals("", e.code)
        assertEquals("Server menjawab HTTP 502", e.message)
        assertTrue(e.isTemporary)
        assertFalse(e.isAuthError)

        val k = SupabaseRpc.parseError(401, """{"message":"Invalid API key"}""")
        assertTrue(k.isAuthError)      // HTTP 401 = ditolak
        assertFalse(k.isKeyRejected)   // tapi bukan kunci HP yang diputus: HP tidak dilepas
        assertEquals("Invalid API key", k.message)

        assertEquals("Server menjawab HTTP 500", SupabaseRpc.parseError(500, null).message)
        assertTrue(SupabaseRpc.parseError(429, "").isTemporary)
    }
}
