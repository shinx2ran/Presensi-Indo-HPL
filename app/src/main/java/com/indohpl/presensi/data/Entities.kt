package com.indohpl.presensi.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Seorang karyawan. Gender menentukan aturan jam masuk yang dipakai (lihat AppSettings) dan warna avatar.
 * [role] = bagian kerja (mis. "Laundry", "Kos"); kosong bila tidak perlu ditampilkan.
 */
@Entity(tableName = "employees")
data class Employee(
    @PrimaryKey val id: Long,
    val name: String,
    val gender: String,          // "P" = perempuan, "L" = laki-laki
    val active: Boolean = true,
    @ColumnInfo(defaultValue = "") val role: String = "",
) {
    /** "Inul · Laundry" bila ada bagian kerja, selain itu hanya nama. */
    val label: String get() = if (role.isBlank()) name else "$name · $role"
}

/** Status satu hari kerja untuk satu karyawan. */
object Status {
    const val TEPAT = "TEPAT"     // hadir, jam masuk <= batas
    const val TELAT = "TELAT"     // hadir, jam masuk > batas
    const val IZIN = "IZIN"       // tidak hadir dengan izin (tidak dipotong)
    const val SAKIT = "SAKIT"     // tidak hadir karena sakit (tidak dipotong)
}

/**
 * Satu catatan presensi. Satu karyawan hanya boleh punya satu catatan per tanggal
 * (index unik employeeId+date).
 */
@Entity(
    tableName = "attendance",
    indices = [Index(value = ["employeeId", "date"], unique = true)],
)
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val employeeId: Long,
    val date: String,            // yyyy-MM-dd (tanggal lokal HP)
    val timestamp: Long,         // epoch millis saat foto diambil
    val timeIn: String,          // HH:mm:ss, kosong untuk IZIN/SAKIT
    val status: String,          // lihat [Status]
    val lateMinutes: Int,        // menit keterlambatan (0 jika tepat)
    val photoPath: String?,      // path file selfie berstempel, null untuk IZIN/SAKIT
    val note: String = "",
    // --- Lokasi GPS saat selfie (null jika tidak didapat / IZIN / SAKIT) ---
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,          // akurasi GPS dalam meter
    val distanceMeters: Int? = null,      // jarak ke titik lokasi kerja, null jika titik belum diatur
    @ColumnInfo(defaultValue = "0") val inLocation: Boolean = false, // true = dalam radius lokasi kerja
    @ColumnInfo(defaultValue = "") val locationNote: String = "",    // mis. "GPS tidak didapat", "lokasi palsu"
    @ColumnInfo(defaultValue = "0") val synced: Boolean = false,     // sudah terkirim ke cloud (dashboard owner)
) {
    /** Kunci presensi di cloud: "yyyy-MM-dd_<employeeId>", satu per karyawan per tanggal (sama dengan index unik lokal). */
    val cloudId: String get() = "${date}_$employeeId"
}

/** Antrean penghapusan di cloud: presensi yang sudah dihapus di HP (salah tekan) tapi belum dihapus di cloud. [docId] = [AttendanceRecord.cloudId]. */
@Entity(tableName = "cloud_deletions")
data class CloudDeletion(@PrimaryKey val docId: String)

/** Tanggal libur (tempat kerja tutup) — tidak dihitung sebagai hari kerja. */
@Entity(tableName = "holidays")
data class Holiday(
    @PrimaryKey val date: String, // yyyy-MM-dd
    val name: String,
)
