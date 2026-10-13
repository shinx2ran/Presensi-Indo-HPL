package com.indohpl.presensi.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ambil posisi HP sekali (fused location dari Google Play Services).
 *
 * Dibuat tahan untuk HP murah di dalam ruangan: alih-alih menunggu satu jawaban lalu menyerah,
 * kita berlangganan pembaruan posisi, menyimpan yang paling akurat, dan berhenti lebih awal
 * begitu akurasinya <= [goodEnoughMeters]. Posisi kasar dari jaringan (±beberapa ratus meter)
 * biasanya datang dalam 1–3 detik, jadi tombol jepret cepat aktif; GPS yang lebih akurat menyusul.
 */
object LocationHelper {
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * null jika izin tidak ada, Lokasi HP mati, atau tidak ada posisi sama sekali dalam [timeoutMs]
     * (dan tidak ada posisi terakhir yang masih segar).
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 40_000, goodEnoughMeters: Float = 30f): Location? {
        if (!hasPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

        var best: Location? = null
        var callback: LocationCallback? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val cb = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            val loc = result.lastLocation ?: return
                            val b = best
                            if (b == null || accuracyOf(loc) <= accuracyOf(b)) best = loc
                            if (accuracyOf(loc) <= goodEnoughMeters && cont.isActive) cont.resume(Unit)
                        }
                    }
                    callback = cb
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                        .setMinUpdateIntervalMillis(500L)
                        .setWaitForAccurateLocation(false)
                        .build()
                    client.requestLocationUpdates(request, cb, Looper.getMainLooper())
                        .addOnFailureListener { if (cont.isActive) cont.resume(Unit) } // mis. Lokasi HP mati
                }
            }
        } finally {
            callback?.let { client.removeLocationUpdates(it) }
        }
        best?.let { return it }

        // Cadangan: posisi terakhir yang masih cukup segar (< 10 menit). HP murah di dalam ruangan
        // sering tidak dapat fix baru, tetapi posisi beberapa menit lalu masih mewakili tempat yang sama.
        val last = withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                client.lastLocation
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        }
        return last?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000 }
    }

    private fun accuracyOf(l: Location): Float = if (l.hasAccuracy()) l.accuracy else Float.MAX_VALUE

    @Suppress("DEPRECATION")
    fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
}
