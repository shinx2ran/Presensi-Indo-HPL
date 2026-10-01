package com.indohpl.presensi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.indohpl.presensi.domain.BonusConfig
import java.time.DayOfWeek
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Pengaturan aplikasi yang bisa diubah kasir dari layar Pengaturan. */
data class AppSettings(
    // Jam masuk & toleransi dibedakan per gender (P = perempuan, L = laki-laki).
    val workStartFemale: String = "07:45",
    val toleranceFemale: Int = 10,
    val workStartMale: String = "08:00",
    val toleranceMale: Int = 5,
    val monthlyBonus: Long = 250_000,         // uang rajin penuh per bulan (Rp)
    val lateDeduction: Long = 0,              // potongan per hari telat; 0 = otomatis (bonus / hari kerja)
    val deductAbsent: Boolean = true,         // potong juga jika alpa (tidak hadir tanpa izin)
    val workDays: Set<DayOfWeek> = DayOfWeek.values().toSet(), // Senin–Minggu
    // --- Lokasi toko Indo HPL ---
    val storeLat: Double? = null,             // null = titik toko belum diatur
    val storeLon: Double? = null,
    val radiusMeters: Int = 100,              // radius toleransi dari titik toko
    val requireLocation: Boolean = true,      // true = absen ditolak jika di luar radius / GPS tidak ada
) {
    val hasStoreLocation: Boolean get() = storeLat != null && storeLon != null

    fun workStartFor(gender: String): String = if (gender == "P") workStartFemale else workStartMale
    fun toleranceFor(gender: String): Int = if (gender == "P") toleranceFemale else toleranceMale

    /** Ringkasan aturan untuk ditampilkan / diekspor. */
    fun rulesText(): String =
        "Perempuan $workStartFemale (toleransi $toleranceFemale mnt) · Laki-laki $workStartMale (toleransi $toleranceMale mnt)"

    fun bonusConfig() = BonusConfig(
        monthlyBonus = monthlyBonus,
        lateDeductionOverride = lateDeduction,
        deductAbsent = deductAbsent,
    )
}

/** Konfigurasi Firebase yang dimasukkan owner lewat Pengaturan (tanpa file google-services.json). */
data class CloudConfig(
    val enabled: Boolean = false,
    val projectId: String = "",
    val appId: String = "",       // mobilesdk_app_id, format 1:1234567890:android:abcdef
    val apiKey: String = "",      // current_key
    val email: String = "",       // akun login Firebase Authentication (email/password)
    val password: String = "",
) {
    val isComplete: Boolean
        get() = projectId.isNotBlank() && appId.isNotBlank() && apiKey.isNotBlank() && email.isNotBlank() && password.isNotBlank()
}

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
        val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        val CLOUD_PROJECT = stringPreferencesKey("cloud_project_id")
        val CLOUD_APP_ID = stringPreferencesKey("cloud_app_id")
        val CLOUD_API_KEY = stringPreferencesKey("cloud_api_key")
        val CLOUD_EMAIL = stringPreferencesKey("cloud_email")
        val CLOUD_PASSWORD = stringPreferencesKey("cloud_password")
        val PIN_HASH = stringPreferencesKey("owner_pin_hash")
        val PIN_SALT = stringPreferencesKey("owner_pin_salt")
    }

    // ---------- Cloud (Firebase) ----------

    val cloud: Flow<CloudConfig> = context.dataStore.data.map { p ->
        CloudConfig(
            enabled = p[Keys.CLOUD_ENABLED] ?: false,
            projectId = p[Keys.CLOUD_PROJECT] ?: "",
            appId = p[Keys.CLOUD_APP_ID] ?: "",
            apiKey = p[Keys.CLOUD_API_KEY] ?: "",
            email = p[Keys.CLOUD_EMAIL] ?: "",
            password = p[Keys.CLOUD_PASSWORD] ?: "",
        )
    }

    suspend fun currentCloud(): CloudConfig = cloud.first()

    suspend fun saveCloud(c: CloudConfig) {
        context.dataStore.edit { p ->
            p[Keys.CLOUD_ENABLED] = c.enabled
            p[Keys.CLOUD_PROJECT] = c.projectId.trim()
            p[Keys.CLOUD_APP_ID] = c.appId.trim()
            p[Keys.CLOUD_API_KEY] = c.apiKey.trim()
            p[Keys.CLOUD_EMAIL] = c.email.trim()
            p[Keys.CLOUD_PASSWORD] = c.password
        }
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

    suspend fun current(): AppSettings = settings.first()

    suspend fun save(s: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.WORK_START_F] = s.workStartFemale
            p[Keys.TOLERANCE_F] = s.toleranceFemale
            p[Keys.WORK_START_M] = s.workStartMale
            p[Keys.TOLERANCE_M] = s.toleranceMale
            p[Keys.BONUS] = s.monthlyBonus
            p[Keys.LATE_DEDUCTION] = s.lateDeduction
            p[Keys.DEDUCT_ABSENT] = s.deductAbsent
            p[Keys.WORK_DAYS] = s.workDays.map { it.value }.sorted().joinToString(",")
            if (s.storeLat != null && s.storeLon != null) {
                p[Keys.STORE_LAT] = s.storeLat
                p[Keys.STORE_LON] = s.storeLon
            } else {
                p.remove(Keys.STORE_LAT)
                p.remove(Keys.STORE_LON)
            }
            p[Keys.RADIUS] = s.radiusMeters
            p[Keys.REQUIRE_LOCATION] = s.requireLocation
        }
    }

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
        )
    }
}
