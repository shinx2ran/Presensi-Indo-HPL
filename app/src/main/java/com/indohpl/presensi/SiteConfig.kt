package com.indohpl.presensi

import com.indohpl.presensi.data.Employee

/**
 * Identitas tempat kerja. Nilainya berbeda per varian build dan didefinisikan sebagai `val Site`
 * di `src/indohpl/java/.../Site.kt` (toko Indo HPL) dan `src/egkost/java/.../Site.kt` (EG Kost).
 * Kode bersama hanya memakai [Site]. Menambah tempat kerja baru = satu flavor di app/build.gradle.kts +
 * Site.kt + res/values/strings.xml (app_name) + res/values/colors.xml (ic_launcher_background) di src/<varian>/.
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
    /** Nama HP bawaan saat menghubungkan ke dashboard owner, mis. "HP Laundry" (bisa diganti di Pengaturan). */
    val defaultDeviceLabel: String,
    /** Karyawan awal. Dimasukkan ke database setiap aplikasi dibuka; yang sudah ada (id sama) tidak diubah. */
    val seedEmployees: List<Employee>,
    /** Jam masuk & toleransi bawaan (bisa diubah owner di Pengaturan). */
    val workStartFemale: String,
    val toleranceFemale: Int,
    val workStartMale: String,
    val toleranceMale: Int,
)
