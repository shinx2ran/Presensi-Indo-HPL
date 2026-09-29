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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Pengaturan aplikasi yang bisa diubah kasir dari layar Pengaturan. */
data class AppSettings(
    val workStart: String = "08:00",          // jam masuk (HH:mm)
    val toleranceMinutes: Int = 0,            // toleransi menit sebelum dianggap telat
    val monthlyBonus: Long = 250_000,         // uang rajin penuh per bulan (Rp)
    val lateDeduction: Long = 0,              // potongan per hari telat; 0 = otomatis (bonus / hari kerja)
    val deductAbsent: Boolean = true,         // potong juga jika alpa (tidak hadir tanpa izin)
    val workDays: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
    ),
    // --- Lokasi toko Indo HPL ---
    val storeLat: Double? = null,             // null = titik toko belum diatur
    val storeLon: Double? = null,
    val radiusMeters: Int = 100,              // radius toleransi dari titik toko
    val requireLocation: Boolean = true,      // true = absen ditolak jika di luar radius / GPS tidak ada
) {
    val hasStoreLocation: Boolean get() = storeLat != null && storeLon != null

    fun bonusConfig() = BonusConfig(
        monthlyBonus = monthlyBonus,
        lateDeductionOverride = lateDeduction,
        deductAbsent = deductAbsent,
    )
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val WORK_START = stringPreferencesKey("work_start")
        val TOLERANCE = intPreferencesKey("tolerance_minutes")
        val BONUS = longPreferencesKey("monthly_bonus")
        val LATE_DEDUCTION = longPreferencesKey("late_deduction")
        val DEDUCT_ABSENT = booleanPreferencesKey("deduct_absent")
        val WORK_DAYS = stringPreferencesKey("work_days") // "1,2,3,4,5,6"
        val STORE_LAT = doublePreferencesKey("store_lat")
        val STORE_LON = doublePreferencesKey("store_lon")
        val RADIUS = intPreferencesKey("radius_meters")
        val REQUIRE_LOCATION = booleanPreferencesKey("require_location")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun save(s: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.WORK_START] = s.workStart
            p[Keys.TOLERANCE] = s.toleranceMinutes
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
            workStart = this[Keys.WORK_START] ?: d.workStart,
            toleranceMinutes = this[Keys.TOLERANCE] ?: d.toleranceMinutes,
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
