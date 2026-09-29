package com.indohpl.presensi.domain

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Hasil pengecekan lokasi satu presensi. Murni Kotlin supaya bisa diuji. */
data class GeoCheck(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,        // meter, null jika tidak diketahui
    val distanceMeters: Int?,    // null jika titik toko belum diatur
    val inLocation: Boolean,     // dalam radius toko (atau titik toko belum diatur -> false)
    val isMock: Boolean,         // lokasi palsu (aplikasi fake GPS)
    val note: String,            // keterangan singkat untuk stempel/rekap
)

object GeoRule {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Jarak haversine dalam meter. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Evaluasi posisi HP terhadap titik toko.
     * Toleransi: jarak dikurangi akurasi GPS (kalau GPS bilang "±30 m", 20 m di luar radius masih dianggap di lokasi).
     */
    fun check(
        latitude: Double,
        longitude: Double,
        accuracy: Float?,
        isMock: Boolean,
        storeLat: Double?,
        storeLon: Double?,
        radiusMeters: Int,
    ): GeoCheck {
        if (storeLat == null || storeLon == null) {
            return GeoCheck(latitude, longitude, accuracy, null, false, isMock, if (isMock) "lokasi palsu" else "titik toko belum diatur")
        }
        val d = distanceMeters(latitude, longitude, storeLat, storeLon)
        val effective = (d - (accuracy ?: 0f)).coerceAtLeast(0.0)
        val inside = !isMock && effective <= radiusMeters
        val note = when {
            isMock -> "lokasi palsu"
            inside -> "di lokasi toko"
            else -> "di luar lokasi"
        }
        return GeoCheck(latitude, longitude, accuracy, d.roundToInt(), inside, isMock, note)
    }

    fun formatDistance(meters: Int): String =
        if (meters < 1000) "$meters m" else String.format(Locale.US, "%.1f km", meters / 1000.0).replace('.', ',')

    fun formatCoord(lat: Double, lon: Double): String = String.format(Locale.US, "%.6f, %.6f", lat, lon)

    /** Terima "-6.200000, 106.816666" (format salin dari Google Maps). */
    fun parseLatLon(text: String): Pair<Double, Double>? {
        val parts = text.trim().split(",", " ").filter { it.isNotBlank() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }
}
