package com.indohpl.presensi.data

import java.time.DayOfWeek
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonTest {
    private val custom = AppSettings(
        workStartFemale = "07:30", toleranceFemale = 10,
        workStartMale = "08:00", toleranceMale = 5,
        monthlyBonus = 300_000, lateDeduction = 10_000, deductAbsent = false,
        workDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        storeLat = -6.2, storeLon = 106.8, radiusMeters = 150,
        requireLocation = false, requireBiometric = false, photoRetentionDays = 30,
    )
    private val holidays = listOf(Holiday("2026-12-25", "Natal"), Holiday("2026-08-17", "HUT RI"))

    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    @Test
    fun toJsonUsesContractKeys() {
        val o = SettingsJson.toJson(custom, holidays)
        assertEquals(
            setOf(
                "workStartFemale", "toleranceFemale", "workStartMale", "toleranceMale",
                "monthlyBonus", "lateDeduction", "deductAbsent", "workDays",
                "storeLat", "storeLon", "radiusMeters", "requireLocation", "requireBiometric",
                "photoRetentionDays", "holidays",
            ),
            keys(o),
        )
        val t = JSONObject(o.toString())
        assertEquals("07:30", t.getString("workStartFemale"))
        assertEquals(10, t.getInt("toleranceFemale"))
        assertEquals("08:00", t.getString("workStartMale"))
        assertEquals(5, t.getInt("toleranceMale"))
        assertEquals(300_000L, t.getLong("monthlyBonus"))
        assertEquals(10_000L, t.getLong("lateDeduction"))
        assertFalse(t.getBoolean("deductAbsent"))
        assertEquals("[1,3,6]", t.getJSONArray("workDays").toString()) // DayOfWeek.value, urut
        assertEquals(-6.2, t.getDouble("storeLat"), 0.0)
        assertEquals(106.8, t.getDouble("storeLon"), 0.0)
        assertEquals(150, t.getInt("radiusMeters"))
        assertFalse(t.getBoolean("requireLocation"))
        assertFalse(t.getBoolean("requireBiometric"))
        assertEquals(30, t.getInt("photoRetentionDays"))
        val h = t.getJSONArray("holidays")
        assertEquals(2, h.length())
        assertEquals("2026-08-17", h.getJSONObject(0).getString("date")) // urut tanggal
        assertEquals("HUT RI", h.getJSONObject(0).getString("name"))
        assertEquals(setOf("date", "name"), keys(h.getJSONObject(1)))
    }

    @Test
    fun noLocationIsSentAsExplicitNull() {
        val o = SettingsJson.toJson(custom.copy(storeLat = null, storeLon = null), emptyList())
        assertTrue(o.has("storeLat") && o.isNull("storeLat"))
        assertTrue(o.has("storeLon") && o.isNull("storeLon"))
        assertTrue(o.toString().contains("\"storeLat\":null"))
        assertEquals(0, o.getJSONArray("holidays").length())
    }

    @Test
    fun toJsonAlwaysPassesServerValidation() {
        val bad = custom.copy(
            workStartFemale = "7:3", toleranceFemale = 500, toleranceMale = -1, monthlyBonus = 999_999_999,
            radiusMeters = 3, photoRetentionDays = 3, workDays = emptySet(),
        )
        val o = SettingsJson.toJson(bad, listOf(Holiday("2026-12-25", "N".repeat(80)), Holiday("rusak", "x")))
        assertEquals(AppSettings().workStartFemale, o.getString("workStartFemale"))
        assertEquals(SettingsJson.MAX_TOLERANCE, o.getInt("toleranceFemale"))
        assertEquals(0, o.getInt("toleranceMale"))
        assertEquals(SettingsJson.MAX_MONEY, o.getLong("monthlyBonus"))
        assertEquals(SettingsJson.MIN_RADIUS, o.getInt("radiusMeters"))
        assertEquals(60, o.getInt("photoRetentionDays"))
        assertEquals(7, o.getJSONArray("workDays").length())
        assertEquals(1, o.getJSONArray("holidays").length())
        assertEquals(60, o.getJSONArray("holidays").getJSONObject(0).getString("name").length)
    }

    @Test
    fun roundTrip() {
        val (s, h) = SettingsJson.fromJson(JSONObject(SettingsJson.toJson(custom, holidays).toString()), AppSettings(), emptyList())
        assertEquals(custom, s)
        assertEquals(holidays.sortedBy { it.date }, h)
    }

    @Test
    fun missingKeysKeepCurrentValues() {
        val (s, h) = SettingsJson.fromJson(JSONObject(), custom, holidays)
        assertEquals(custom, s)
        assertEquals(holidays, h) // holidays tidak ada = daftar HP tetap

        val (s2, h2) = SettingsJson.fromJson(JSONObject("""{"toleranceMale": 15}"""), custom, holidays)
        assertEquals(custom.copy(toleranceMale = 15), s2)
        assertEquals(holidays, h2)
    }

    @Test
    fun invalidValuesKeepCurrentValues() {
        val o = JSONObject(
            """{"workStartFemale":"7:30","toleranceFemale":500,"workStartMale":800,"toleranceMale":"5",
                "monthlyBonus":"abc","lateDeduction":-1,"deductAbsent":"yes","workDays":[0,8],
                "storeLat":100,"storeLon":106.8,"radiusMeters":5,"requireLocation":1,"requireBiometric":null,
                "photoRetentionDays":3,"holidays":"bukan daftar"}""",
        )
        val (s, h) = SettingsJson.fromJson(o, custom, holidays)
        assertEquals(custom, s)
        assertEquals(holidays, h)
        assertEquals(custom.workDays, SettingsJson.fromJson(JSONObject("""{"workDays":[]}"""), custom, holidays).first.workDays)
        assertEquals(custom.workDays, SettingsJson.fromJson(JSONObject("""{"workDays":[1,2.5]}"""), custom, holidays).first.workDays)
        assertEquals(custom.photoRetentionDays, SettingsJson.fromJson(JSONObject("""{"photoRetentionDays":4000}"""), custom, holidays).first.photoRetentionDays)
    }

    @Test
    fun explicitNullClearsLocation() {
        val (s, _) = SettingsJson.fromJson(JSONObject("""{"storeLat":null,"storeLon":null}"""), custom, holidays)
        assertNull(s.storeLat)
        assertNull(s.storeLon)
        assertFalse(s.hasStoreLocation)
        // Satu saja null juga menghapus titik (titik butuh keduanya).
        assertFalse(SettingsJson.fromJson(JSONObject("""{"storeLat":null}"""), custom, holidays).first.hasStoreLocation)
        // Titik baru dari dashboard.
        val (moved, _) = SettingsJson.fromJson(JSONObject("""{"storeLat":-6.175392,"storeLon":106.827153}"""), custom, holidays)
        assertEquals(-6.175392, moved.storeLat!!, 0.0)
        assertEquals(106.827153, moved.storeLon!!, 0.0)
        // Hanya satu koordinat angka: titik lama dipakai.
        val (half, _) = SettingsJson.fromJson(JSONObject("""{"storeLat":-7.0}"""), custom, holidays)
        assertEquals(custom.storeLat, half.storeLat)
        assertEquals(custom.storeLon, half.storeLon)
    }

    @Test
    fun workDaysUseDayOfWeekValues() {
        val (s, _) = SettingsJson.fromJson(JSONObject("""{"workDays":[1,3,5,7]}"""), custom, holidays)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY), s.workDays)
        val (all, _) = SettingsJson.fromJson(JSONObject("""{"workDays":[1,2,3,4,5,6,7]}"""), custom, holidays)
        assertEquals(DayOfWeek.values().toSet(), all.workDays)
    }

    @Test
    fun decimalsFromServerAreAccepted() {
        // Server menyimpan jsonb; angka bisa datang sebagai 10.0 / 250000.0.
        val (s, _) = SettingsJson.fromJson(
            JSONObject("""{"toleranceFemale":10.0,"monthlyBonus":250000.0,"radiusMeters":120.4,"photoRetentionDays":0}"""),
            custom, holidays,
        )
        assertEquals(10, s.toleranceFemale)
        assertEquals(250_000L, s.monthlyBonus)
        assertEquals(120, s.radiusMeters)
        assertEquals(0, s.photoRetentionDays) // 0 = simpan selamanya
    }

    @Test
    fun holidaysFromCloudReplaceTheList() {
        val arr = JSONArray(
            """[{"date":"2026-12-25","name":"Natal"},{"date":"2026-01-01","name":""},
                {"date":"2026-13-40","name":"rusak"},{"date":"kemarin","name":"x"},{"name":"tanpa tanggal"},
                {"date":"2026-12-25","name":"Natal (pengganti)"}]""",
        )
        val (_, h) = SettingsJson.fromJson(JSONObject().put("holidays", arr), custom, holidays)
        assertEquals(listOf(Holiday("2026-01-01", "Libur"), Holiday("2026-12-25", "Natal (pengganti)")), h)
        val (_, empty) = SettingsJson.fromJson(JSONObject("""{"holidays":[]}"""), custom, holidays)
        assertEquals(emptyList<Holiday>(), empty)
    }

    @Test
    fun siteDefaultSettingsFromServerMatchAppDefaults() {
        // site.defaultSettings di migrasi Supabase (sama untuk kedua tempat kecuali jam masuk).
        val d = AppSettings()
        val server = JSONObject(
            """{"workStartFemale":"${d.workStartFemale}","toleranceFemale":${d.toleranceFemale},
                "workStartMale":"${d.workStartMale}","toleranceMale":${d.toleranceMale},"monthlyBonus":250000,
                "lateDeduction":0,"deductAbsent":true,"workDays":[1,2,3,4,5,6,7],"storeLat":null,"storeLon":null,
                "radiusMeters":100,"requireLocation":true,"requireBiometric":true,"photoRetentionDays":60,"holidays":[]}""",
        )
        val (s, h) = SettingsJson.fromJson(server, custom, holidays)
        assertEquals(d, s)
        assertEquals(emptyList<Holiday>(), h)
    }
}
