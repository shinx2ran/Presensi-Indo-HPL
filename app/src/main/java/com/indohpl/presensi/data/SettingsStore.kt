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
    val monthlyBonus: Long = 350_000,         // uang rajin maksimal per bulan (Rp)
    val perDayAmount: Long = 0,               // tambahan per hari tepat waktu; 0 = otomatis (uang rajin / hari kerja)
    val workDays: Set<DayOfWeek> = DayOfWeek.values().toSet(), // Senin–Minggu
    // --- Lokasi toko Indo HPL ---
    val storeLat: Double? = null,             // null = titik toko belum diatur
    val storeLon: Double? = null,
    val radiusMeters: Int = 100,              // radius toleransi dari titik toko
    val requireLocation: Boolean = true,      // true = absen ditolak jika di luar radius / GPS tidak ada
    // --- Pembersihan otomatis ---
    val retentionMonths: Int = 2,             // data lebih lama dari N bulan dihapus otomatis (beserta foto)
) {
    val hasStoreLocation: Boolean get() = storeLat != null && storeLon != null

    fun workStartFor(gender: String): String = if (gender == "P") workStartFemale else workStartMale
    fun toleranceFor(gender: String): Int = if (gender == "P") toleranceFemale else toleranceMale

    /** Ringkasan aturan untuk ditampilkan / diekspor. */
    fun rulesText(): String =
        "Perempuan $workStartFemale (toleransi $toleranceFemale mnt) · Laki-laki $workStartMale (toleransi $toleranceMale mnt)"

    fun bonusConfig() = BonusConfig(
        monthlyBonus = monthlyBonus,
        perDayOverride = perDayAmount,
    )
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val WORK_START_F = stringPreferencesKey("work_start_f")
        val TOLERANCE_F = intPreferencesKey("tolerance_f")
        val WORK_START_M = stringPreferencesKey("work_start_m")
        val TOLERANCE_M = intPreferencesKey("tolerance_m")
        val BONUS = longPreferencesKey("monthly_bonus")
        // Arti lama "potongan per hari telat". Tidak dibaca lagi (artinya berbeda) dan dihapus saat menyimpan.
        val LATE_DEDUCTION = longPreferencesKey("late_deduction")
        val PER_DAY_AMOUNT = longPreferencesKey("per_day_amount")
        val DEDUCT_ABSENT = booleanPreferencesKey("deduct_absent")
        val WORK_DAYS = stringPreferencesKey("work_days") // "1,2,3,4,5,6"
        val STORE_LAT = doublePreferencesKey("store_lat")
        val STORE_LON = doublePreferencesKey("store_lon")
        val RADIUS = intPreferencesKey("radius_meters")
        val REQUIRE_LOCATION = booleanPreferencesKey("require_location")
        val RETENTION = intPreferencesKey("retention_months")
        val PIN_HASH = stringPreferencesKey("owner_pin_hash")
        val PIN_SALT = stringPreferencesKey("owner_pin_salt")
        val LAST_SEEN_VERSION = longPreferencesKey("last_seen_version_code")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val LAST_BACKUP_FILE = stringPreferencesKey("last_backup_file")
        val LAST_CLEANUP_AT = longPreferencesKey("last_cleanup_at")
    }

    // ---------- Catatan versi, cadangan, dan pembersihan (dipakai Inspector) ----------

    /** versionCode yang terakhir dibuka aplikasi; null = belum pernah tercatat. */
    suspend fun lastSeenVersionCode(): Long? = context.dataStore.data.first()[Keys.LAST_SEEN_VERSION]

    suspend fun setLastSeenVersionCode(v: Long) {
        context.dataStore.edit { it[Keys.LAST_SEEN_VERSION] = v }
    }

    /** Cadangan terakhir yang berhasil: (waktu epoch millis, nama file); null = belum pernah. */
    val lastBackup: Flow<Pair<Long, String>?> = context.dataStore.data.map { p ->
        val at = p[Keys.LAST_BACKUP_AT]
        val file = p[Keys.LAST_BACKUP_FILE]
        if (at != null && file != null) at to file else null
    }

    suspend fun setLastBackup(at: Long, file: String) {
        context.dataStore.edit { p ->
            p[Keys.LAST_BACKUP_AT] = at
            p[Keys.LAST_BACKUP_FILE] = file
        }
    }

    /** Waktu (epoch millis) pembersihan data lama terakhir dijalankan; null = belum pernah. */
    suspend fun lastCleanupAt(): Long? = context.dataStore.data.first()[Keys.LAST_CLEANUP_AT]

    suspend fun setLastCleanupAt(at: Long) {
        context.dataStore.edit { it[Keys.LAST_CLEANUP_AT] = at }
    }

    // ---------- Migrasi sekali jalan ----------

    /**
     * Uang rajin naik dari 250.000 ke 350.000 (permintaan owner, November 2026). HP yang sudah menyimpan nilai bawaan
     * lama 250.000 dinaikkan sekali; nilai lain yang sengaja diisi owner tidak diubah.
     */
    suspend fun migrateBonusTo350() {
        val done = booleanPreferencesKey("migrated_bonus_350")
        context.dataStore.edit { p ->
            if (p[done] == true) return@edit
            if (p[Keys.BONUS] == 250_000L) p[Keys.BONUS] = 350_000L
            p[done] = true
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
            p[Keys.PER_DAY_AMOUNT] = s.perDayAmount
            p.remove(Keys.LATE_DEDUCTION)
            p.remove(Keys.DEDUCT_ABSENT) // aturan lama; bolos kini selalu tidak menambah
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
            p[Keys.RETENTION] = s.retentionMonths
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
            perDayAmount = this[Keys.PER_DAY_AMOUNT] ?: d.perDayAmount,
            workDays = days ?: d.workDays,
            storeLat = this[Keys.STORE_LAT],
            storeLon = this[Keys.STORE_LON],
            radiusMeters = this[Keys.RADIUS] ?: d.radiusMeters,
            requireLocation = this[Keys.REQUIRE_LOCATION] ?: d.requireLocation,
            retentionMonths = this[Keys.RETENTION] ?: d.retentionMonths,
        )
    }
}
