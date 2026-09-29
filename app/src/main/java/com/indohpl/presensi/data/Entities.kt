package com.indohpl.presensi.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Seorang karyawan. Gender hanya untuk tampilan (ikon), tidak memengaruhi perhitungan. */
@Entity(tableName = "employees")
data class Employee(
    @PrimaryKey val id: Long,
    val name: String,
    val gender: String,          // "P" = perempuan, "L" = laki-laki
    val active: Boolean = true,
)

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
)

/** Tanggal libur (toko tutup) — tidak dihitung sebagai hari kerja. */
@Entity(tableName = "holidays")
data class Holiday(
    @PrimaryKey val date: String, // yyyy-MM-dd
    val name: String,
)
