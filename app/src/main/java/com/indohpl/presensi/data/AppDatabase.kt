package com.indohpl.presensi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Employee::class, AttendanceRecord::class, Holiday::class],
    version = 4,
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

        /** v2 -> v3: penanda sinkron cloud + antrean hapus. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attendance ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS cloud_deletions (docId TEXT NOT NULL, PRIMARY KEY(docId))")
            }
        }

        /** v3 -> v4: fitur cloud dihapus; buang kolom synced dan tabel cloud_deletions. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS cloud_deletions")
                db.execSQL(
                    "CREATE TABLE attendance_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, employeeId INTEGER NOT NULL, date TEXT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, timeIn TEXT NOT NULL, status TEXT NOT NULL, lateMinutes INTEGER NOT NULL, " +
                        "photoPath TEXT, note TEXT NOT NULL, latitude REAL, longitude REAL, accuracy REAL, distanceMeters INTEGER, " +
                        "inLocation INTEGER NOT NULL DEFAULT 0, locationNote TEXT NOT NULL DEFAULT '')",
                )
                db.execSQL(
                    "INSERT INTO attendance_new (id, employeeId, date, timestamp, timeIn, status, lateMinutes, photoPath, note, " +
                        "latitude, longitude, accuracy, distanceMeters, inLocation, locationNote) " +
                        "SELECT id, employeeId, date, timestamp, timeIn, status, lateMinutes, photoPath, note, " +
                        "latitude, longitude, accuracy, distanceMeters, inLocation, locationNote FROM attendance",
                )
                db.execSQL("DROP TABLE attendance")
                db.execSQL("ALTER TABLE attendance_new RENAME TO attendance")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_attendance_employeeId_date ON attendance (employeeId, date)")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "presensi.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
    }
}
