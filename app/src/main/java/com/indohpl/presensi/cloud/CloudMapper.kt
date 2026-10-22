package com.indohpl.presensi.cloud

import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import org.json.JSONArray
import org.json.JSONObject

/** Satu kiriman `device_upload`: antrean hapus diproses server lebih dulu, baru presensi disimpan. */
data class UploadBatch<D, R>(val deletes: List<D>, val rows: List<R>)

/**
 * Konversi data HP <-> JSON cloud (docs/CLOUD_API.md). Murni Kotlin + org.json (bawaan Android) supaya bisa diuji.
 * Foto TIDAK PERNAH dikirim: cloud hanya menyimpan data presensi (jam, status, koordinat, jarak).
 */
object CloudMapper {
    /** Maks presensi per panggilan `device_upload` (batas server). */
    const val MAX_UPLOAD = 500

    private val YMD = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /** Presensi -> objek "Attendance" untuk `device_upload` (tanpa foto, tanpa device/uploadedAt: diisi server). */
    fun attendanceJson(r: AttendanceRecord): JSONObject = JSONObject()
        .put("employeeId", r.employeeId)
        .put("date", r.date)
        .put("timeIn", r.timeIn)
        .put("timestamp", r.timestamp)
        .put("status", r.status)
        .put("lateMinutes", r.lateMinutes)
        .put("note", r.note)
        .put("latitude", r.latitude ?: JSONObject.NULL)
        .put("longitude", r.longitude ?: JSONObject.NULL)
        .put("accuracy", r.accuracy?.let(::floatToDouble) ?: JSONObject.NULL)
        .put("distanceMeters", r.distanceMeters ?: JSONObject.NULL)
        .put("inLocation", r.inLocation)
        .put("locationNote", r.locationNote)

    /** 12.5f -> 12.5 (lewat teks, supaya 12.3f tidak menjadi 12.300000190734863); NaN/tak hingga -> null. */
    fun floatToDouble(f: Float): Double? = if (f.isNaN() || f.isInfinite()) null else f.toString().toDouble()

    fun attendanceArray(rows: List<AttendanceRecord>): JSONArray = JSONArray().apply { rows.forEach { put(attendanceJson(it)) } }

    /** Antrean hapus "yyyy-MM-dd_<employeeId>" -> {employeeId, date}; null bila formatnya rusak (buang dari antrean). */
    fun deletionJson(docId: String): JSONObject? {
        val i = docId.indexOf('_')
        if (i <= 0) return null
        val date = docId.substring(0, i)
        val employeeId = docId.substring(i + 1).toLongOrNull() ?: return null
        if (!YMD.matches(date) || employeeId <= 0) return null
        return JSONObject().put("employeeId", employeeId).put("date", date)
    }

    fun employeeJson(e: Employee): JSONObject = JSONObject()
        .put("id", e.id)
        .put("name", e.name.trim().take(60))
        .put("gender", if (e.gender == "L") "L" else "P")
        .put("role", e.role.trim().take(40))
        .put("active", e.active)

    /** Daftar karyawan HP untuk `device_seed_employees` (nama kosong dilewati: ditolak server). */
    fun employeesJson(list: List<Employee>): JSONArray =
        JSONArray().apply { list.filter { it.id > 0 && it.name.isNotBlank() }.forEach { put(employeeJson(it)) } }

    /** {id, name, gender, role, active} -> Employee; null bila id/nama tidak sah. */
    fun employeeFromJson(o: JSONObject): Employee? {
        val id = (o.opt("id") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
        val name = (o.opt("name") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val gender = if (o.opt("gender") == "L") "L" else "P"
        val role = (o.opt("role") as? String)?.trim() ?: ""
        val active = o.opt("active") as? Boolean ?: true
        return Employee(id = id, name = name, gender = gender, active = active, role = role)
    }

    fun employeesFromJson(arr: JSONArray?): List<Employee> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> (arr.opt(i) as? JSONObject)?.let(::employeeFromJson) }
    }

    /**
     * Bagi antrean hapus + presensi ke kiriman maks [max]. Semua hapus terkirim paling lambat bersama presensi
     * PERTAMA (server menandai hapus dulu, baru menyimpan), jadi "hapus salah tekan lalu absen ulang di hari yang sama"
     * tidak pernah membuat presensi baru ikut terhapus.
     */
    fun <D, R> uploadBatches(deletes: List<D>, rows: List<R>, max: Int = MAX_UPLOAD): List<UploadBatch<D, R>> {
        require(max > 0)
        val delChunks = deletes.chunked(max)
        val rowChunks = rows.chunked(max)
        val out = ArrayList<UploadBatch<D, R>>()
        delChunks.forEachIndexed { i, d ->
            // hapus terakhir boleh menumpang presensi pertama
            val r = if (i == delChunks.lastIndex) rowChunks.firstOrNull().orEmpty() else emptyList()
            out += UploadBatch(d, r)
        }
        val rest = if (delChunks.isEmpty()) rowChunks else rowChunks.drop(1)
        rest.forEach { out += UploadBatch(emptyList(), it) }
        return out
    }
}
