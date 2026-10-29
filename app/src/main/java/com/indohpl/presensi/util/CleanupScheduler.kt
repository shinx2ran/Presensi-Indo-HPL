package com.indohpl.presensi.util

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.indohpl.presensi.PresensiApp
import java.util.concurrent.TimeUnit

/** Menjadwalkan pembersihan data lama sekali sehari (dan dijalankan juga saat aplikasi dibuka). */
object CleanupScheduler {
    private const val WORK = "cleanup-old-data"

    fun scheduleDaily(context: Context) {
        val req = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val n = (applicationContext as PresensiApp).repository.cleanupOldData(automatic = true)
        if (n > 0) Log.i("Cleanup", "Menghapus $n catatan lama")
        Result.success()
    } catch (e: Exception) {
        Log.w("Cleanup", "gagal: ${e.message}")
        Result.retry()
    }
}
