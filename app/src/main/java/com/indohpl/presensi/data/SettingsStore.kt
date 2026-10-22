package com.indohpl.presensi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.indohpl.presensi.Site
import com.indohpl.presensi.domain.BonusConfig
import com.indohpl.presensi.domain.PhotoRetention
import java.time.DayOfWeek
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Pengaturan aplikasi yang bisa diubah owner dari layar Pengaturan. Nilai bawaan per varian ada di [Site]. */
data class AppSettings(
    // Jam masuk & toleransi dibedakan per gender (P = perempuan, L = laki-laki).
    val workStartFemale: String = Site.workStartFemale,
    val toleranceFemale: Int = Site.toleranceFemale,
    val workStartMale: String = Site.workStartMale,
    val toleranceMale: Int = Site.toleranceMale,
    val monthlyBonus: Long = 250_000,         // uang rajin penuh per bulan (Rp)
    val lateDeduction: Long = 0,              // potongan per hari telat; 0 = otomatis (bonus / hari kerja)
    val deductAbsent: Boolean = true,         // potong juga jika alpa (tidak hadir tanpa izin)
    val workDays: Set<DayOfWeek> = DayOfWeek.values().toSet(), // Senin–Minggu
    // --- Titik lokasi kerja (toko / kost) ---
    val storeLat: Double? = null,             // null = titik lokasi belum diatur
    val storeLon: Double? = null,
    val radiusMeters: Int = 100,              // radius toleransi dari titik lokasi
    val requireLocation: Boolean = true,      // true = absen ditolak jika di luar radius / GPS tidak ada
    // --- Verifikasi ---
    val requireBiometric: Boolean = true,     // true = sidik jari/wajah/PIN HP wajib sebelum selfie; matikan bila HP tanpa kunci layar
    // --- Memori HP ---
    val photoRetentionDays: Int = PhotoRetention.DEFAULT_DAYS, // foto selfie dihapus dari HP setelah N hari; 0 = simpan selamanya
) {
    val hasStoreLocation: Boolean get() = storeLat != null && storeLon != null

    fun workStartFor(gender: String): String = if (gender == "P") workStartFemale else workStartMale
    fun toleranceFor(gender: String): Int = if (gender == "P") toleranceFemale else toleranceMale

    /** Aturan perempuan dan laki-laki sama persis -> cukup ditampilkan satu kali. */
    val sameRuleForAll: Boolean get() = workStartFemale == workStartMale && toleranceFemale == toleranceMale

    /** Ringkasan aturan untuk ditampilkan / diekspor. */
    fun rulesText(): String =
        if (sameRuleForAll) {
            "$workStartFemale (toleransi $toleranceFemale mnt)"
        } else {
            "Perempuan $workStartFemale (toleransi $toleranceFemale mnt) · Laki-laki $workStartMale (toleransi $toleranceMale mnt)"
        }

    /** Versi pendek untuk kartu Beranda, mis. "07:30 (+10 mnt)" atau "P 07:45 (+10 mnt) · L 08:00 (+5 mnt)". */
    fun shortRulesText(): String =
        if (sameRuleForAll) {
            "$workStartFemale (+$toleranceFemale mnt)"
        } else {
            "P $workStartFemale (+$toleranceFemale mnt) · L $workStartMale (+$toleranceMale mnt)"
        }

    fun bonusConfig() = BonusConfig(
        monthlyBonus = monthlyBonus,
        lateDeductionOverride = lateDeduction,
        deductAbsent = deductAbsent,
    )
}

/**
 * Hubungan HP ini dengan dashboard owner (Supabase). [deviceKey] didapat sekali dari `device_pair`
 * memakai kode dari dashboard; tanpa kunci, HP tidak tersambung.
 */
data class CloudConfig(
    val enabled: Boolean = false,
    val deviceKey: String = "",
    val deviceLabel: String = "",   // nama HP di dashboard, mis. "HP Laundry"
    val siteName: String = "",      // nama tempat dari server, mis. "EG Kost"
) {
    val isComplete: Boolean get() = deviceKey.isNotBlank()

    /** Tersambung dan aktif: presensi dikirim, pengaturan ikut dashboard. */
    val isLinked: Boolean get() = enabled && isComplete
}

/** Keadaan sinkron yang disimpan di DataStore (tetap ada walau aplikasi ditutup). */
data class CloudState(
    /** Versi pengaturan cloud yang sudah diterapkan di HP ini; 0 = belum ada. */
    val appliedVersion: Long = 0,
    /** Pengaturan diubah owner di HP (lewat PIN) dan belum terkirim ke cloud. */
    val settingsDirty: Boolean = false,
    /** Versi cloud yang menjadi dasar perubahan itu (dikirim sebagai p_base_version). */
    val dirtyBaseVersion: Long = 0,
    /** Naik setiap kali pengaturan diubah di HP; perubahan baru selagi mengirim tidak ikut "dianggap terkirim". */
    val dirtySeq: Long = 0,
    /** Terakhir sinkron berhasil (epoch ms); 0 = belum pernah. */
    val lastSyncAt: Long = 0,
    val lastMessage: String = "",
    val lastMessageAt: Long = 0,
    val lastMessageIsError: Boolean = false,
    /**
     * Daftar karyawan HP dan dashboard sudah dicocokkan sejak HP dipasangkan. Selama false, sinkron pertama
     * memeriksa nomor karyawan dulu sebelum mengirim presensi (lihat CloudSync), supaya presensi tidak
     * tercatat atas nama orang lain bila dashboard sudah punya karyawan dengan nomor yang sama.
     */
    val employeesReconciled: Boolean = false,
)

/** Pengaturan + versi cloud yang sudah diterapkan, dari satu snapshot DataStore yang sama. */
data class SettingsSnapshot(val settings: AppSettings, val appliedVersion: Long)

class SettingsStore(private val context: Context) {
    private object Keys {
        val WORK_START_F = stringPreferencesKey("work_start_f")
        val TOLERANCE_F = intPreferencesKey("tolerance_f")
        val WORK_START_M = stringPreferencesKey("work_start_m")
        val TOLERANCE_M = intPreferencesKey("tolerance_m")
        val BONUS = longPreferencesKey("monthly_bonus")
        val LATE_DEDUCTION = longPreferencesKey("late_deduction")
        val DEDUCT_ABSENT = booleanPreferencesKey("deduct_absent")
        val WORK_DAYS = stringPreferencesKey("work_days") // "1,2,3,4,5,6"
        val STORE_LAT = doublePreferencesKey("store_lat")
        val STORE_LON = doublePreferencesKey("store_lon")
        val RADIUS = intPreferencesKey("radius_meters")
        val REQUIRE_LOCATION = booleanPreferencesKey("require_location")
        val REQUIRE_BIOMETRIC = booleanPreferencesKey("require_biometric")
        val PHOTO_RETENTION_DAYS = intPreferencesKey("photo_retention_days")

        // --- Cloud (Supabase, dashboard owner) ---
        val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        val CLOUD_DEVICE_KEY = stringPreferencesKey("cloud_device_key")
        val CLOUD_DEVICE_LABEL = stringPreferencesKey("cloud_device_label")
        val CLOUD_SITE_NAME = stringPreferencesKey("cloud_site_name")
        val CLOUD_SETTINGS_VERSION = longPreferencesKey("cloud_settings_version")
        val CLOUD_SETTINGS_DIRTY = booleanPreferencesKey("cloud_settings_dirty")
        val CLOUD_SETTINGS_BASE = longPreferencesKey("cloud_settings_base")
        val CLOUD_SETTINGS_SEQ = longPreferencesKey("cloud_settings_seq")
        val CLOUD_LAST_SYNC = longPreferencesKey("cloud_last_sync")
        val CLOUD_LAST_MESSAGE = stringPreferencesKey("cloud_last_message")
        val CLOUD_LAST_MESSAGE_AT = longPreferencesKey("cloud_last_message_at")
        val CLOUD_LAST_ERROR = booleanPreferencesKey("cloud_last_error")
        val CLOUD_EMPLOYEES_OK = booleanPreferencesKey("cloud_employees_reconciled")

        // --- Sisa konfigurasi Firebase lama (dihapus; termasuk password yang dulu disimpan) ---
        val LEGACY_PROJECT = stringPreferencesKey("cloud_project_id")
        val LEGACY_APP_ID = stringPreferencesKey("cloud_app_id")
        val LEGACY_API_KEY = stringPreferencesKey("cloud_api_key")
        val LEGACY_EMAIL = stringPreferencesKey("cloud_email")
        val LEGACY_PASSWORD = stringPreferencesKey("cloud_password")

        val PIN_HASH = stringPreferencesKey("owner_pin_hash")
        val PIN_SALT = stringPreferencesKey("owner_pin_salt")
    }

    // ---------- Cloud (dashboard owner) ----------

    val cloud: Flow<CloudConfig> = context.dataStore.data.map { p -> p.toCloudConfig() }

    suspend fun currentCloud(): CloudConfig = cloud.first()

    val cloudState: Flow<CloudState> = context.dataStore.data.map { p -> p.toCloudState() }

    suspend fun currentCloudState(): CloudState = cloudState.first()

    suspend fun saveCloud(c: CloudConfig) {
        context.dataStore.edit { p ->
            p[Keys.CLOUD_ENABLED] = c.enabled
            p[Keys.CLOUD_DEVICE_KEY] = c.deviceKey.trim()
            p[Keys.CLOUD_DEVICE_LABEL] = c.deviceLabel.trim()
            p[Keys.CLOUD_SITE_NAME] = c.siteName.trim()
            p.removeLegacyFirebase()
        }
    }

    /**
     * HP baru saja dipasangkan (device_pair berhasil): simpan kunci, aktifkan sinkron, dan mulai dari nol
     * (versi pengaturan 0 = pengaturan cloud diterapkan pada sinkron pertama; bila cloud belum punya, pengaturan HP dikirim).
     */
    suspend fun linkCloud(deviceKey: String, deviceLabel: String, siteName: String, message: String) {
        context.dataStore.edit { p ->
            p[Keys.CLOUD_ENABLED] = true
            p[Keys.CLOUD_DEVICE_KEY] = deviceKey.trim()
            p[Keys.CLOUD_DEVICE_LABEL] = deviceLabel.trim()
            p[Keys.CLOUD_SITE_NAME] = siteName.trim()
            p[Keys.CLOUD_SETTINGS_VERSION] = 0L
            p[Keys.CLOUD_SETTINGS_DIRTY] = false
            p[Keys.CLOUD_SETTINGS_BASE] = 0L
            p[Keys.CLOUD_EMPLOYEES_OK] = false
            p.putMessage(message, isError = false)
            p.removeLegacyFirebase()
        }
    }

    suspend fun setEmployeesReconciled() {
        context.dataStore.edit { p -> p[Keys.CLOUD_EMPLOYEES_OK] = true }
    }

    /** Putuskan HP ini: kunci dihapus dari HP, sinkron berhenti. Nama HP disimpan untuk menghubungkan ulang. */
    suspend fun unlinkCloud(message: String, isError: Boolean) {
        context.dataStore.edit { p ->
            p[Keys.CLOUD_ENABLED] = false
            p.remove(Keys.CLOUD_DEVICE_KEY)
            p.remove(Keys.CLOUD_SITE_NAME)
            p[Keys.CLOUD_SETTINGS_DIRTY] = false
            p.putMessage(message, isError)
            p.removeLegacyFirebase()
        }
    }

    /** Hapus sisa konfigurasi Firebase lama (dipanggil saat aplikasi dibuka; tidak menulis apa pun bila sudah bersih). */
    suspend fun cleanupLegacyCloud() {
        val p = context.dataStore.data.first()
        val hasLegacy = p[Keys.LEGACY_PROJECT] != null || p[Keys.LEGACY_APP_ID] != null || p[Keys.LEGACY_API_KEY] != null ||
            p[Keys.LEGACY_EMAIL] != null || p[Keys.LEGACY_PASSWORD] != null
        if (!hasLegacy) return
        context.dataStore.edit { m ->
            m.removeLegacyFirebase()
            if (m[Keys.CLOUD_DEVICE_KEY].isNullOrBlank()) m[Keys.CLOUD_ENABLED] = false
        }
    }

    /** Catat hasil sinkron berhasil. [message] null = tidak ada yang baru (pesan galat lama diganti "Semua sudah tersinkron"). */
    suspend fun recordSyncSuccess(at: Long, message: String?) {
        context.dataStore.edit { p ->
            p[Keys.CLOUD_LAST_SYNC] = at
            when {
                message != null -> p.putMessage(message, isError = false, at = at)
                p[Keys.CLOUD_LAST_ERROR] == true || p[Keys.CLOUD_LAST_MESSAGE].isNullOrBlank() ->
                    p.putMessage("Semua sudah tersinkron", isError = false, at = at)
            }
        }
    }

    suspend fun recordMessage(message: String, isError: Boolean) {
        context.dataStore.edit { p -> p.putMessage(message, isError) }
    }

    suspend fun setAppliedSettingsVersion(version: Long) {
        context.dataStore.edit { p -> p[Keys.CLOUD_SETTINGS_VERSION] = version }
    }

    /**
     * Pengaturan cloud versi [version] diterapkan ke HP (TANPA menandai "diubah di HP").
     * Tidak dilakukan bila owner baru saja mengubah pengaturan di HP dan belum terkirim; mengembalikan false.
     */
    suspend fun applyCloudSettings(s: AppSettings, version: Long): Boolean {
        var applied = false
        context.dataStore.edit { p ->
            if (p[Keys.CLOUD_SETTINGS_DIRTY] == true) return@edit
            p.writeSettings(s)
            p[Keys.CLOUD_SETTINGS_VERSION] = version
            applied = true
        }
        return applied
    }

    /** device_save_settings ok: cloud sekarang versi [version]. Tanda "diubah" dihapus bila tidak ada perubahan baru sejak [seq]. */
    suspend fun settingsPushed(version: Long, seq: Long) {
        context.dataStore.edit { p ->
            p[Keys.CLOUD_SETTINGS_VERSION] = version
            if ((p[Keys.CLOUD_SETTINGS_SEQ] ?: 0L) == seq) {
                p[Keys.CLOUD_SETTINGS_DIRTY] = false
            } else {
                p[Keys.CLOUD_SETTINGS_BASE] = version // ada perubahan lagi selagi mengirim: kirim ulang di atas versi baru
            }
        }
    }

    /** device_save_settings ok:false (cloud lebih baru) atau ditolak: pengaturan HP berhenti dikirim, versi cloud yang dipakai. */
    suspend fun settingsPushRejected(seq: Long) {
        context.dataStore.edit { p ->
            if ((p[Keys.CLOUD_SETTINGS_SEQ] ?: 0L) == seq) p[Keys.CLOUD_SETTINGS_DIRTY] = false
        }
    }

    /** Hari libur diubah owner di HP: tandai pengaturan perlu dikirim (bila HP tersambung). */
    suspend fun markSettingsDirtyIfLinked(): Boolean {
        var linked = false
        context.dataStore.edit { p ->
            linked = p.isCloudLinked()
            if (linked) p.markDirty()
        }
        return linked
    }

    // ---------- PIN owner (pengaman tab Pengaturan) ----------

    /** true jika owner sudah membuat PIN. */
    val hasOwnerPin: Flow<Boolean> = context.dataStore.data.map { it[Keys.PIN_HASH] != null }

    suspend fun setOwnerPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        context.dataStore.edit { p ->
            p[Keys.PIN_SALT] = salt
            p[Keys.PIN_HASH] = hashPin(salt, pin)
        }
    }

    suspend fun verifyOwnerPin(pin: String): Boolean {
        val p = context.dataStore.data.first()
        val salt = p[Keys.PIN_SALT] ?: return false
        val hash = p[Keys.PIN_HASH] ?: return false
        return hashPin(salt, pin) == hash
    }

    private fun hashPin(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    /** Pengaturan + versi cloud dari snapshot yang sama (layar Pengaturan memuat ulang form saat versi berubah). */
    val settingsSnapshot: Flow<SettingsSnapshot> = context.dataStore.data.map { p ->
        SettingsSnapshot(p.toSettings(), p[Keys.CLOUD_SETTINGS_VERSION] ?: 0L)
    }

    suspend fun current(): AppSettings = settings.first()

    /** Simpan pengaturan di HP saja (tanpa urusan cloud). */
    suspend fun save(s: AppSettings) {
        context.dataStore.edit { p -> p.writeSettings(s) }
    }

    /**
     * Simpan pengaturan yang diubah owner di HP. Bila HP tersambung ke dashboard, sekaligus (atomik) ditandai
     * "belum terkirim" dengan dasar versi cloud yang sedang diterapkan. Mengembalikan true bila tersambung.
     */
    suspend fun saveByOwner(s: AppSettings): Boolean {
        var linked = false
        context.dataStore.edit { p ->
            p.writeSettings(s)
            linked = p.isCloudLinked()
            if (linked) p.markDirty()
        }
        return linked
    }

    private fun Preferences.isCloudLinked(): Boolean =
        this[Keys.CLOUD_ENABLED] == true && !this[Keys.CLOUD_DEVICE_KEY].isNullOrBlank()

    private fun MutablePreferences.markDirty() {
        this[Keys.CLOUD_SETTINGS_DIRTY] = true
        this[Keys.CLOUD_SETTINGS_BASE] = this[Keys.CLOUD_SETTINGS_VERSION] ?: 0L
        this[Keys.CLOUD_SETTINGS_SEQ] = (this[Keys.CLOUD_SETTINGS_SEQ] ?: 0L) + 1
    }

    private fun MutablePreferences.putMessage(message: String, isError: Boolean, at: Long = System.currentTimeMillis()) {
        this[Keys.CLOUD_LAST_MESSAGE] = message
        this[Keys.CLOUD_LAST_MESSAGE_AT] = at
        this[Keys.CLOUD_LAST_ERROR] = isError
    }

    private fun MutablePreferences.removeLegacyFirebase() {
        remove(Keys.LEGACY_PROJECT)
        remove(Keys.LEGACY_APP_ID)
        remove(Keys.LEGACY_API_KEY)
        remove(Keys.LEGACY_EMAIL)
        remove(Keys.LEGACY_PASSWORD)
    }

    private fun MutablePreferences.writeSettings(s: AppSettings) {
        this[Keys.WORK_START_F] = s.workStartFemale
        this[Keys.TOLERANCE_F] = s.toleranceFemale
        this[Keys.WORK_START_M] = s.workStartMale
        this[Keys.TOLERANCE_M] = s.toleranceMale
        this[Keys.BONUS] = s.monthlyBonus
        this[Keys.LATE_DEDUCTION] = s.lateDeduction
        this[Keys.DEDUCT_ABSENT] = s.deductAbsent
        this[Keys.WORK_DAYS] = s.workDays.map { it.value }.sorted().joinToString(",")
        if (s.storeLat != null && s.storeLon != null) {
            this[Keys.STORE_LAT] = s.storeLat
            this[Keys.STORE_LON] = s.storeLon
        } else {
            remove(Keys.STORE_LAT)
            remove(Keys.STORE_LON)
        }
        this[Keys.RADIUS] = s.radiusMeters
        this[Keys.REQUIRE_LOCATION] = s.requireLocation
        this[Keys.REQUIRE_BIOMETRIC] = s.requireBiometric
        this[Keys.PHOTO_RETENTION_DAYS] = s.photoRetentionDays
    }

    private fun Preferences.toCloudConfig(): CloudConfig = CloudConfig(
        enabled = this[Keys.CLOUD_ENABLED] ?: false,
        deviceKey = this[Keys.CLOUD_DEVICE_KEY] ?: "",
        deviceLabel = this[Keys.CLOUD_DEVICE_LABEL] ?: "",
        siteName = this[Keys.CLOUD_SITE_NAME] ?: "",
    )

    private fun Preferences.toCloudState(): CloudState = CloudState(
        appliedVersion = this[Keys.CLOUD_SETTINGS_VERSION] ?: 0L,
        settingsDirty = this[Keys.CLOUD_SETTINGS_DIRTY] ?: false,
        dirtyBaseVersion = this[Keys.CLOUD_SETTINGS_BASE] ?: 0L,
        dirtySeq = this[Keys.CLOUD_SETTINGS_SEQ] ?: 0L,
        lastSyncAt = this[Keys.CLOUD_LAST_SYNC] ?: 0L,
        lastMessage = this[Keys.CLOUD_LAST_MESSAGE] ?: "",
        lastMessageAt = this[Keys.CLOUD_LAST_MESSAGE_AT] ?: 0L,
        lastMessageIsError = this[Keys.CLOUD_LAST_ERROR] ?: false,
        employeesReconciled = this[Keys.CLOUD_EMPLOYEES_OK] ?: false,
    )

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        val days = this[Keys.WORK_DAYS]
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in 1..7 }
            ?.map { DayOfWeek.of(it) }
            ?.toSet()
        return AppSettings(
            workStartFemale = this[Keys.WORK_START_F] ?: d.workStartFemale,
            toleranceFemale = this[Keys.TOLERANCE_F] ?: d.toleranceFemale,
            workStartMale = this[Keys.WORK_START_M] ?: d.workStartMale,
            toleranceMale = this[Keys.TOLERANCE_M] ?: d.toleranceMale,
            monthlyBonus = this[Keys.BONUS] ?: d.monthlyBonus,
            lateDeduction = this[Keys.LATE_DEDUCTION] ?: d.lateDeduction,
            deductAbsent = this[Keys.DEDUCT_ABSENT] ?: d.deductAbsent,
            workDays = days ?: d.workDays,
            storeLat = this[Keys.STORE_LAT],
            storeLon = this[Keys.STORE_LON],
            radiusMeters = this[Keys.RADIUS] ?: d.radiusMeters,
            requireLocation = this[Keys.REQUIRE_LOCATION] ?: d.requireLocation,
            requireBiometric = this[Keys.REQUIRE_BIOMETRIC] ?: d.requireBiometric,
            photoRetentionDays = this[Keys.PHOTO_RETENTION_DAYS] ?: d.photoRetentionDays,
        )
    }
}
