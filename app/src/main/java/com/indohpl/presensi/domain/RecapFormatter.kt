package com.indohpl.presensi.domain

import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Menghasilkan teks/CSV rekap. Murni Kotlin (tanpa Android) supaya bisa diuji. */
object RecapFormatter {
    private val monthNames = listOf(
        "Januari", "Februari", "Maret", "April", "Mei", "Juni",
        "Juli", "Agustus", "September", "Oktober", "November", "Desember",
    )

    fun monthLabel(ym: YearMonth): String = "${monthNames[ym.monthValue - 1]} ${ym.year}"

    fun rupiah(v: Long): String {
        val s = String.format(Locale.US, "%,d", v).replace(',', '.')
        return "Rp $s"
    }

    /** CSV detail per hari. Dipisah koma, header baris pertama. Cocok untuk Excel / Claude. */
    fun detailCsv(ym: YearMonth, employees: List<Employee>, records: List<AttendanceRecord>): String {
        val names = employees.associate { it.id to it.name }
        val sb = StringBuilder()
        sb.append("tanggal,nama,status,jam_masuk,menit_telat,catatan,foto\n")
        records.sortedWith(compareBy({ it.date }, { it.timestamp })).forEach { r ->
            sb.append(
                listOf(
                    r.date,
                    names[r.employeeId] ?: r.employeeId.toString(),
                    r.status,
                    r.timeIn,
                    r.lateMinutes.toString(),
                    r.note,
                    r.photoPath?.substringAfterLast('/') ?: "",
                ).joinToString(",") { csvCell(it) },
            ).append('\n')
        }
        return sb.toString()
    }

    /** CSV ringkasan per karyawan. */
    fun summaryCsv(ym: YearMonth, recaps: List<EmployeeRecap>): String {
        val sb = StringBuilder()
        sb.append("bulan,nama,hari_kerja,hadir,tepat,telat,izin,sakit,alpa,total_menit_telat,potongan_per_hari,hari_dipotong,total_potongan,uang_rajin,full_bulan\n")
        recaps.forEach { r ->
            sb.append(
                listOf(
                    ym.toString(), r.employee.name, r.workDaysInMonth, r.hadir, r.tepat, r.telat, r.izin, r.sakit,
                    r.alpa, r.totalLateMinutes, r.deductionPerDay, r.deductedDays, r.totalDeduction, r.bonus,
                    if (r.isFullMonth) "YA" else "TIDAK",
                ).joinToString(",") { csvCell(it.toString()) },
            ).append('\n')
        }
        return sb.toString()
    }

    /** Rekap dalam Markdown — tinggal tempel ke Claude. */
    fun markdown(
        ym: YearMonth,
        recaps: List<EmployeeRecap>,
        records: List<AttendanceRecord>,
        employees: List<Employee>,
        config: BonusConfig,
        workStart: String,
        toleranceMinutes: Int,
        generatedAt: String,
    ): String {
        val names = employees.associate { it.id to it.name }
        val sb = StringBuilder()
        sb.append("# Rekap Presensi ${monthLabel(ym)}\n\n")
        sb.append("Dibuat: $generatedAt\n")
        sb.append("Jam masuk: $workStart (toleransi $toleranceMinutes menit). ")
        sb.append("Uang rajin penuh: ${rupiah(config.monthlyBonus)}/bulan. ")
        val perDay = recaps.firstOrNull()?.deductionPerDay ?: 0
        sb.append("Potongan per hari telat: ${rupiah(perDay)}")
        sb.append(if (config.deductAbsent) " (alpa juga dipotong).\n\n" else " (alpa tidak dipotong).\n\n")

        sb.append("## Ringkasan\n\n")
        sb.append("| Nama | Hari kerja | Hadir | Tepat | Telat | Izin | Sakit | Alpa | Potongan | Uang rajin |\n")
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n")
        recaps.forEach { r ->
            sb.append(
                "| ${r.employee.name} | ${r.workDaysInMonth} | ${r.hadir} | ${r.tepat} | ${r.telat} | ${r.izin} | ${r.sakit} | ${r.alpa} | ${rupiah(r.totalDeduction)} | **${rupiah(r.bonus)}** |\n",
            )
        }
        sb.append("\n## Detail harian\n\n")
        sb.append("| Tanggal | Nama | Status | Jam masuk | Telat (menit) | Catatan |\n")
        sb.append("|---|---|---|---|---:|---|\n")
        records.sortedWith(compareBy({ it.date }, { it.timestamp })).forEach { r ->
            sb.append("| ${r.date} | ${names[r.employeeId] ?: r.employeeId} | ${r.status} | ${r.timeIn} | ${r.lateMinutes} | ${r.note} |\n")
        }
        return sb.toString()
    }

    private fun csvCell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    val DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
}
