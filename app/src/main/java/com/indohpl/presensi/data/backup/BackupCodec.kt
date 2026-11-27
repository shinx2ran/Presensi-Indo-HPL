package com.indohpl.presensi.data.backup

import com.indohpl.presensi.data.AppSettings
import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.BonusEvent
import com.indohpl.presensi.data.Holiday
import com.indohpl.presensi.data.SalesMonth
import com.indohpl.presensi.domain.SalesBonus
import com.indohpl.presensi.domain.SalesBonusConfig
import java.time.DayOfWeek
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Isi satu file cadangan. Foto TIDAK ikut (hanya nama file di [AttendanceRecord.photoPath]). */
data class BackupData(
    val createdAt: String,
    val appVersion: String,
    val employees: List<Employee>,
    val attendance: List<AttendanceRecord>,
    val holidays: List<Holiday>,
    val settings: AppSettings,
    // Bonus penjualan (opsional: cadangan versi lama tidak punya).
    val salesMonths: List<SalesMonth> = emptyList(),
    val bonusEvents: List<BonusEvent> = emptyList(),
    val salesConfig: SalesBonusConfig? = null,
)

/** Mengubah [BackupData] dari/ke teks JSON. Murni Kotlin + org.json supaya bisa diuji. */
object BackupCodec {
    const val FORMAT = "presensi-indohpl-backup"
    const val VERSION = 1

    private const val NOT_A_BACKUP = "File ini bukan cadangan Presensi Indo HPL"
    private const val TOO_NEW = "Versi cadangan terlalu baru, perbarui aplikasi dulu"

    fun encode(d: BackupData): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("createdAt", d.createdAt)
        root.put("appVersion", d.appVersion)

        val employees = JSONArray()
        d.employees.forEach { e ->
            employees.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("gender", e.gender)
                    .put("active", e.active),
            )
        }
        root.put("employees", employees)

        val attendance = JSONArray()
        d.attendance.forEach { r ->
            val o = JSONObject()
            o.put("employeeId", r.employeeId)
            o.put("date", r.date)
            o.put("timestamp", r.timestamp)
            o.put("timeIn", r.timeIn)
            o.put("status", r.status)
            o.put("lateMinutes", r.lateMinutes)
            o.put("photoPath", r.photoPath?.substringAfterLast('/') ?: JSONObject.NULL)
            o.put("note", r.note)
            o.put("latitude", numberOrNull(r.latitude))
            o.put("longitude", numberOrNull(r.longitude))
            o.put("accuracy", numberOrNull(r.accuracy?.toDouble()))
            o.put("distanceMeters", r.distanceMeters ?: JSONObject.NULL)
            o.put("inLocation", r.inLocation)
            o.put("locationNote", r.locationNote)
            o.put("annulledLateMinutes", r.annulledLateMinutes ?: JSONObject.NULL)
            o.put("annulReason", r.annulReason)
            attendance.put(o)
        }
        root.put("attendance", attendance)

        val holidays = JSONArray()
        d.holidays.forEach { h -> holidays.put(JSONObject().put("date", h.date).put("name", h.name)) }
        root.put("holidays", holidays)

        val s = d.settings
        val days = JSONArray()
        s.workDays.map { it.value }.sorted().forEach { days.put(it) }
        root.put(
            "settings",
            JSONObject()
                .put("workStartFemale", s.workStartFemale)
                .put("toleranceFemale", s.toleranceFemale)
                .put("workStartMale", s.workStartMale)
                .put("toleranceMale", s.toleranceMale)
                .put("monthlyBonus", s.monthlyBonus)
                .put("perDayAmount", s.perDayAmount)
                .put("workDays", days)
                .put("storeLat", numberOrNull(s.storeLat))
                .put("storeLon", numberOrNull(s.storeLon))
                .put("radiusMeters", s.radiusMeters)
                .put("requireLocation", s.requireLocation)
                .put("retentionMonths", s.retentionMonths),
        )

        val sales = JSONArray()
        d.salesMonths.forEach { m ->
            sales.put(
                JSONObject()
                    .put("ym", m.ym)
                    .put("grossSales", m.grossSales)
                    .put("excludedSales", m.excludedSales)
                    .put("asOfDate", m.asOfDate)
                    .put("note", m.note)
                    .put("excludedEmployeeIds", m.excludedEmployeeIds)
                    .put("paidAt", m.paidAt ?: JSONObject.NULL)
                    .put("snapshot", m.snapshot)
                    .put("updatedAt", m.updatedAt),
            )
        }
        root.put("salesMonths", sales)
        val events = JSONArray()
        d.bonusEvents.forEach { e ->
            events.put(
                JSONObject()
                    .put("employeeId", e.employeeId)
                    .put("date", e.date)
                    .put("type", e.type)
                    .put("note", e.note)
                    .put("byWhom", e.byWhom)
                    .put("createdAt", e.createdAt),
            )
        }
        root.put("bonusEvents", events)
        d.salesConfig?.let { c ->
            root.put("salesBonus", JSONObject().put("tiers", SalesBonus.encodeTiers(c.tiers)).put("startMonth", c.startMonth.toString()))
        }
        return root.toString()
    }

    /** @throws IllegalArgumentException jika bukan file cadangan, rusak, atau versinya lebih baru dari aplikasi. */
    fun decode(text: String): BackupData {
        try {
            val root = JSONObject(text.trim().removePrefix("﻿"))
            if (root.optString("format", "") != FORMAT) throw IllegalArgumentException(NOT_A_BACKUP)
            if (root.optInt("version", 1) > VERSION) throw IllegalArgumentException(TOO_NEW)

            val employees = mutableListOf<Employee>()
            root.optJSONArray("employees")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    employees += Employee(
                        id = o.getLong("id"),
                        name = o.getString("name"),
                        gender = str(o, "gender", "L"),
                        active = bool(o, "active", true),
                    )
                }
            }

            val attendance = mutableListOf<AttendanceRecord>()
            root.optJSONArray("attendance")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    attendance += AttendanceRecord(
                        id = 0,
                        employeeId = o.getLong("employeeId"),
                        date = o.getString("date"),
                        timestamp = long(o, "timestamp", 0L),
                        timeIn = str(o, "timeIn", ""),
                        status = o.getString("status"),
                        lateMinutes = int(o, "lateMinutes", 0),
                        photoPath = if (o.isNull("photoPath")) null else o.getString("photoPath").substringAfterLast('/'),
                        note = str(o, "note", ""),
                        latitude = if (o.isNull("latitude")) null else o.getDouble("latitude"),
                        longitude = if (o.isNull("longitude")) null else o.getDouble("longitude"),
                        accuracy = if (o.isNull("accuracy")) null else o.getDouble("accuracy").toFloat(),
                        distanceMeters = if (o.isNull("distanceMeters")) null else o.getInt("distanceMeters"),
                        inLocation = bool(o, "inLocation", false),
                        locationNote = str(o, "locationNote", ""),
                        // Opsional: cadangan versi lama belum punya kolom anulir.
                        annulledLateMinutes = if (o.isNull("annulledLateMinutes")) null else o.getInt("annulledLateMinutes"),
                        annulReason = str(o, "annulReason", ""),
                    )
                }
            }

            val holidays = mutableListOf<Holiday>()
            root.optJSONArray("holidays")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    holidays += Holiday(date = o.getString("date"), name = str(o, "name", ""))
                }
            }

            val salesMonths = mutableListOf<SalesMonth>()
            root.optJSONArray("salesMonths")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    salesMonths += SalesMonth(
                        ym = o.getString("ym"),
                        grossSales = long(o, "grossSales", 0L),
                        excludedSales = long(o, "excludedSales", 0L),
                        asOfDate = str(o, "asOfDate", ""),
                        note = str(o, "note", ""),
                        excludedEmployeeIds = str(o, "excludedEmployeeIds", ""),
                        paidAt = if (o.isNull("paidAt")) null else o.getLong("paidAt"),
                        snapshot = str(o, "snapshot", ""),
                        updatedAt = long(o, "updatedAt", 0L),
                    )
                }
            }
            val bonusEvents = mutableListOf<BonusEvent>()
            root.optJSONArray("bonusEvents")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    bonusEvents += BonusEvent(
                        id = 0,
                        employeeId = o.getLong("employeeId"),
                        date = o.getString("date"),
                        type = o.getString("type"),
                        note = str(o, "note", ""),
                        byWhom = str(o, "byWhom", ""),
                        createdAt = long(o, "createdAt", 0L),
                    )
                }
            }
            val salesConfig = root.optJSONObject("salesBonus")?.let { o ->
                SalesBonusConfig(
                    tiers = SalesBonus.decodeTiers(if (o.isNull("tiers")) null else o.getString("tiers")),
                    startMonth = runCatching { java.time.YearMonth.parse(str(o, "startMonth", "")) }.getOrNull() ?: SalesBonus.DEFAULT_START,
                )
            }

            return BackupData(
                createdAt = str(root, "createdAt", ""),
                appVersion = str(root, "appVersion", ""),
                employees = employees,
                attendance = attendance,
                holidays = holidays,
                settings = decodeSettings(root.optJSONObject("settings")),
                salesMonths = salesMonths,
                bonusEvents = bonusEvents,
                salesConfig = salesConfig,
            )
        } catch (e: JSONException) {
            throw IllegalArgumentException(NOT_A_BACKUP)
        }
    }

    private fun decodeSettings(o: JSONObject?): AppSettings {
        val d = AppSettings()
        if (o == null) return d
        val days = o.optJSONArray("workDays")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optInt(i, 0).takeIf { it in 1..7 }?.let { DayOfWeek.of(it) } }.toSet()
        }
        return AppSettings(
            workStartFemale = str(o, "workStartFemale", d.workStartFemale),
            toleranceFemale = int(o, "toleranceFemale", d.toleranceFemale),
            workStartMale = str(o, "workStartMale", d.workStartMale),
            toleranceMale = int(o, "toleranceMale", d.toleranceMale),
            monthlyBonus = long(o, "monthlyBonus", d.monthlyBonus),
            perDayAmount = long(o, "perDayAmount", d.perDayAmount),
            workDays = days ?: d.workDays,
            storeLat = if (o.isNull("storeLat")) null else o.getDouble("storeLat"),
            storeLon = if (o.isNull("storeLon")) null else o.getDouble("storeLon"),
            radiusMeters = int(o, "radiusMeters", d.radiusMeters),
            requireLocation = bool(o, "requireLocation", d.requireLocation),
            retentionMonths = int(o, "retentionMonths", d.retentionMonths),
        )
    }

    // JSON tidak punya NaN/Infinity; org.json melempar error untuk itu, jadi simpan sebagai null.
    private fun numberOrNull(v: Double?): Any = if (v == null || v.isNaN() || v.isInfinite()) JSONObject.NULL else v

    // org.json bisa mengubah null menjadi teks "null" lewat optString; cek isNull dulu.
    private fun str(o: JSONObject, k: String, def: String): String = if (o.isNull(k)) def else o.getString(k)
    private fun int(o: JSONObject, k: String, def: Int): Int = if (o.isNull(k)) def else o.getInt(k)
    private fun long(o: JSONObject, k: String, def: Long): Long = if (o.isNull(k)) def else o.getLong(k)
    private fun bool(o: JSONObject, k: String, def: Boolean): Boolean = if (o.isNull(k)) def else o.getBoolean(k)
}
