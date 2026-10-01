package com.indohpl.presensi.cloud

import com.indohpl.presensi.data.AttendanceRecord

/** Satu dokumen cloud = satu presensi + foto (base64) opsional. */
data class CloudRecord(val record: AttendanceRecord, val photoBase64: String?, val employeeName: String)

/**
 * Konversi presensi <-> Map untuk Firestore. Murni Kotlin (tanpa SDK Firebase) supaya bisa diuji.
 * Nama field dibuat jelas agar mudah dibaca di konsol Firebase.
 */
object CloudMapper {
    fun toMap(r: AttendanceRecord, employeeName: String, photoBase64: String?, device: String): Map<String, Any?> = mapOf(
        "employeeId" to r.employeeId,
        "employeeName" to employeeName,
        "date" to r.date,
        "timestamp" to r.timestamp,
        "timeIn" to r.timeIn,
        "status" to r.status,
        "lateMinutes" to r.lateMinutes,
        "note" to r.note,
        "latitude" to r.latitude,
        "longitude" to r.longitude,
        "accuracy" to r.accuracy?.toDouble(),
        "distanceMeters" to r.distanceMeters,
        "inLocation" to r.inLocation,
        "locationNote" to r.locationNote,
        "photoBase64" to photoBase64,
        "device" to device,
    )

    fun fromMap(m: Map<String, Any?>): CloudRecord? {
        val employeeId = (m["employeeId"] as? Number)?.toLong() ?: return null
        val date = m["date"] as? String ?: return null
        val record = AttendanceRecord(
            id = 0,
            employeeId = employeeId,
            date = date,
            timestamp = (m["timestamp"] as? Number)?.toLong() ?: 0L,
            timeIn = m["timeIn"] as? String ?: "",
            status = m["status"] as? String ?: "",
            lateMinutes = (m["lateMinutes"] as? Number)?.toInt() ?: 0,
            photoPath = null,
            note = m["note"] as? String ?: "",
            latitude = (m["latitude"] as? Number)?.toDouble(),
            longitude = (m["longitude"] as? Number)?.toDouble(),
            accuracy = (m["accuracy"] as? Number)?.toFloat(),
            distanceMeters = (m["distanceMeters"] as? Number)?.toInt(),
            inLocation = m["inLocation"] as? Boolean ?: false,
            locationNote = m["locationNote"] as? String ?: "",
            synced = true,
        )
        return CloudRecord(record, m["photoBase64"] as? String, m["employeeName"] as? String ?: employeeId.toString())
    }
}
