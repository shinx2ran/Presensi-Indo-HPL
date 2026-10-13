package com.indohpl.presensi

import com.indohpl.presensi.data.Employee

/** Varian `egkost`: aplikasi presensi karyawan EG Kost. */
val Site = SiteConfig(
    name = "EG Kost",
    appTitle = "Presensi EG Kost",
    place = "kost",
    placeFull = "EG Kost",
    device = "HP presensi",
    stampFooter = "Presensi EG Kost",
    radiusHint = "100 m cukup untuk satu bangunan kost dan halamannya.",
    firebaseProjectHint = "presensi-eg-kost",
    seedEmployees = listOf(
        Employee(1, "Inul", "P", role = "Laundry"),
        Employee(2, "Bik Nia", "P", role = "Kos"),
    ),
    // Jam masuk 07:30 untuk semua, toleransi 10 menit (bisa diubah owner di Pengaturan).
    workStartFemale = "07:30",
    toleranceFemale = 10,
    workStartMale = "07:30",
    toleranceMale = 10,
)
