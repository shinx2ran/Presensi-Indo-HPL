package com.indohpl.presensi

import com.indohpl.presensi.data.Employee

/** Varian `indohpl`: aplikasi presensi di HP kasir toko Indo HPL. */
val Site = SiteConfig(
    name = "Indo HPL",
    appTitle = "Presensi Indo HPL",
    place = "toko",
    placeFull = "toko Indo HPL",
    device = "HP kasir",
    stampFooter = "Presensi Indo HPL · HP Kasir",
    radiusHint = "100 m cukup untuk toko di dalam ruko/mall.",
    firebaseProjectHint = "presensi-indo-hpl",
    seedEmployees = listOf(
        Employee(1, "Sara", "P"),
        Employee(2, "Riyanti", "P"),
        Employee(3, "Evan", "L"),
        Employee(4, "Madi", "L"),
        Employee(5, "Roni", "L"),
    ),
    // Perempuan 07:45 (+10 mnt), laki-laki 08:00 (+5 mnt).
    workStartFemale = "07:45",
    toleranceFemale = 10,
    workStartMale = "08:00",
    toleranceMale = 5,
)
