package com.indohpl.presensi

import com.indohpl.presensi.data.Employee

/**
 * Identitas tempat kerja. Nilainya berbeda per varian build dan didefinisikan sebagai `val Site`
 * di `src/indohpl/java/.../Site.kt` (toko Indo HPL) dan `src/egkost/java/.../Site.kt` (EG Kost).
 * Kode bersama hanya memakai [Site], jadi menambah tempat kerja baru = menambah satu varian + satu berkas.
 */
data class SiteConfig(
    /** Nama tempat untuk judul & stempel, mis. "Indo HPL". */
    val name: String,
    /** Judul aplikasi di Beranda, mis. "Presensi Indo HPL". */
    val appTitle: String,
    /** Kata tempat kerja dalam kalimat (huruf kecil): "toko", "kost". */
    val place: String,
    /** Nama tempat lengkap untuk judul bagian lokasi: "toko Indo HPL", "EG Kost". */
    val placeFull: String,
    /** Sebutan HP yang dipakai absen: "HP kasir", "HP presensi". */
    val device: String,
    /** Baris paling bawah pada stempel foto selfie. */
    val stampFooter: String,
    /** Petunjuk radius di Pengaturan. */
    val radiusHint: String,
    /** Contoh nama proyek Firebase (placeholder di Pengaturan). */
    val firebaseProjectHint: String,
    /** Karyawan awal yang di-seed saat aplikasi pertama kali dibuka. */
    val seedEmployees: List<Employee>,
    /** Jam masuk & toleransi bawaan (bisa diubah owner di Pengaturan). */
    val workStartFemale: String,
    val toleranceFemale: Int,
    val workStartMale: String,
    val toleranceMale: Int,
)
