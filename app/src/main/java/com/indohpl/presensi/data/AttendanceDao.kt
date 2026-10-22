package com.indohpl.presensi.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EmployeeDao {
    @Query("SELECT * FROM employees ORDER BY id")
    fun observeAll(): Flow<List<Employee>>

    @Query("SELECT * FROM employees ORDER BY id")
    suspend fun getAll(): List<Employee>

    @Query("SELECT * FROM employees WHERE id = :id")
    suspend fun getById(id: Long): Employee?

    @Query("SELECT COUNT(*) FROM employees")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(employees: List<Employee>)

    @Update
    suspend fun update(employee: Employee)
}

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance WHERE date = :date ORDER BY timestamp")
    fun observeByDate(date: String): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM attendance WHERE date BETWEEN :from AND :to ORDER BY date, timestamp")
    fun observeBetween(from: String, to: String): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM attendance WHERE date BETWEEN :from AND :to ORDER BY date, timestamp")
    suspend fun getBetween(from: String, to: String): List<AttendanceRecord>

    @Query("SELECT * FROM attendance ORDER BY date, timestamp")
    suspend fun getAll(): List<AttendanceRecord>

    @Query("SELECT * FROM attendance WHERE employeeId = :employeeId AND date = :date LIMIT 1")
    suspend fun find(employeeId: Long, date: String): AttendanceRecord?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: AttendanceRecord): Long

    @Delete
    suspend fun delete(record: AttendanceRecord)

    @Query("SELECT * FROM attendance WHERE synced = 0 ORDER BY timestamp")
    suspend fun getUnsynced(): List<AttendanceRecord>

    /** Nomor karyawan yang punya catatan presensi di HP ini (pemeriksaan karyawan saat pertama tersambung). */
    @Query("SELECT DISTINCT employeeId FROM attendance")
    suspend fun employeeIdsWithRecords(): List<Long>

    @Query("SELECT COUNT(*) FROM attendance WHERE synced = 0")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM attendance WHERE synced = 0")
    suspend fun countUnsynced(): Int

    /** Tandai terkirim ke cloud. Dipanggil per kiriman (maks 500 id, di bawah batas variabel SQLite). */
    @Query("UPDATE attendance SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("UPDATE attendance SET synced = 0")
    suspend fun markAllUnsynced()

    // --- Pembersihan foto lama (hanya kolom photoPath yang diubah) ---

    @Query("SELECT * FROM attendance WHERE photoPath IS NOT NULL")
    suspend fun getWithPhoto(): List<AttendanceRecord>

    @Query("SELECT * FROM attendance WHERE photoPath IS NOT NULL AND date < :beforeDate")
    suspend fun getWithPhotoBefore(beforeDate: String): List<AttendanceRecord>

    @Query("UPDATE attendance SET photoPath = NULL WHERE id = :id")
    suspend fun clearPhotoPath(id: Long)
}

@Dao
interface CloudDeletionDao {
    @Query("SELECT * FROM cloud_deletions")
    suspend fun getAll(): List<CloudDeletion>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(d: CloudDeletion)

    @Delete
    suspend fun delete(d: CloudDeletion)
}

@Dao
interface HolidayDao {
    @Query("SELECT * FROM holidays ORDER BY date")
    fun observeAll(): Flow<List<Holiday>>

    @Query("SELECT * FROM holidays ORDER BY date")
    suspend fun getAll(): List<Holiday>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(holiday: Holiday)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(holidays: List<Holiday>)

    @Delete
    suspend fun delete(holiday: Holiday)

    @Query("DELETE FROM holidays")
    suspend fun deleteAll()
}
