package com.indohpl.presensi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Employee::class, AttendanceRecord::class, Holiday::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun employeeDao(): EmployeeDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun holidayDao(): HolidayDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "presensi.db",
                ).build().also { instance = it }
            }
    }
}
