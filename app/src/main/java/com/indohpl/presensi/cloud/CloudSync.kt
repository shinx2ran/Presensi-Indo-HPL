package com.indohpl.presensi.cloud

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.indohpl.presensi.PresensiApp
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.util.PhotoStamper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Penjadwal & pelaksana sinkronisasi ke Firestore. Berjalan di latar lewat WorkManager (menunggu internet). */
object CloudSync {
    private const val TAG = "CloudSync"
    private const val WORK_NOW = "cloud-sync"
    private const val WORK_PERIODIC = "cloud-sync-periodic"

    private val _lastMessage = MutableStateFlow("")
    val lastMessage: StateFlow<String> = _lastMessage

    /** Dipanggil setiap ada perubahan data. Aman dipanggil berkali-kali. */
    fun schedule(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Pengaman: coba sinkron tiap 6 jam walau tidak ada pemicu. */
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /**
     * Kirim semua presensi yang belum tersinkron + proses antrean hapus.
     * Mengembalikan true jika semua berhasil (atau cloud tidak aktif).
     */
    suspend fun syncNow(context: Context, repository: Repository): Boolean {
        val cfg = repository.settingsStore.currentCloud()
        if (!cfg.enabled || !cfg.isComplete) return true
        val cloud = FirebaseCloud(context, cfg)
        val names = repository.employeeDao.getAll().associate { it.id to it.name }
        var ok = true
        var sent = 0

        for (d in repository.cloudDeletionDao.getAll()) {
            try {
                cloud.delete(d.docId)
                repository.cloudDeletionDao.delete(d)
            } catch (e: Exception) {
                Log.w(TAG, "hapus ${d.docId} gagal: ${e.message}")
                ok = false
            }
        }

        for (r in repository.attendanceDao.getUnsynced()) {
            try {
                val photo = r.photoPath?.let { path ->
                    val f = File(path)
                    if (f.exists()) PhotoStamper.compressForCloud(f)?.let { Base64.encodeToString(it, Base64.NO_WRAP) } else null
                }
                cloud.upload(r.cloudId, CloudMapper.toMap(r, names[r.employeeId] ?: r.employeeId.toString(), photo, FirebaseCloud.deviceName()))
                repository.attendanceDao.markSynced(r.id)
                sent++
            } catch (e: Exception) {
                Log.w(TAG, "upload ${r.cloudId} gagal: ${e.message}")
                _lastMessage.value = "Gagal kirim: ${e.message ?: e.javaClass.simpleName}"
                ok = false
            }
        }
        if (ok) _lastMessage.value = if (sent > 0) "Terkirim $sent presensi" else "Semua sudah tersinkron"
        return ok
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PresensiApp
        return try {
            if (CloudSync.syncNow(applicationContext, app.repository)) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.w("CloudSync", "worker gagal: ${e.message}")
            Result.retry()
        }
    }
}
