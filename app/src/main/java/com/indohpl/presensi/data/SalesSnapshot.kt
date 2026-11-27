package com.indohpl.presensi.data

import com.indohpl.presensi.domain.AbsenceItem
import com.indohpl.presensi.domain.BonusShare
import com.indohpl.presensi.domain.SalesBonusPerson
import com.indohpl.presensi.domain.SalesBonusResult
import com.indohpl.presensi.domain.SalesTier
import java.time.LocalDate
import java.time.YearMonth
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hasil bonus penjualan yang "dikunci" saat owner menandai sudah dibayar. Disimpan sebagai JSON di
 * [SalesMonth.snapshot] supaya angka yang sudah dibayar tidak berubah lagi, walau presensi bulan itu nanti
 * dianulir, dihapus, atau terhapus otomatis oleh pembersihan data lama.
 */
object SalesSnapshot {
    fun encode(r: SalesBonusResult): String {
        val o = JSONObject()
        o.put("ym", r.ym.toString())
        o.put("netSales", r.netSales)
        o.put("tier", r.tier?.let { tierJson(it) } ?: JSONObject.NULL)
        o.put("nextTier", r.nextTier?.let { tierJson(it) } ?: JSONObject.NULL)
        o.put("shortfall", r.shortfall)
        o.put("participants", r.participants)
        o.put("pool", r.pool)
        val people = JSONArray()
        r.people.forEach { p ->
            val absences = JSONArray()
            p.absences.forEach { a ->
                absences.put(JSONObject().put("date", a.date.toString()).put("halfDays", a.halfDays).put("reason", a.reason))
            }
            people.put(
                JSONObject()
                    .put("id", p.employee.id)
                    .put("name", p.employee.name)
                    .put("gender", p.employee.gender)
                    .put("participating", p.participating)
                    .put("absentHalfDays", p.absentHalfDays)
                    .put("warnings", p.warnings)
                    .put("attendanceShare", p.attendanceShare.name)
                    .put("share", p.share.name)
                    .put("payout", p.payout)
                    .put("absences", absences),
            )
        }
        o.put("people", people)
        return o.toString()
    }

    /** null bila teks kosong atau rusak (pemanggil lalu memakai hitungan langsung). */
    fun decode(text: String): SalesBonusResult? = runCatching {
        if (text.isBlank()) return null
        val o = JSONObject(text)
        val arr = o.getJSONArray("people")
        val people = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val abs = p.optJSONArray("absences") ?: JSONArray()
            SalesBonusPerson(
                employee = Employee(p.getLong("id"), p.getString("name"), p.optString("gender", "L")),
                participating = p.getBoolean("participating"),
                absentHalfDays = p.getInt("absentHalfDays"),
                absences = (0 until abs.length()).map { j ->
                    val a = abs.getJSONObject(j)
                    AbsenceItem(LocalDate.parse(a.getString("date")), a.getInt("halfDays"), a.optString("reason", ""))
                },
                warnings = p.getInt("warnings"),
                attendanceShare = BonusShare.valueOf(p.getString("attendanceShare")),
                share = BonusShare.valueOf(p.getString("share")),
                payout = p.getLong("payout"),
            )
        }
        SalesBonusResult(
            ym = YearMonth.parse(o.getString("ym")),
            netSales = o.getLong("netSales"),
            tier = tier(o, "tier"),
            nextTier = tier(o, "nextTier"),
            shortfall = o.getLong("shortfall"),
            participants = o.getInt("participants"),
            pool = o.getLong("pool"),
            people = people,
        )
    }.getOrNull()

    private fun tierJson(t: SalesTier) = JSONObject().put("minSales", t.minSales).put("perPerson", t.perPerson)

    private fun tier(o: JSONObject, key: String): SalesTier? {
        if (!o.has(key) || o.isNull(key)) return null
        val t = o.getJSONObject(key)
        return SalesTier(t.getLong("minSales"), t.getLong("perPerson"))
    }
}
