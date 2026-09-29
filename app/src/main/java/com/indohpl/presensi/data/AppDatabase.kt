package com.indohpl.presensi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Employee::class, AttendanceRecord::class, Holiday::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun employeeDao(): EmployeeDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun holidayDao(): HolidayDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v1 -> v2: kolom lokasi GPS pada tabel attendance. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attendance ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE attendance ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE attendance ADD COLUMN accuracy REAL")
                db.execSQL("ALTER TABLE attendance ADD COLUMN distanceMeters INTEGER")
                db.execSQL("ALTER TABLE attendance ADD COLUMN inLocation INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE attendance ADD COLUMN locationNote TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "presensi.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
