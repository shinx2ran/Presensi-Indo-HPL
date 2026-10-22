package com.indohpl.presensi.util

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.indohpl.presensi.PresensiApp
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sekali sehari: hapus foto selfie yang lebih lama dari batas simpan (Pengaturan, bawaan 60 hari) supaya memori HP
 * tidak penuh. Data presensi tetap ada; hanya berkas fotonya yang dihapus.
 */
class PhotoCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PresensiApp
        return try {
            val r = app.repository.cleanupOldPhotos(LocalDate.now())
            if (r.totalDeleted > 0) Log.i(TAG, "dihapus ${r.totalDeleted} berkas, ${r.bytesFreed} byte")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pembersihan foto gagal: ${e.message}")
            Result.success() // dicoba lagi besok
        }
    }

    companion object {
        private const val TAG = "PhotoCleanup"
        private const val WORK_NAME = "photo-cleanup-daily"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<PhotoCleanupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
