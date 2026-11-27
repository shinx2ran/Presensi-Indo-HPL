package com.indohpl.presensi.data

import androidx.room.ColumnInfo
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
    // --- Lokasi GPS saat selfie (null jika tidak didapat / IZIN / SAKIT) ---
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,          // akurasi GPS dalam meter
    val distanceMeters: Int? = null,      // jarak ke titik toko, null jika titik toko belum diatur
    @ColumnInfo(defaultValue = "0") val inLocation: Boolean = false, // true = dalam radius toko
    @ColumnInfo(defaultValue = "") val locationNote: String = "",    // mis. "GPS tidak didapat", "lokasi palsu"
    // --- Anulir telat oleh owner (mis. pintu toko belum dibuka sehingga HP belum bisa dipakai) ---
    val annulledLateMinutes: Int? = null,                            // menit telat ASLI bila dianulir; null = tidak dianulir
    @ColumnInfo(defaultValue = "") val annulReason: String = "",     // alasan anulir, mis. "Pintu toko belum dibuka"
)

/** true bila telat dianulir owner (status sudah TEPAT, menit telat asli disimpan). Ekstensi agar Room tidak melihatnya. */
val AttendanceRecord.isAnnulled: Boolean get() = annulledLateMinutes != null

/** Tanggal libur (toko tutup) — tidak dihitung sebagai hari kerja. */
@Entity(tableName = "holidays")
data class Holiday(
    @PrimaryKey val date: String, // yyyy-MM-dd
    val name: String,
)

/**
 * Penjualan toko satu bulan untuk bonus penjualan (diisi owner dari laporan Toqoo yang dicek Koko).
 * Penjualan yang dihitung = [grossSales] - [excludedSales] (proyek CE LENY / Pawan 5 tidak dihitung).
 */
@Entity(tableName = "sales_months")
data class SalesMonth(
    @PrimaryKey val ym: String,          // yyyy-MM
    val grossSales: Long,                // total penjualan dari laporan Toqoo (Rp)
    val excludedSales: Long,             // dikurangi: barang untuk proyek CE LENY (Pawan 5) (Rp)
    val asOfDate: String,                // data penjualan sampai tanggal ini (yyyy-MM-dd)
    val note: String,
    val excludedEmployeeIds: String,     // id karyawan yang TIDAK ikut bonus bulan ini, dipisah koma ("" = semua ikut)
    val paidAt: Long?,                   // waktu ditandai "sudah dibayar"; null = belum (hasil masih dihitung ulang)
    val snapshot: String,                // hasil yang dikunci saat ditandai dibayar (JSON); "" bila belum
    val updatedAt: Long,
)

/** Jenis catatan owner untuk bonus penjualan. */
object BonusEventType {
    const val TEGURAN = "TEGURAN"             // teguran oleh Akong / Koko; teguran ke-2 dalam sebulan = tidak dapat bonus
    const val SETENGAH_HARI = "SETENGAH_HARI" // tidak masuk setengah hari / pulang sebelum waktunya = 1/2 hari tidak masuk
}

/** Catatan owner: teguran atau setengah hari. Unik per (karyawan, tanggal, jenis, waktu dibuat) agar pulihkan tidak menggandakan. */
@Entity(
    tableName = "bonus_events",
    indices = [
        Index(value = ["date"]),
        Index(value = ["employeeId", "date", "type", "createdAt"], unique = true),
    ],
)
data class BonusEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val employeeId: Long,
    val date: String,                    // yyyy-MM-dd
    val type: String,                    // lihat [BonusEventType]
    val note: String,
    val byWhom: String,                  // mis. "Akong", "Koko"
    val createdAt: Long,
)
