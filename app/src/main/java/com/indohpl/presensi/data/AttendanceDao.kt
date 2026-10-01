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

    @Query("SELECT COUNT(*) FROM attendance WHERE synced = 0")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("UPDATE attendance SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("UPDATE attendance SET synced = 0")
    suspend fun markAllUnsynced()
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

    @Delete
    suspend fun delete(holiday: Holiday)
}
