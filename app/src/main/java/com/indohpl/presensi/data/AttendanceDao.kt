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

    @Query("SELECT * FROM attendance WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AttendanceRecord?

    @Update
    suspend fun update(record: AttendanceRecord)

    /** Catatan telat dan yang sudah dianulir dalam rentang tanggal (untuk daftar "Anulir telat" owner). */
    @Query("SELECT * FROM attendance WHERE (status = 'TELAT' OR annulledLateMinutes IS NOT NULL) AND date BETWEEN :from AND :to ORDER BY date DESC, timestamp")
    fun observeLateOrAnnulled(from: String, to: String): Flow<List<AttendanceRecord>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: AttendanceRecord): Long

    @Delete
    suspend fun delete(record: AttendanceRecord)

    /** Catatan sebelum tanggal tertentu (untuk pembersihan otomatis). */
    @Query("SELECT * FROM attendance WHERE date < :date")
    suspend fun getBefore(date: String): List<AttendanceRecord>

    @Query("DELETE FROM attendance WHERE date < :date")
    suspend fun deleteBefore(date: String): Int

    @Query("SELECT MIN(date) FROM attendance")
    fun observeOldestDate(): Flow<String?>

    @Query("SELECT MAX(date) FROM attendance")
    suspend fun newestDate(): String?

    @Query("SELECT MIN(date) FROM attendance")
    suspend fun oldestDate(): String?

    @Query("SELECT COUNT(*) FROM attendance")
    suspend fun count(): Int

    /** Lepas tautan foto yang filenya sudah hilang (catatan presensi tetap ada). */
    @Query("UPDATE attendance SET photoPath = NULL WHERE id = :id")
    suspend fun clearPhotoPath(id: Long)

    /** Bulan (yyyy-MM) yang punya catatan sebelum tanggal tertentu. */
    @Query("SELECT DISTINCT substr(date, 1, 7) FROM attendance WHERE date < :date")
    suspend fun monthsBefore(date: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(record: AttendanceRecord): Long
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

    @Query("DELETE FROM holidays WHERE date < :date")
    suspend fun deleteBefore(date: String)
}
