package com.indohpl.presensi.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch

/**
 * Posisi HP dari fused location (Google Play Services), dibuat tahan untuk HP murah di dalam ruangan.
 *
 * [updates] memancarkan posisi secara progresif: emisi pertama secepat mungkin (fix pertama yang
 * datang, atau posisi terakhir yang masih segar), lalu setiap kali ada fix yang lebih akurat.
 * Aliran selesai saat akurasi sudah <= [goodEnoughMeters] atau [timeoutMs] habis.
 * Pemanggil bisa langsung memakai emisi pertama (tombol jepret aktif) sambil akurasi diperbaiki.
 */
object LocationHelper {
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Posisi terakhir yang lebih tua dari ini tidak dipakai sebagai cadangan. */
    private const val STALE_MAX_MS = 5 * 60_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** false bila saklar Lokasi di HP dimatikan (fused location tidak akan mengirim apa pun). */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /**
     * Aliran posisi yang makin akurat (lihat dokumentasi objek). Kosong (selesai tanpa emisi) bila izin
     * tidak ada, Lokasi HP mati, atau tidak ada posisi sama sekali sampai [timeoutMs].
     */
    @SuppressLint("MissingPermission")
    fun updates(context: Context, timeoutMs: Long = 40_000, goodEnoughMeters: Float = 30f): Flow<Location> = callbackFlow {
        if (!hasPermission(context) || !isLocationEnabled(context)) {
            close()
            return@callbackFlow
        }
        val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        var best: Location? = null
        var liveSeen = false

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val b = best
                // Fix langsung selalu mengalahkan cadangan posisi lama; sesudahnya hanya yang lebih akurat.
                if (b == null || !liveSeen || accuracyOf(loc) <= accuracyOf(b)) {
                    best = loc
                    trySend(loc)
                }
                liveSeen = true
                if (accuracyOf(loc) <= goodEnoughMeters) close()
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close() }

        // Cadangan awal: posisi terakhir yang masih segar (umur dihitung dengan jam monotonik, bukan jam dinding)
        // dan punya akurasi. Dipakai hanya bila belum ada fix langsung.
        client.lastLocation.addOnSuccessListener { last ->
            if (last != null && best == null && last.hasAccuracy() && ageMs(last) in 0 until STALE_MAX_MS) {
                best = last
                trySend(last)
            }
        }

        launch {
            delay(timeoutMs)
            close()
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    /** Posisi terbaik yang didapat sampai aliran [updates] selesai; null bila tidak ada. */
    suspend fun current(context: Context, timeoutMs: Long = 40_000): Location? = updates(context, timeoutMs).lastOrNull()

    private fun accuracyOf(l: Location): Float = if (l.hasAccuracy()) l.accuracy else Float.MAX_VALUE

    private fun ageMs(l: Location): Long = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    @Suppress("DEPRECATION")
    fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
}
