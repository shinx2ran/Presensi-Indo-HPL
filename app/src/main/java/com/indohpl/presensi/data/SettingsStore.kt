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
import com.indohpl.presensi.domain.BonusConfig
import com.indohpl.presensi.domain.PinCheck
import com.indohpl.presensi.domain.PinLockout
import com.indohpl.presensi.domain.SalesBonus
import com.indohpl.presensi.domain.SalesBonusConfig
import java.time.DayOfWeek
import java.time.YearMonth
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
        val PIN_FAILS = intPreferencesKey("owner_pin_fails")
        val PIN_LOCKED_UNTIL = longPreferencesKey("owner_pin_locked_until")
        val PIN_LOCK_ELAPSED_START = longPreferencesKey("owner_pin_lock_elapsed_start")
        val PIN_LOCK_ELAPSED_UNTIL = longPreferencesKey("owner_pin_lock_elapsed_until")
        val LAST_SEEN_VERSION = longPreferencesKey("last_seen_version_code")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val LAST_BACKUP_FILE = stringPreferencesKey("last_backup_file")
        val LAST_CLEANUP_AT = longPreferencesKey("last_cleanup_at")
        val SALES_TIERS = stringPreferencesKey("sales_bonus_tiers")
        val SALES_START = stringPreferencesKey("sales_bonus_start")
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

    // ---------- Bonus penjualan (target & bonus per orang) ----------

    /** Disimpan terpisah dari [AppSettings] supaya formulir aturan jam tidak pernah menimpanya. */
    val salesBonusConfig: Flow<SalesBonusConfig> = context.dataStore.data.map { p ->
        SalesBonusConfig(
            tiers = SalesBonus.decodeTiers(p[Keys.SALES_TIERS]),
            startMonth = p[Keys.SALES_START]?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: SalesBonus.DEFAULT_START,
        )
    }

    suspend fun saveSalesBonusConfig(c: SalesBonusConfig) {
        context.dataStore.edit { p ->
            p[Keys.SALES_TIERS] = SalesBonus.encodeTiers(c.sortedTiers.ifEmpty { SalesBonus.DEFAULT_TIERS })
            p[Keys.SALES_START] = c.startMonth.toString()
        }
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

    private fun pinClock() = PinLockout.Clock(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())

    private fun readLock(p: Preferences) = PinLockout.State(
        fails = p[Keys.PIN_FAILS] ?: 0,
        wallUntil = p[Keys.PIN_LOCKED_UNTIL] ?: 0L,
        elapsedStart = p[Keys.PIN_LOCK_ELAPSED_START] ?: 0L,
        elapsedUntil = p[Keys.PIN_LOCK_ELAPSED_UNTIL] ?: 0L,
    )

    private fun writeLock(p: MutablePreferences, s: PinLockout.State) {
        p[Keys.PIN_FAILS] = s.fails
        if (s.wallUntil > 0L) p[Keys.PIN_LOCKED_UNTIL] = s.wallUntil else p.remove(Keys.PIN_LOCKED_UNTIL)
        if (s.elapsedUntil > 0L) {
            p[Keys.PIN_LOCK_ELAPSED_START] = s.elapsedStart
            p[Keys.PIN_LOCK_ELAPSED_UNTIL] = s.elapsedUntil
        } else {
            p.remove(Keys.PIN_LOCK_ELAPSED_START)
            p.remove(Keys.PIN_LOCK_ELAPSED_UNTIL)
        }
    }

    /**
     * Sisa waktu kunci PIN (ms; 0 = tidak terkunci), untuk hitung mundur saat layar/dialog PIN dibuka.
     * Sekalian menyimpan state yang sudah dirapikan (mis. batas yang dipangkas setelah jam HP dimundurkan).
     */
    suspend fun ownerPinLockMsLeft(): Long {
        val now = pinClock()
        var left = 0L
        context.dataStore.edit { p ->
            val s = PinLockout.normalize(readLock(p), now)
            writeLock(p, s)
            left = PinLockout.remainingMs(s, now)
        }
        return left
    }

    /**
     * Cek PIN owner dengan kunci permanen (lihat [PinLockout]). Dipakai gerbang Pengaturan dan dialog PIN anulir.
     * DataStore.edit berjalan berurutan, jadi dua pengecekan bersamaan tetap dihitung satu per satu.
     */
    suspend fun checkOwnerPin(pin: String): PinCheck {
        val now = pinClock()
        var result = PinCheck(ok = false)
        context.dataStore.edit { p ->
            val salt = p[Keys.PIN_SALT]
            val hash = p[Keys.PIN_HASH]
            val correct = salt != null && hash != null && hashPin(salt, pin) == hash
            val (next, r) = PinLockout.attempt(readLock(p), correct, now)
            writeLock(p, next)
            result = r
        }
        if (result.lockedMsLeft > 0L) clearPinLockWhenExpired(result.lockedMsLeft)
        return result
    }

    private val pinCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pinCleanupJob: Job? = null

    /**
     * Hapus catatan kunci begitu habis, tanpa menunggu layar PIN dibuka. Kunci yang tercatat masih aktif saat HP
     * dinyalakan ulang akan dimulai ulang (lihat PinLockout), jadi kunci yang sudah habis jangan dibiarkan tercatat.
     * Usaha terbaik: bila proses aplikasi mati lebih dulu, pembersihan saat aplikasi dibuka lagi yang menangani.
     */
    private fun clearPinLockWhenExpired(firstWaitMs: Long) {
        pinCleanupJob?.cancel()
        pinCleanupJob = pinCleanupScope.launch {
            var wait = firstWaitMs
            while (wait > 0L) {
                delay(wait + 1_000L)
                wait = runCatching { ownerPinLockMsLeft() }.getOrDefault(0L)
            }
        }
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
