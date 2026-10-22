package com.indohpl.presensi.cloud

import android.content.Context
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
import com.indohpl.presensi.BuildConfig
import com.indohpl.presensi.PresensiApp
import com.indohpl.presensi.Site
import com.indohpl.presensi.data.CloudDeletion
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.data.SettingsJson
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Keadaan sinkron untuk layar (selain yang tersimpan di DataStore: waktu sinkron & pesan terakhir). */
data class SyncStatus(val running: Boolean = false, val lastOutcome: CloudSync.Outcome? = null)

/**
 * Sinkron HP presensi <-> dashboard owner (Supabase), mengikuti "Alur sinkron HP" di docs/CLOUD_API.md:
 * 1. kirim antrean hapus + presensi yang belum terkirim (`device_upload`, maks 500 per kiriman, TANPA foto);
 * 2. bila pengaturan diubah owner di HP: `device_save_settings` (base = versi yang diterapkan);
 * 3. `device_sync`: terapkan pengaturan cloud yang lebih baru (atau kirim pengaturan HP bila cloud belum punya);
 * 4. karyawan: cloud kosong -> kirim daftar HP; selain itu daftar cloud diterapkan ke HP (tidak ada yang dihapus).
 * Berjalan di latar lewat WorkManager (menunggu internet, dicoba ulang otomatis).
 */
object CloudSync {
    private const val TAG = "CloudSync"
    private const val WORK_AUTO = "cloud-sync"           // dipicu perubahan data (presensi baru, hapus, pengaturan)
    private const val WORK_NOW = "cloud-sync-now"        // dipicu pengguna / aplikasi dibuka / baru dipasangkan
    private const val WORK_PERIODIC = "cloud-sync-periodic"

    const val MSG_REVOKED = "HP ini diputus dari dashboard. Hubungkan ulang dengan kode baru."
    const val MSG_SETTINGS_OVERWRITTEN = "Pengaturan di HP ditimpa versi yang lebih baru dari dashboard"
    private const val MSG_OFFLINE = "Server tidak terjangkau (cek internet). Dicoba lagi otomatis."

    enum class Outcome { OK, DISABLED, REVOKED, RETRY, FAILED }

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val lock = Mutex()

    private fun networkConstraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Dipanggil setiap ada perubahan data. Aman dipanggil berkali-kali (antre di belakang sinkron yang berjalan). */
    fun schedule(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_AUTO, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /**
     * Sinkron secepatnya (begitu ada internet). [force] = true (tombol "Sinkron sekarang", baru dipasangkan):
     * menggantikan sinkron serupa yang sedang menunggu jadwal ulang. false (aplikasi dibuka): diabaikan bila sudah antre.
     */
    fun syncSoon(context: Context, force: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NOW, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /** Pengaman: sinkron tiap 1 jam walau tidak ada pemicu (juga mengambil perubahan pengaturan dari dashboard). */
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /**
     * Hubungkan HP ini ke dashboard dengan kode dari owner (`device_pair`). Mengembalikan null bila berhasil,
     * atau pesan galat (pesan server apa adanya, berbahasa Indonesia) untuk ditampilkan.
     */
    suspend fun pair(context: Context, repository: Repository, code: String, label: String): String? {
        val name = label.trim().ifEmpty { Site.defaultDeviceLabel }.take(40)
        return try {
            val params = JSONObject()
                .put("p_code", code.trim())
                .put("p_label", name)
                .put("p_site", BuildConfig.FLAVOR)
            val res = JSONObject(SupabaseRpc.call("device_pair", params))
            val key = (res.opt("key") as? String)?.takeIf { it.isNotBlank() }
                ?: return "Jawaban server tidak lengkap. Coba lagi."
            val siteName = (res.opt("siteName") as? String)?.takeIf { it.isNotBlank() } ?: Site.name
            lock.withLock {
                repository.settingsStore.linkCloud(key, name, siteName, "Terhubung ke dashboard $siteName sebagai $name")
                // Kirim ulang semua presensi di HP ke tempat yang baru dipasangkan.
                repository.attendanceDao.markAllUnsynced()
            }
            syncSoon(context, force = true)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
            e.message ?: "Gagal menghubungkan (HTTP ${e.httpStatus})"
        } catch (e: IOException) {
            "Tidak bisa terhubung ke server. Periksa internet lalu coba lagi."
        } catch (e: JSONException) {
            "Jawaban server tidak terbaca. Coba lagi."
        }
    }

    /**
     * "Putuskan HP ini": kunci diputus juga di server (`device_unpair`, bila ada internet) lalu dihapus dari HP;
     * sinkron berhenti; presensi di HP tetap ada. Mengembalikan true bila server ikut memutus.
     */
    suspend fun disconnect(repository: Repository): Boolean {
        val key = repository.settingsStore.currentCloud().deviceKey
        val serverDone = key.isNotBlank() && try {
            SupabaseRpc.call("device_unpair", JSONObject().put("p_key", key))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
            e.isKeyRejected // sudah diputus di server
        } catch (e: IOException) {
            false
        }
        repository.settingsStore.unlinkCloud(
            if (serverDone) "HP ini sudah diputus dari dashboard." else
                "HP ini diputus (kunci dihapus dari HP). Tidak ada internet: hapus juga HP ini di dashboard, tab HP.",
            isError = false,
        )
        return serverDone
    }

    /**
     * Jalankan satu putaran sinkron sekarang (dipanggil worker). [Outcome.RETRY] = gangguan jaringan / server
     * sementara (WorkManager mencoba lagi); selain itu selesai (galat lain dicatat dan dicoba lagi di jadwal berikutnya).
     */
    suspend fun syncNow(context: Context, repository: Repository): Outcome = lock.withLock {
        val cfg = repository.settingsStore.currentCloud()
        if (!cfg.isLinked) return@withLock Outcome.DISABLED
        _status.value = _status.value.copy(running = true)
        var outcome: Outcome? = null
        try {
            val result = runGuarded(repository, cfg.deviceKey, cfg.deviceLabel)
            outcome = result
            result
        } finally {
            _status.value = SyncStatus(running = false, lastOutcome = outcome ?: _status.value.lastOutcome)
        }
    }

    private suspend fun runGuarded(repository: Repository, key: String, label: String): Outcome =
        try {
            runSync(repository, key, label)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
            when {
                e.isKeyRejected -> {
                    // Kunci HP ditolak (HP diputus owner): berhenti, kunci tidak disimpan lagi.
                    repository.settingsStore.unlinkCloud(MSG_REVOKED, isError = true)
                    Outcome.REVOKED
                }
                e.isTemporary -> {
                    repository.settingsStore.recordMessage("Server sedang sibuk (HTTP ${e.httpStatus}). Dicoba lagi otomatis.", isError = true)
                    Outcome.RETRY
                }
                else -> {
                    Log.w(TAG, "sinkron ditolak: HTTP ${e.httpStatus} ${e.code} ${e.message}")
                    repository.settingsStore.recordMessage("Gagal sinkron: ${e.message}", isError = true)
                    Outcome.FAILED
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "jaringan: ${e.message}")
            repository.settingsStore.recordMessage(MSG_OFFLINE, isError = true)
            Outcome.RETRY
        } catch (e: JSONException) {
            Log.w(TAG, "jawaban tidak terbaca: ${e.message}")
            repository.settingsStore.recordMessage("Jawaban server tidak terbaca. Dicoba lagi nanti.", isError = true)
            Outcome.FAILED
        }

    private suspend fun runSync(repository: Repository, key: String, label: String): Outcome {
        val store = repository.settingsStore
        val notes = mutableListOf<String>()
        var reportedVersion = -1L // versi pengaturan yang terakhir dilaporkan ke server lewat device_sync
        suspend fun sync(): JSONObject {
            reportedVersion = store.currentCloudState().appliedVersion
            return deviceSync(repository, key, label)
        }

        // 0) Sinkron pertama setelah dipasangkan: cocokkan karyawan DULU, sebelum presensi dikirim.
        if (!store.currentCloudState().employeesReconciled) {
            val problem = reconcileEmployees(repository, key, sync())
            if (problem != null) {
                store.recordMessage(problem, isError = true)
                return Outcome.FAILED
            }
        }

        // 1) Antrean hapus + presensi belum terkirim.
        val sent = upload(repository, key)
        if (sent > 0) notes += "Terkirim $sent presensi"

        // 2) Pengaturan yang diubah owner di HP.
        when (val pushed = pushSettingsIfDirty(repository, key)) {
            PushResult.OVERWRITTEN -> notes += MSG_SETTINGS_OVERWRITTEN
            is PushResult.Rejected -> notes += "Pengaturan HP ditolak server: ${pushed.message}"
            PushResult.NONE, PushResult.PUSHED -> Unit
        }

        // 3) device_sync: pengaturan cloud.
        var res = sync()
        val settings = res.optJSONObject("settings")
        if (settings == null) {
            // Cloud belum punya pengaturan untuk tempat ini: pakai pengaturan HP sebagai awal.
            val data = SettingsJson.toJson(store.current(), repository.holidayDao.getAll())
            try {
                val r = saveSettings(key, data, 0L)
                if (r.ok) store.setAppliedSettingsVersion(r.version)
            } catch (e: CloudException) {
                if (e.isAuthError || e.isTemporary) throw e
                notes += "Pengaturan HP ditolak server: ${e.message}"
            }
        } else {
            val version = (settings.opt("version") as? Number)?.toLong() ?: 0L
            val data = settings.optJSONObject("data")
            // Diperiksa ulang (versi & belum ada perubahan HP) di dalam kunci pengaturan Repository.
            if (data != null && repository.applyCloudSettings(version, data)) {
                notes += "Pengaturan dari dashboard diterapkan (versi $version)"
            }
        }

        // 4) Karyawan.
        val cloudEmployees = CloudMapper.employeesFromJson(res.optJSONArray("employees"))
        if (cloudEmployees.isEmpty()) {
            val local = repository.employeeDao.getAll()
            if (local.isNotEmpty()) {
                SupabaseRpc.call(
                    "device_seed_employees",
                    JSONObject().put("p_key", key).put("p_rows", CloudMapper.employeesJson(local)),
                )
                res = sync() // sekali lagi, supaya daftar cloud (id resmi) ikut diterapkan
                repository.upsertEmployees(CloudMapper.employeesFromJson(res.optJSONArray("employees")))
            }
        } else {
            val changed = repository.upsertEmployees(cloudEmployees)
            if (changed > 0) notes += "Daftar karyawan diperbarui dari dashboard ($changed)"
        }

        // 5) Laporkan versi pengaturan yang sekarang benar-benar dipakai HP (dashboard: "menunggu HP sinkron").
        if (store.currentCloudState().appliedVersion != reportedVersion) sync()

        store.recordSyncSuccess(System.currentTimeMillis(), notes.takeIf { it.isNotEmpty() }?.joinToString(". "))
        return Outcome.OK
    }

    /**
     * Cocokkan daftar karyawan HP dengan dashboard pada sinkron pertama setelah dipasangkan.
     * Cloud kosong -> kirim daftar HP. Cloud berisi -> nomor karyawan yang sama harus orang yang sama bila HP sudah
     * punya presensi atas nomor itu; kalau tidak, berhenti (null = beres, selain itu pesan untuk owner).
     */
    private suspend fun reconcileEmployees(repository: Repository, key: String, res: JSONObject): String? {
        val cloud = CloudMapper.employeesFromJson(res.optJSONArray("employees"))
        val local = repository.employeeDao.getAll()
        if (cloud.isEmpty()) {
            if (local.isNotEmpty()) {
                SupabaseRpc.call("device_seed_employees", JSONObject().put("p_key", key).put("p_rows", CloudMapper.employeesJson(local)))
            }
        } else {
            val byId = cloud.associateBy { it.id }
            val withHistory = repository.attendanceDao.employeeIdsWithRecords().toHashSet()
            val clash = local.firstOrNull { l ->
                val c = byId[l.id]
                c != null && l.id in withHistory && !sameName(c.name, l.name)
            }
            if (clash != null) {
                val c = byId.getValue(clash.id)
                return "Daftar karyawan di dashboard berbeda dengan HP: nomor ${clash.id} di HP \"${clash.name}\", " +
                    "di dashboard \"${c.name}\". Samakan dulu di dashboard (tab Karyawan), lalu tekan Sinkron sekarang. " +
                    "Presensi belum dikirim supaya tidak tercatat atas nama orang lain."
            }
            repository.upsertEmployees(cloud)
        }
        repository.settingsStore.setEmployeesReconciled()
        return null
    }

    private fun sameName(a: String, b: String): Boolean =
        a.trim().replace(Regex("\\s+"), " ").equals(b.trim().replace(Regex("\\s+"), " "), ignoreCase = true)

    /** Kirim antrean hapus lalu presensi; penanda terkirim / antrean dihapus hanya setelah server menerima. */
    private suspend fun upload(repository: Repository, key: String): Int {
        val queued = repository.cloudDeletionDao.getAll()
        val deletes = ArrayList<Pair<CloudDeletion, JSONObject>>()
        for (d in queued) {
            val json = CloudMapper.deletionJson(d.docId)
            if (json == null) repository.cloudDeletionDao.delete(d) else deletes += d to json // rusak: tidak mungkin terkirim
        }
        val rows = repository.attendanceDao.getUnsynced()
        var sent = 0
        for (batch in CloudMapper.uploadBatches(deletes, rows)) {
            val params = JSONObject()
                .put("p_key", key)
                .put("p_rows", if (batch.rows.isEmpty()) JSONObject.NULL else CloudMapper.attendanceArray(batch.rows))
                .put("p_deletes", if (batch.deletes.isEmpty()) JSONObject.NULL else JSONArray().apply { batch.deletes.forEach { put(it.second) } })
            SupabaseRpc.call("device_upload", params)
            batch.deletes.forEach { repository.cloudDeletionDao.delete(it.first) }
            if (batch.rows.isNotEmpty()) repository.attendanceDao.markSynced(batch.rows.map { it.id })
            sent += batch.rows.size
        }
        return sent
    }

    private sealed class PushResult {
        data object NONE : PushResult()
        data object PUSHED : PushResult()
        data object OVERWRITTEN : PushResult()
        data class Rejected(val message: String) : PushResult()
    }

    private suspend fun pushSettingsIfDirty(repository: Repository, key: String): PushResult {
        val store = repository.settingsStore
        val state = store.currentCloudState()
        if (!state.settingsDirty) return PushResult.NONE
        val data = SettingsJson.toJson(store.current(), repository.holidayDao.getAll())
        return try {
            // Dasar = versi cloud yang sedang diterapkan di HP (selama "diubah di HP", versi itu tidak berubah:
            // pengaturan cloud tidak diterapkan di atas perubahan yang belum terkirim).
            val r = saveSettings(key, data, state.appliedVersion)
            if (r.ok) {
                store.settingsPushed(r.version, state.dirtySeq)
                PushResult.PUSHED
            } else {
                // Cloud sudah punya versi lebih baru (diubah dari dashboard): versi cloud yang berlaku.
                store.settingsPushRejected(state.dirtySeq)
                PushResult.OVERWRITTEN
            }
        } catch (e: CloudException) {
            if (e.isAuthError || e.isTemporary) throw e
            // Data ditolak validasi server (22023): jangan macet mengirim yang sama terus; pakai versi cloud.
            store.settingsPushRejected(state.dirtySeq)
            PushResult.Rejected(e.message ?: "HTTP ${e.httpStatus}")
        }
    }

    private data class SaveResult(val ok: Boolean, val version: Long)

    private suspend fun saveSettings(key: String, data: JSONObject, baseVersion: Long): SaveResult {
        val params = JSONObject().put("p_key", key).put("p_data", data).put("p_base_version", baseVersion)
        val res = JSONObject(SupabaseRpc.call("device_save_settings", params))
        return SaveResult(ok = res.opt("ok") == true, version = (res.opt("version") as? Number)?.toLong() ?: 0L)
    }

    private suspend fun deviceSync(repository: Repository, key: String, label: String): JSONObject {
        val params = JSONObject()
            .put("p_key", key)
            .put("p_label", label)
            .put("p_app_version", BuildConfig.VERSION_NAME)
            .put("p_unsynced", repository.attendanceDao.countUnsynced())
            .put("p_settings_version", repository.settingsStore.currentCloudState().appliedVersion)
        return JSONObject(SupabaseRpc.call("device_sync", params))
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PresensiApp
        return try {
            when (CloudSync.syncNow(applicationContext, app.repository)) {
                CloudSync.Outcome.RETRY -> Result.retry()
                else -> Result.success() // termasuk HP diputus: berhenti mencoba
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("CloudSync", "worker gagal: ${e.message}")
            Result.retry()
        }
    }
}
