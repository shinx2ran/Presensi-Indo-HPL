package com.indohpl.presensi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Employee::class, AttendanceRecord::class, Holiday::class, CloudDeletion::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun employeeDao(): EmployeeDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun holidayDao(): HolidayDao
    abstract fun cloudDeletionDao(): CloudDeletionDao

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

        /** v2 -> v3: penanda sinkron cloud + antrean hapus. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attendance ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS cloud_deletions (docId TEXT NOT NULL, PRIMARY KEY(docId))")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "presensi.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
