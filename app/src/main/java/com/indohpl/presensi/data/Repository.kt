package com.indohpl.presensi.data

import android.content.Context
import kotlinx.coroutines.sync.withLock
import androidx.room.withTransaction
import com.indohpl.presensi.data.backup.BackupCodec
import com.indohpl.presensi.data.backup.BackupData
import com.indohpl.presensi.data.backup.LocalBackup
import com.indohpl.presensi.domain.AnnulRule
import com.indohpl.presensi.domain.CsvImport
import com.indohpl.presensi.domain.GeoCheck
import com.indohpl.presensi.domain.Retention
import com.indohpl.presensi.domain.LateRule
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pintu tunggal ke database + pengaturan. Dibuat sekali di [com.indohpl.presensi.PresensiApp]. */
class Repository(context: Context) {
    private val appContext: Context = context.applicationContext
    private val db = AppDatabase.get(context)
    val settingsStore = SettingsStore(context)
    val employeeDao = db.employeeDao()
    val attendanceDao = db.attendanceDao()
    val holidayDao = db.holidayDao()
    val salesMonthDao = db.salesMonthDao()
    val bonusEventDao = db.bonusEventDao()

    /** Folder foto selfie: <filesDir>/photos/yyyy-MM/ */
    val photoRoot: File = File(context.filesDir, "photos")

    /** Cadangan otomatis ke Download/PresensiIndoHPL (tetap ada walau aplikasi dihapus). */
    val localBackup = LocalBackup(appContext)

    companion object {
        /** Daftar karyawan awal. Bisa diubah nanti lewat database tanpa mengubah kode. */
        val DEFAULT_EMPLOYEES: List<Employee> = listOf(
            Employee(1, "Sara", "P"),
            Employee(2, "Riyanti", "P"),
            Employee(3, "Evan", "L"),
            Employee(4, "Madi", "L"),
            Employee(5, "Roni", "L"),
        )
    }

    suspend fun ensureSeeded() {
        if (employeeDao.count() == 0) {
            employeeDao.insertAll(DEFAULT_EMPLOYEES)
        }
    }

    /** Kembalikan karyawan bawaan yang hilang (yang sudah ada tidak diubah). Mengembalikan jumlah yang ditambahkan. */
    suspend fun seedMissingEmployees(): Int {
        val before = employeeDao.count()
        employeeDao.insertAll(DEFAULT_EMPLOYEES)
        return employeeDao.count() - before
    }

    fun observeEmployees(): Flow<List<Employee>> = employeeDao.observeAll()
    fun observeToday(date: LocalDate): Flow<List<AttendanceRecord>> = attendanceDao.observeByDate(date.toString())
    fun observeMonth(ym: YearMonth): Flow<List<AttendanceRecord>> =
        attendanceDao.observeBetween(ym.atDay(1).toString(), ym.atEndOfMonth().toString())
    suspend fun getMonth(ym: YearMonth): List<AttendanceRecord> =
        attendanceDao.getBetween(ym.atDay(1).toString(), ym.atEndOfMonth().toString())
    fun observeHolidays(): Flow<List<Holiday>> = holidayDao.observeAll()

    /** Rekap saldo uang rajin semua karyawan aktif untuk satu bulan (dipakai layar hasil absen). */
    suspend fun monthRecaps(ym: YearMonth, today: LocalDate = LocalDate.now()): List<com.indohpl.presensi.domain.EmployeeRecap> {
        val s = settingsStore.current()
        return com.indohpl.presensi.domain.BonusCalculator.recap(
            ym = ym,
            today = today,
            employees = employeeDao.getAll().filter { it.active },
            records = getMonth(ym),
            workDayOfWeeks = s.workDays,
            holidays = holidayDao.getAll().mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.toSet(),
            config = s.bonusConfig(),
        )
    }

    /**
     * Simpan presensi hadir. [capturedAt] adalah waktu saat foto diambil (jam HP kasir).
     * Mengembalikan null jika karyawan sudah absen hari itu.
     */
    suspend fun recordCheckIn(
        employeeId: Long,
        capturedAt: LocalDateTime,
        photoPath: String,
        geo: GeoCheck?,
    ): AttendanceRecord? {
        val date = capturedAt.toLocalDate().toString()
        if (attendanceDao.find(employeeId, date) != null) return null
        val employee = employeeDao.getById(employeeId) ?: return null
        val s = settingsStore.current()
        // Aturan per gender: perempuan 07:45 (+10), laki-laki 08:00 (+5) secara bawaan.
        val late = LateRule.lateMinutes(
            capturedAt.toLocalTime(),
            s.workStartFor(employee.gender),
            s.toleranceFor(employee.gender),
        )
        val rec = AttendanceRecord(
            employeeId = employeeId,
            date = date,
            timestamp = capturedAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            timeIn = capturedAt.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            status = if (late > 0) Status.TELAT else Status.TEPAT,
            lateMinutes = late,
            photoPath = photoPath,
            latitude = geo?.latitude,
            longitude = geo?.longitude,
            accuracy = geo?.accuracy,
            distanceMeters = geo?.distanceMeters,
            inLocation = geo?.inLocation ?: false,
            locationNote = geo?.note ?: "GPS tidak didapat",
        )
        val id = attendanceDao.insert(rec)
        backupSoon()
        return rec.copy(id = id)
    }

    /** Tandai izin/sakit secara manual (tanpa selfie). */
    suspend fun recordExcused(employeeId: Long, date: LocalDate, status: String, note: String): Boolean {
        if (attendanceDao.find(employeeId, date.toString()) != null) return false
        attendanceDao.insert(
            AttendanceRecord(
                employeeId = employeeId,
                date = date.toString(),
                timestamp = System.currentTimeMillis(),
                timeIn = "",
                status = status,
                lateMinutes = 0,
                photoPath = null,
                note = note,
            ),
        )
        backupSoon()
        return true
    }

    /**
     * Owner menganulir telat (mis. karyawan sudah datang tepat waktu tetapi pintu toko belum dibuka).
     * Catatan menjadi TEPAT sehingga saldo bertambah seperti biasa; menit telat asli dan alasannya disimpan
     * sebagai jejak (lihat [AnnulRule]). Mengembalikan catatan baru, atau null bila bukan catatan telat.
     */
    suspend fun annulLate(recordId: Long, reason: String): AttendanceRecord? {
        val r = attendanceDao.getById(recordId) ?: return null
        val updated = AnnulRule.annul(r, reason) ?: return null
        attendanceDao.update(updated)
        backupSoon()
        return updated
    }

    /** Membatalkan anulir: kembali TELAT dengan menit telat asli. Mengembalikan catatan baru, atau null bila tidak dianulir. */
    suspend fun undoAnnul(recordId: Long): AttendanceRecord? {
        val r = attendanceDao.getById(recordId) ?: return null
        val updated = AnnulRule.undo(r) ?: return null
        attendanceDao.update(updated)
        backupSoon()
        return updated
    }

    /**
     * Berapa saldo karyawan berubah bila catatan ini dianulir (positif) atau anulirnya dibatalkan (negatif).
     * Untuk ditampilkan di dialog konfirmasi owner sebelum menyimpan. 0 bila tidak berpengaruh (mis. hari libur).
     */
    suspend fun annulSaldoChange(record: AttendanceRecord, today: LocalDate = LocalDate.now()): Long {
        val after = AnnulRule.annul(record, AnnulRule.DEFAULT_REASON) ?: AnnulRule.undo(record) ?: return 0
        val employee = employeeDao.getAll().firstOrNull { it.id == record.employeeId } ?: return 0
        val s = settingsStore.current()
        return AnnulRule.saldoChange(
            employee = employee,
            monthRecords = getMonth(YearMonth.from(LocalDate.parse(record.date))),
            before = record,
            after = after,
            today = today,
            workDayOfWeeks = s.workDays,
            holidays = holidayDao.getAll().mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.toSet(),
            config = s.bonusConfig(),
        )
    }

    fun observeLateOrAnnulled(from: LocalDate, to: LocalDate): Flow<List<AttendanceRecord>> =
        attendanceDao.observeLateOrAnnulled(from.toString(), to.toString())

    suspend fun deleteRecord(record: AttendanceRecord) {
        attendanceDao.delete(record)
        record.photoPath?.let { runCatching { File(it).delete() } }
        backupSoon()
    }

    data class ImportResult(
        val imported: Int,
        val duplicates: Int,
        val unknownNames: Set<String>,
        val tooOld: Int,
        val errors: List<String>,
    )

    /**
     * Impor catatan dari file CSV detail (ekspor aplikasi versi lama). Dipakai untuk pindah ke APK berkunci
     * permanen tanpa komputer. Foto tidak ikut (CSV hanya menyimpan nama file). Catatan yang sudah ada
     * (karyawan + tanggal sama) dilewati; yang lebih lama dari batas simpan juga dilewati supaya tidak
     * langsung terhapus lagi oleh pembersihan otomatis.
     */
    suspend fun importCsv(files: List<Pair<String, String>>, today: LocalDate = LocalDate.now()): ImportResult {
        val employees = employeeDao.getAll()
        val byName = employees.associateBy { it.name.trim().lowercase() }
        val cutoff = Retention.cutoffDate(today, settingsStore.current().retentionMonths)
        var imported = 0
        var duplicates = 0
        var tooOld = 0
        val unknown = linkedSetOf<String>()
        val errors = mutableListOf<String>()
        files.forEach { (fileName, text) ->
            val parsed = CsvImport.parse(text)
            errors += parsed.errors.map { "$fileName: $it" }
            for (r in parsed.rows) {
                val emp = byName[r.name.trim().lowercase()]
                if (emp == null) { unknown += r.name; continue }
                if (r.date.isBefore(cutoff)) { tooOld++; continue }
                val time = if (r.timeIn.isNotEmpty()) java.time.LocalTime.parse(r.timeIn) else java.time.LocalTime.MIDNIGHT
                val id = attendanceDao.insertIgnore(
                    AttendanceRecord(
                        employeeId = emp.id,
                        date = r.date.toString(),
                        timestamp = r.date.atTime(time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        timeIn = r.timeIn,
                        status = r.status,
                        lateMinutes = r.lateMinutes,
                        photoPath = null,
                        note = r.note,
                        latitude = r.latitude,
                        longitude = r.longitude,
                        accuracy = r.accuracy,
                        distanceMeters = r.distanceMeters,
                        inLocation = r.inLocation,
                        locationNote = r.locationNote,
                        annulledLateMinutes = r.annulledLateMinutes,
                        annulReason = r.annulReason,
                    ),
                )
                if (id == -1L) duplicates++ else imported++
            }
        }
        if (imported > 0) backupSoon()
        return ImportResult(imported, duplicates, unknown, tooOld, errors)
    }

    /**
     * Pembersihan otomatis: hapus catatan (dan fotonya) yang lebih lama dari [AppSettings.retentionMonths].
     * Mengembalikan jumlah catatan yang dihapus.
     */
    suspend fun cleanupOldData(
        today: LocalDate = LocalDate.now(),
        months: Int? = null,
        automatic: Boolean = false,
    ): Int {
        val months = months ?: settingsStore.current().retentionMonths
        val cutoff = Retention.cutoffDate(today, months).toString()
        // Catat bahwa pembersihan sudah DIPERIKSA (juga bila pengaman menundanya), supaya Pemeriksaan tidak terus memperingatkan.
        settingsStore.setLastCleanupAt(System.currentTimeMillis())
        if (automatic) {
            val newest = attendanceDao.newestDate()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (!Retention.shouldAutoClean(today, newest, attendanceDao.monthsBefore(cutoff).size)) return 0
        }
        val old = attendanceDao.getBefore(cutoff)
        old.forEach { r -> r.photoPath?.let { runCatching { File(it).delete() } } }
        val deleted = attendanceDao.deleteBefore(cutoff)
        holidayDao.deleteBefore(cutoff)
        bonusEventDao.deleteBefore(cutoff) // teguran/setengah hari ikut aturan simpan presensi; penjualan bulanan tetap

        // Folder foto per bulan yang sudah kosong / lebih lama dari batas ikut dibuang.
        photoRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name < cutoff.take(7)) dir.deleteRecursively()
        }
        if (deleted > 0) backupSoon()
        return deleted
    }

    // ---------- Cadangan & pulihkan ----------

    private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backupJob: Job? = null

    /** Isi cadangan lengkap saat ini (tanpa foto). */
    suspend fun buildBackup(): BackupData = BackupData(
        createdAt = LocalDateTime.now().toString(),
        appVersion = installedVersionName(),
        employees = employeeDao.getAll(),
        attendance = attendanceDao.getAll(),
        holidays = holidayDao.getAll(),
        settings = settingsStore.current(),
        salesMonths = salesMonthDao.getAll(),
        bonusEvents = bonusEventDao.getAll(),
        salesConfig = settingsStore.salesBonusConfig.first(),
    )

    @Suppress("DEPRECATION")
    private fun installedVersionName(): String =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull() ?: ""

    /**
     * Simpan cadangan sekarang ke Download/PresensiIndoHPL. Tidak pernah melempar error.
     * Database tanpa satu pun catatan presensi dilewati supaya HP yang baru dipasang ulang tidak
     * menimpa cadangan hari yang sama dengan file kosong sebelum sempat dipulihkan.
     */
    suspend fun backupNow(): Result<String> = backupMutex.withLock { backupNowLocked() }

    /** Satu penulisan cadangan pada satu waktu (penulisan file tidak bisa dibatalkan di tengah jalan). */
    private val backupMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun backupNowLocked(): Result<String> = try {
        if (attendanceDao.count() == 0) {
            Result.failure<String>(IllegalStateException("Belum ada catatan presensi, cadangan dibuat setelah ada catatan pertama"))
        } else {
            val json = BackupCodec.encode(buildBackup())
            val result = localBackup.write(json, LocalDate.now())
            result.getOrNull()?.let { settingsStore.setLastBackup(System.currentTimeMillis(), it) }
            result
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure<String>(e)
    }

    /** Jadwalkan cadangan 3 detik lagi; panggilan beruntun digabung jadi satu. */
    @Synchronized
    fun backupSoon() {
        backupJob?.cancel()
        backupJob = backupScope.launch {
            delay(3_000)
            backupNow()
        }
    }

    data class RestoreResult(
        val imported: Int,
        val duplicates: Int,
        val employeesAdded: Int,
        val holidays: Int,
        val tooOld: Int,
        val settingsApplied: Boolean,
        val unmatched: Int = 0,
        val salesMonths: Int = 0,
        val bonusEvents: Int = 0,
    )

    /**
     * Pulihkan dari teks file cadangan. Catatan yang sudah ada TIDAK pernah dihapus/ditimpa: catatan cadangan
     * dimasukkan dengan IGNORE (karyawan + tanggal sama dilewati). Karyawan dicocokkan lewat NAMA, bukan id.
     * Foto hanya ditautkan kalau filenya masih ada di folder foto aplikasi.
     * @throws IllegalArgumentException jika file bukan cadangan yang sah.
     */
    suspend fun restoreFromBackup(text: String, applySettings: Boolean, today: LocalDate = LocalDate.now()): RestoreResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val data = BackupCodec.decode(text) // IllegalArgumentException diteruskan ke pemanggil
            val result = db.withTransaction { restoreLocked(data, applySettings, today) }
            if (applySettings) {
                settingsStore.save(data.settings) // PIN owner tidak disentuh
                data.salesConfig?.let { settingsStore.saveSalesBonusConfig(it) }
            }
            backupSoon()
            result
        }

    private suspend fun restoreLocked(data: com.indohpl.presensi.data.backup.BackupData, applySettings: Boolean, today: LocalDate): RestoreResult {

        val employeesBefore = employeeDao.count()
        employeeDao.insertAll(data.employees) // IGNORE: id yang sudah ada tidak diubah
        val employeesAdded = employeeDao.count() - employeesBefore

        val currentByName = employeeDao.getAll().associateBy { it.name.trim().lowercase() }
        val backupNameById = data.employees.associate { it.id to it.name.trim().lowercase() }

        val months = if (applySettings) data.settings.retentionMonths else settingsStore.current().retentionMonths
        val cutoff = Retention.cutoffDate(today, months).toString()

        var imported = 0
        var duplicates = 0
        var tooOld = 0
        var unmatched = 0
        for (r in data.attendance) {
            val current = backupNameById[r.employeeId]?.let { currentByName[it] }
            if (current == null) { unmatched++; continue } // nama karyawan di cadangan tidak ada di HP ini
            if (r.date < cutoff) { tooOld++; continue }
            val fileName = r.photoPath?.substringAfterLast('/')
            val photo = if (!fileName.isNullOrEmpty()) File(photoRoot, "${r.date.take(7)}/$fileName") else null
            val id = attendanceDao.insertIgnore(
                r.copy(
                    id = 0,
                    employeeId = current.id,
                    photoPath = photo?.takeIf { it.isFile }?.absolutePath,
                ),
            )
            if (id == -1L) duplicates++ else imported++
        }

        var holidays = 0
        for (h in data.holidays) {
            if (h.date < cutoff) continue
            holidayDao.upsert(h)
            holidays++
        }

        // Bonus penjualan: id karyawan di cadangan diterjemahkan lewat nama, seperti presensi.
        fun currentIdOf(backupId: Long): Long? = backupNameById[backupId]?.let { currentByName[it] }?.id
        var salesMonths = 0
        for (m in data.salesMonths) {
            val excluded = m.excludedEmployeeIds.split(',').mapNotNull { it.trim().toLongOrNull() }
                .mapNotNull { currentIdOf(it) }.joinToString(",")
            if (salesMonthDao.insertIgnore(m.copy(excludedEmployeeIds = excluded)) != -1L) salesMonths++
        }
        var bonusEvents = 0
        for (e in data.bonusEvents) {
            val id = currentIdOf(e.employeeId) ?: continue
            if (e.date < cutoff) continue
            if (bonusEventDao.insertIgnore(e.copy(id = 0, employeeId = id)) != -1L) bonusEvents++
        }

        return RestoreResult(imported, duplicates, employeesAdded, holidays, tooOld, applySettings, unmatched, salesMonths, bonusEvents)
    }

    // ---------- Bonus penjualan toko ----------

    /**
     * Semua yang dibutuhkan layar bonus penjualan untuk bulan [ym].
     * [result] dihitung langsung dari data sekarang, kecuali bulan yang sudah ditandai dibayar: angkanya dari
     * [SalesMonth.snapshot] (dikunci). [attendanceDeleted] = presensi bulan itu sudah terhapus oleh pembersihan data
     * lama dan bulan itu belum dikunci, jadi hitungan langsung tidak bisa dipercaya.
     */
    data class SalesBonusState(
        val ym: YearMonth,
        val config: com.indohpl.presensi.domain.SalesBonusConfig,
        val month: SalesMonth?,
        val result: com.indohpl.presensi.domain.SalesBonusResult,
        val events: List<BonusEvent>,
        val employees: List<Employee>,
        val paid: Boolean,
        val attendanceDeleted: Boolean,
    ) {
        val excludedIds: Set<Long> get() = parseIds(month?.excludedEmployeeIds.orEmpty())
        val applies: Boolean get() = config.appliesTo(ym)
        /** Bulan sudah lewat seluruhnya (hasil akhir). */
        val monthFinished: Boolean get() = LocalDate.now().isAfter(ym.atEndOfMonth())
        /** Penjualan yang diisi sampai tanggal (null bila belum diisi). */
        val salesAsOf: LocalDate? get() = month?.asOfDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        /** Penjualan sudah diisi sampai akhir bulan: baru boleh jadi hasil akhir / ditandai dibayar. */
        val salesComplete: Boolean get() = salesAsOf?.let { !it.isBefore(ym.atEndOfMonth()) } ?: false
    }

    fun observeSalesBonus(ym: YearMonth): Flow<SalesBonusState> {
        val from = ym.atDay(1).toString()
        val to = ym.atEndOfMonth().toString()
        val base = kotlinx.coroutines.flow.combine(
            salesMonthDao.observe(ym.toString()),
            bonusEventDao.observeBetween(from, to),
            observeEmployees(),
            observeMonth(ym),
        ) { month, events, employees, records -> SalesInputs(month, events, employees, records) }
        return kotlinx.coroutines.flow.combine(
            base,
            settingsStore.settings,
            observeHolidays(),
            settingsStore.salesBonusConfig,
        ) { inp, settings, holidays, config ->
            salesBonusState(ym, inp, settings, holidays, config, LocalDate.now())
        }
    }

    suspend fun salesBonusState(ym: YearMonth): SalesBonusState =
        observeSalesBonus(ym).first()

    private data class SalesInputs(
        val month: SalesMonth?,
        val events: List<BonusEvent>,
        val employees: List<Employee>,
        val records: List<AttendanceRecord>,
    )

    private fun salesBonusState(
        ym: YearMonth,
        inp: SalesInputs,
        settings: AppSettings,
        holidays: List<Holiday>,
        config: com.indohpl.presensi.domain.SalesBonusConfig,
        today: LocalDate,
    ): SalesBonusState {
        val active = inp.employees.filter { it.active }
        val month = inp.month
        val excluded = parseIds(month?.excludedEmployeeIds.orEmpty())
        val net = month?.let { com.indohpl.presensi.domain.SalesBonus.netSales(it.grossSales, it.excludedSales) } ?: 0L
        val live = com.indohpl.presensi.domain.SalesBonus.calculate(
            ym = ym,
            today = today,
            netSales = net,
            config = config,
            employees = active,
            excludedIds = excluded,
            records = inp.records,
            events = inp.events,
            workDayOfWeeks = settings.workDays,
            holidays = holidays.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.toSet(),
        )
        val paid = month?.paidAt != null
        val frozen = if (paid) SalesSnapshot.decode(month!!.snapshot) else null
        val cutoff = Retention.cutoffDate(today, settings.retentionMonths)
        return SalesBonusState(
            ym = ym,
            config = config,
            month = month,
            result = frozen ?: live,
            events = inp.events,
            employees = active,
            paid = paid,
            attendanceDeleted = !paid && ym.atEndOfMonth().isBefore(cutoff),
        )
    }

    /**
     * Simpan penjualan bulan [ym] (dari laporan Toqoo). Peserta dan status dibayar tidak berubah.
     * false bila bulan itu sudah ditandai dibayar (dikunci).
     */
    suspend fun saveSales(ym: YearMonth, grossSales: Long, excludedSales: Long, asOf: LocalDate, note: String): Boolean {
        val existing = salesMonthDao.get(ym.toString())
        if (existing?.paidAt != null) return false
        val now = System.currentTimeMillis()
        salesMonthDao.upsert(
            (existing ?: emptySalesMonth(ym, now)).copy(
                grossSales = grossSales.coerceAtLeast(0L),
                excludedSales = excludedSales.coerceAtLeast(0L),
                asOfDate = asOf.toString(),
                note = note.trim(),
                updatedAt = now,
            ),
        )
        backupSoon()
        return true
    }

    /** Ikut / tidak ikut bonus bulan [ym] (mis. keluar sebelum akhir bulan, atau belum bulan penuh pertama). */
    suspend fun setSalesParticipant(ym: YearMonth, employeeId: Long, participating: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val existing = salesMonthDao.get(ym.toString()) ?: emptySalesMonth(ym, now)
        if (existing.paidAt != null) return false
        val ids = parseIds(existing.excludedEmployeeIds).let { if (participating) it - employeeId else it + employeeId }
        salesMonthDao.upsert(existing.copy(excludedEmployeeIds = ids.sorted().joinToString(","), updatedAt = now))
        backupSoon()
        return true
    }

    /** Catat teguran / setengah hari. false bila bulan itu sudah dikunci. */
    suspend fun addBonusEvent(employeeId: Long, date: LocalDate, type: String, note: String, byWhom: String): Boolean {
        if (salesMonthDao.get(YearMonth.from(date).toString())?.paidAt != null) return false
        bonusEventDao.insert(
            BonusEvent(
                employeeId = employeeId,
                date = date.toString(),
                type = type,
                note = note.trim(),
                byWhom = byWhom.trim(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        backupSoon()
        return true
    }

    suspend fun deleteBonusEvent(event: BonusEvent): Boolean {
        val ym = runCatching { YearMonth.from(LocalDate.parse(event.date)) }.getOrNull()
        if (ym != null && salesMonthDao.get(ym.toString())?.paidAt != null) return false
        bonusEventDao.delete(event)
        backupSoon()
        return true
    }

    /**
     * Tandai bonus bulan [ym] sudah dibayar: hasil saat ini dikunci (tidak berubah lagi). Hanya setelah bulan selesai,
     * dan hanya bila penjualan sudah diisi. false bila tidak bisa.
     */
    suspend fun markSalesPaid(ym: YearMonth): Boolean {
        if (!LocalDate.now().isAfter(ym.atEndOfMonth())) return false
        val state = salesBonusState(ym)
        val month = state.month ?: return false
        if (state.paid || state.attendanceDeleted || !state.salesComplete) return false
        salesMonthDao.upsert(
            month.copy(paidAt = System.currentTimeMillis(), snapshot = SalesSnapshot.encode(state.result), updatedAt = System.currentTimeMillis()),
        )
        backupSoon()
        return true
    }

    /** Buka lagi bulan yang sudah ditandai dibayar (hasil dihitung ulang dari data sekarang). */
    suspend fun reopenSales(ym: YearMonth): Boolean {
        val month = salesMonthDao.get(ym.toString()) ?: return false
        if (month.paidAt == null) return false
        salesMonthDao.upsert(month.copy(paidAt = null, snapshot = "", updatedAt = System.currentTimeMillis()))
        backupSoon()
        return true
    }

    private fun emptySalesMonth(ym: YearMonth, now: Long) = SalesMonth(
        ym = ym.toString(), grossSales = 0L, excludedSales = 0L, asOfDate = "", note = "",
        excludedEmployeeIds = "", paidAt = null, snapshot = "", updatedAt = now,
    )

    // ---------- Pemeriksaan (Inspector) ----------

    suspend fun attendanceCount(): Int = attendanceDao.count()

    /** (tanggal terlama, tanggal terbaru) catatan presensi; null jika belum ada. */
    suspend fun dateRange(): Pair<String, String>? {
        val oldest = attendanceDao.oldestDate() ?: return null
        val newest = attendanceDao.newestDate() ?: return null
        return oldest to newest
    }

    /** Hasil PRAGMA integrity_check; "ok" jika database sehat. */
    suspend fun integrityCheck(): String = withContext(Dispatchers.IO) {
        db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { c ->
            val rows = mutableListOf<String>()
            while (c.moveToNext() && rows.size < 5) rows += c.getString(0)
            if (rows.isEmpty()) "tidak ada hasil" else rows.joinToString("; ")
        }
    }

    /** Catatan yang punya tautan foto tetapi filenya sudah tidak ada. */
    suspend fun missingPhotoRecords(): List<AttendanceRecord> = withContext(Dispatchers.IO) {
        attendanceDao.getAll().filter { r ->
            val p = r.photoPath
            p != null && !File(p).exists()
        }
    }

    /** Hapus tautan foto yang filenya hilang (catatan tetap ada). Mengembalikan jumlah catatan yang dirapikan. */
    suspend fun clearMissingPhotoLinks(): Int {
        val missing = missingPhotoRecords()
        missing.forEach { attendanceDao.clearPhotoPath(it.id) }
        return missing.size
    }

    /** File di folder foto yang tidak dipakai catatan mana pun. File yang baru dibuat (< 10 menit) dilewati. */
    suspend fun orphanPhotos(): List<File> = withContext(Dispatchers.IO) {
        if (!photoRoot.isDirectory) return@withContext emptyList()
        val used = attendanceDao.getAll().mapNotNull { it.photoPath }.map { canonical(File(it)) }.toSet()
        val recent = System.currentTimeMillis() - 10 * 60_000L
        photoRoot.walkTopDown()
            .filter { it.isFile && it.lastModified() < recent && canonical(it) !in used }
            .toList()
    }

    private fun canonical(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    /** Hapus foto yatim. Mengembalikan (jumlah file, byte yang dibebaskan). */
    suspend fun deleteOrphanPhotos(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        var count = 0
        var bytes = 0L
        for (f in orphanPhotos()) {
            val size = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                count++
                bytes += size
            }
        }
        count to bytes
    }
}

/** "1,3,5" -> {1, 3, 5}; teks rusak diabaikan. */
internal fun parseIds(text: String): Set<Long> = text.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
