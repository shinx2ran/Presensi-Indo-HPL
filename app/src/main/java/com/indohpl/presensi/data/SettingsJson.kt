package com.indohpl.presensi.data

import com.indohpl.presensi.domain.PhotoRetention
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pengaturan HP <-> JSON "Settings" di cloud (docs/CLOUD_API.md, `site_settings.data`).
 * Murni Kotlin + org.json (bawaan Android) supaya bisa diuji unit test.
 *
 * Batas nilai sama dengan validasi server (`private.validate_settings`): [toJson] selalu menghasilkan data
 * yang diterima server, [fromJson] mengabaikan kunci yang hilang / tidak sah (nilai HP sekarang dipakai).
 */
object SettingsJson {
    const val MAX_TOLERANCE = 240
    const val MAX_MONEY = 100_000_000L
    const val MIN_RADIUS = 10
    const val MAX_RADIUS = 100_000
    const val MAX_HOLIDAYS = 400
    const val MAX_HOLIDAY_NAME = 60

    private val HHMM = Regex("""^([01][0-9]|2[0-3]):[0-5][0-9]$""")
    private val YMD = Regex("""^\d{4}-\d{2}-\d{2}$""")

    fun toJson(s: AppSettings, holidays: List<Holiday>): JSONObject {
        val d = AppSettings()
        val days = s.workDays.map { it.value }.sorted().ifEmpty { (1..7).toList() }
        return JSONObject()
            .put("workStartFemale", s.workStartFemale.takeIf { HHMM.matches(it) } ?: d.workStartFemale)
            .put("toleranceFemale", s.toleranceFemale.coerceIn(0, MAX_TOLERANCE))
            .put("workStartMale", s.workStartMale.takeIf { HHMM.matches(it) } ?: d.workStartMale)
            .put("toleranceMale", s.toleranceMale.coerceIn(0, MAX_TOLERANCE))
            .put("monthlyBonus", s.monthlyBonus.coerceIn(0L, MAX_MONEY))
            .put("lateDeduction", s.lateDeduction.coerceIn(0L, MAX_MONEY))
            .put("deductAbsent", s.deductAbsent)
            .put("workDays", JSONArray().apply { days.forEach { put(it) } })
            .put("storeLat", if (s.hasStoreLocation) s.storeLat!! else JSONObject.NULL)
            .put("storeLon", if (s.hasStoreLocation) s.storeLon!! else JSONObject.NULL)
            .put("radiusMeters", s.radiusMeters.coerceIn(MIN_RADIUS, MAX_RADIUS))
            .put("requireLocation", s.requireLocation)
            .put("requireBiometric", s.requireBiometric)
            .put("photoRetentionDays", if (PhotoRetention.isValidDays(s.photoRetentionDays)) s.photoRetentionDays else PhotoRetention.DEFAULT_DAYS)
            .put(
                "holidays",
                JSONArray().apply {
                    holidays.filter { YMD.matches(it.date) }.sortedBy { it.date }.take(MAX_HOLIDAYS).forEach { h ->
                        put(JSONObject().put("date", h.date).put("name", h.name.take(MAX_HOLIDAY_NAME)))
                    }
                },
            )
    }

    /**
     * Terapkan JSON pengaturan cloud di atas pengaturan HP sekarang. Kunci yang hilang / tidak sah = nilai [current]
     * tetap dipakai; `storeLat`/`storeLon` bernilai null eksplisit = titik lokasi dihapus; `holidays` hilang = daftar
     * libur HP tetap.
     */
    fun fromJson(o: JSONObject, current: AppSettings, currentHolidays: List<Holiday>): Pair<AppSettings, List<Holiday>> {
        var lat = current.storeLat
        var lon = current.storeLon
        when (val loc = location(o)) {
            is Loc.Set -> { lat = loc.lat; lon = loc.lon }
            Loc.Clear -> { lat = null; lon = null }
            Loc.Keep -> Unit
        }
        val s = current.copy(
            workStartFemale = o.hhmm("workStartFemale") ?: current.workStartFemale,
            toleranceFemale = o.int("toleranceFemale", 0..MAX_TOLERANCE) ?: current.toleranceFemale,
            workStartMale = o.hhmm("workStartMale") ?: current.workStartMale,
            toleranceMale = o.int("toleranceMale", 0..MAX_TOLERANCE) ?: current.toleranceMale,
            monthlyBonus = o.long("monthlyBonus", 0L..MAX_MONEY) ?: current.monthlyBonus,
            lateDeduction = o.long("lateDeduction", 0L..MAX_MONEY) ?: current.lateDeduction,
            deductAbsent = o.bool("deductAbsent") ?: current.deductAbsent,
            workDays = workDays(o) ?: current.workDays,
            storeLat = lat,
            storeLon = lon,
            radiusMeters = o.int("radiusMeters", MIN_RADIUS..MAX_RADIUS) ?: current.radiusMeters,
            requireLocation = o.bool("requireLocation") ?: current.requireLocation,
            requireBiometric = o.bool("requireBiometric") ?: current.requireBiometric,
            photoRetentionDays = o.int("photoRetentionDays", 0..PhotoRetention.MAX_DAYS)
                ?.takeIf { PhotoRetention.isValidDays(it) } ?: current.photoRetentionDays,
        )
        return s to (holidays(o) ?: currentHolidays)
    }

    private sealed class Loc {
        data class Set(val lat: Double, val lon: Double) : Loc()
        data object Clear : Loc()
        data object Keep : Loc()
    }

    private fun location(o: JSONObject): Loc {
        if (!o.has("storeLat") && !o.has("storeLon")) return Loc.Keep
        val latNull = o.has("storeLat") && o.isNull("storeLat")
        val lonNull = o.has("storeLon") && o.isNull("storeLon")
        if (latNull || lonNull) return Loc.Clear
        val lat = o.double("storeLat")?.takeIf { it in -90.0..90.0 }
        val lon = o.double("storeLon")?.takeIf { it in -180.0..180.0 }
        return if (lat != null && lon != null) Loc.Set(lat, lon) else Loc.Keep
    }

    private fun workDays(o: JSONObject): Set<DayOfWeek>? {
        val arr = o.opt("workDays") as? JSONArray ?: return null
        if (arr.length() !in 1..7) return null
        val days = mutableSetOf<DayOfWeek>()
        for (i in 0 until arr.length()) {
            val n = arr.opt(i) as? Number ?: return null
            val v = n.toDouble()
            if (v != Math.floor(v) || v < 1 || v > 7) return null
            days += DayOfWeek.of(v.toInt())
        }
        return days
    }

    private fun holidays(o: JSONObject): List<Holiday>? {
        val arr = o.opt("holidays") as? JSONArray ?: return null
        val byDate = LinkedHashMap<String, Holiday>()
        for (i in 0 until arr.length()) {
            val h = arr.opt(i) as? JSONObject ?: continue
            val date = h.str("date")?.trim()?.takeIf { YMD.matches(it) } ?: continue
            if (runCatching { LocalDate.parse(date) }.isFailure) continue
            val name = h.str("name")?.trim()?.take(MAX_HOLIDAY_NAME)?.ifEmpty { null } ?: "Libur"
            byDate[date] = Holiday(date, name)
        }
        return byDate.values.sortedBy { it.date }
    }

    // ---- pembaca ketat (org.json di Android dan di JVM berbeda perilaku untuk null & angka desimal) ----

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else opt(key) as? String

    private fun JSONObject.hhmm(key: String): String? = str(key)?.takeIf { HHMM.matches(it) }

    private fun JSONObject.bool(key: String): Boolean? = if (isNull(key)) null else opt(key) as? Boolean

    private fun JSONObject.double(key: String): Double? {
        if (isNull(key)) return null
        val v = (opt(key) as? Number)?.toDouble() ?: return null
        return v.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    private fun JSONObject.long(key: String, range: LongRange): Long? =
        double(key)?.roundToLong()?.takeIf { it in range }

    private fun JSONObject.int(key: String, range: IntRange): Int? =
        double(key)?.roundToLong()?.takeIf { it in range.first.toLong()..range.last.toLong() }?.toInt()
}
