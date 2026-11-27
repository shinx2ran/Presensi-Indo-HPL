package com.indohpl.presensi.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SalesMonthDao {
    @Query("SELECT * FROM sales_months WHERE ym = :ym LIMIT 1")
    fun observe(ym: String): Flow<SalesMonth?>

    @Query("SELECT * FROM sales_months WHERE ym = :ym LIMIT 1")
    suspend fun get(ym: String): SalesMonth?

    @Query("SELECT * FROM sales_months ORDER BY ym")
    suspend fun getAll(): List<SalesMonth>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(month: SalesMonth)

    /** Untuk pulihkan: bulan yang sudah ada di HP tidak ditimpa. -1 bila sudah ada. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(month: SalesMonth): Long
}

@Dao
interface BonusEventDao {
    @Query("SELECT * FROM bonus_events WHERE date BETWEEN :from AND :to ORDER BY date, createdAt")
    fun observeBetween(from: String, to: String): Flow<List<BonusEvent>>

    @Query("SELECT * FROM bonus_events WHERE date BETWEEN :from AND :to ORDER BY date, createdAt")
    suspend fun getBetween(from: String, to: String): List<BonusEvent>

    @Query("SELECT * FROM bonus_events ORDER BY date, createdAt")
    suspend fun getAll(): List<BonusEvent>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: BonusEvent): Long

    /** Untuk pulihkan: catatan yang sama (karyawan, tanggal, jenis, waktu dibuat) tidak digandakan. -1 bila sudah ada. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(event: BonusEvent): Long

    @Delete
    suspend fun delete(event: BonusEvent)

    @Query("DELETE FROM bonus_events WHERE date < :date")
    suspend fun deleteBefore(date: String): Int
}
