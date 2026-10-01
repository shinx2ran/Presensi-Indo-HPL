package com.indohpl.presensi

import android.app.Application
import com.indohpl.presensi.cloud.CloudSync
import com.indohpl.presensi.data.Repository
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
        CloudSync.schedulePeriodic(this)
    }
}
