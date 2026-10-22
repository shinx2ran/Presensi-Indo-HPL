package com.indohpl.presensi

import android.app.Application
import com.indohpl.presensi.cloud.CloudSync
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.util.AppUpdater
import com.indohpl.presensi.util.PhotoCleanupWorker
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PresensiApp : Application() {
    lateinit var repository: Repository
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this)
        appScope.launch { repository.ensureSeeded() }
        appScope.launch { runCatching { repository.settingsStore.cleanupLegacyCloud() } } // sisa konfigurasi Firebase lama
        CloudSync.schedulePeriodic(this)
        // Foto selfie lama dihapus sekali sehari (dan sekali tiap aplikasi dibuka) supaya memori HP tidak penuh.
        PhotoCleanupWorker.schedule(this)
        appScope.launch { runCatching { repository.cleanupOldPhotos(LocalDate.now()) } }
        appScope.launch { runCatching { AppUpdater.cleanup(this@PresensiApp) } }
    }
}
