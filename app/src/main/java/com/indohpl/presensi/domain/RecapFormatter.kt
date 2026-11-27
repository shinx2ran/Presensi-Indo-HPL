package com.indohpl.presensi.domain

import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Status
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

    /** "+Rp 8.333" untuk tambahan saldo. */
    fun rupiahPlus(v: Long): String = "+" + rupiah(v)

    fun rupiah(v: Long): String {
        val s = String.format(Locale.US, "%,d", v).replace(',', '.')
        return "Rp $s"
    }

    /** CSV detail per hari. Dipisah koma, header baris pertama. Cocok untuk Excel / Claude. */
    fun detailCsv(ym: YearMonth, employees: List<Employee>, records: List<AttendanceRecord>): String {
        val names = employees.associate { it.id to it.name }
        val sb = StringBuilder()
        sb.append("tanggal,nama,status,jam_masuk,menit_telat,catatan,foto,latitude,longitude,akurasi_m,jarak_toko_m,di_lokasi,ket_lokasi,telat_dianulir_menit,alasan_anulir\n")
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
                    r.latitude?.toString() ?: "",
                    r.longitude?.toString() ?: "",
                    r.accuracy?.toInt()?.toString() ?: "",
                    r.distanceMeters?.toString() ?: "",
                    if (r.status == Status.TEPAT || r.status == Status.TELAT) (if (r.inLocation) "YA" else "TIDAK") else "",
                    r.locationNote,
                    r.annulledLateMinutes?.toString() ?: "",
                    r.annulReason,
                ).joinToString(",") { csvCell(it) },
            ).append('\n')
        }
        return sb.toString()
    }

    /** CSV ringkasan per karyawan. */
    fun summaryCsv(ym: YearMonth, recaps: List<EmployeeRecap>): String {
        val sb = StringBuilder()
        sb.append("bulan,nama,hari_kerja,hadir,tepat,telat,izin,sakit,alpa,di_luar_lokasi,total_menit_telat,tambah_per_hari,hari_menambah,saldo_uang_rajin,maksimal_bulan_ini,full_bulan,telat_dianulir\n")
        recaps.forEach { r ->
            sb.append(
                listOf(
                    ym.toString(), r.employee.name, r.workDaysInMonth, r.hadir, r.tepat, r.telat, r.izin, r.sakit,
                    r.alpa, r.outsideLocation, r.totalLateMinutes, r.perDayAmount, r.creditedDays, r.earned, r.maxThisMonth,
                    if (r.isFullMonth) "YA" else "TIDAK", r.annulled,
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
        rulesText: String,
        generatedAt: String,
    ): String {
        val names = employees.associate { it.id to it.name }
        val sb = StringBuilder()
        sb.append("# Rekap Presensi ${monthLabel(ym)}\n\n")
        sb.append("Dibuat: $generatedAt\n")
        sb.append("Jam masuk: $rulesText. ")
        val perDay = recaps.firstOrNull()?.perDayAmount ?: 0
        sb.append("Uang rajin bertambah ${rupiahPlus(perDay)} setiap hari tepat waktu (telat, izin, sakit, dan bolos tidak dihitung)")
        sb.append(", maksimal ${rupiah(recaps.firstOrNull()?.maxThisMonth ?: config.monthlyBonus)} bulan ini.\n")
        val totalAnnulled = recaps.sumOf { it.annulled }
        if (totalAnnulled > 0) {
            sb.append("Telat yang dianulir owner (dihitung tepat waktu): $totalAnnulled kali, ditandai \"dianulir\" di detail harian.\n")
        }
        sb.append("\n")

        sb.append("## Ringkasan\n\n")
        sb.append("| Nama | Hari kerja | Hadir | Tepat | Telat | Izin | Sakit | Alpa | Di luar lokasi | Hari menambah | Saldo uang rajin | Maksimal |\n")
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n")
        recaps.forEach { r ->
            sb.append(
                "| ${mdCell(r.employee.name)} | ${r.workDaysInMonth} | ${r.hadir} | ${r.tepat} | ${r.telat} | ${r.izin} | ${r.sakit} | ${r.alpa} | ${r.outsideLocation} | ${r.creditedDays} | **${rupiah(r.earned)}** | ${rupiah(r.maxThisMonth)} |\n",
            )
        }
        val creditByKey = recaps.flatMap { r -> r.dailyCredits.map { (d, v) -> (r.employee.id to d) to v } }.toMap()
        sb.append("\n## Detail harian\n\n")
        sb.append("| Tanggal | Nama | Status | Jam masuk | Telat (menit) | Tambah saldo | Lokasi | Jarak toko | Koordinat | Catatan |\n")
        sb.append("|---|---|---|---|---:|---:|---|---:|---|---|\n")
        records.sortedWith(compareBy({ it.date }, { it.timestamp })).forEach { r ->
            val hadir = r.status == Status.TEPAT || r.status == Status.TELAT
            val lokasi = if (!hadir) "" else if (r.inLocation) "di lokasi" else "DI LUAR (${r.locationNote})"
            val jarak = r.distanceMeters?.let { GeoRule.formatDistance(it) } ?: ""
            val koord = if (r.latitude != null && r.longitude != null) GeoRule.formatCoord(r.latitude, r.longitude) else ""
            val tambah = creditByKey[r.employeeId to r.date]?.let { rupiahPlus(it) } ?: "-"
            val status = if (r.annulledLateMinutes != null) {
                val reason = mdCell(r.annulReason)
                "${r.status} (dianulir, telat ${r.annulledLateMinutes} mnt${if (reason.isNotEmpty()) ": $reason" else ""})"
            } else {
                r.status
            }
            val name = mdCell(names[r.employeeId] ?: r.employeeId.toString())
            sb.append("| ${r.date} | $name | $status | ${r.timeIn} | ${r.lateMinutes} | $tambah | ${mdCell(lokasi)} | $jarak | $koord | ${mdCell(r.note)} |\n")
        }
        return sb.toString()
    }

    /**
     * Bagian "Bonus penjualan" untuk rekap Markdown (ditambahkan di akhir rekap bulanan).
     * [asOf]: data penjualan sampai tanggal; [paid]: bulan sudah ditandai dibayar (angka dikunci).
     */
    fun salesBonusMarkdown(result: SalesBonusResult, config: SalesBonusConfig, asOf: String, paid: Boolean): String {
        val sb = StringBuilder()
        sb.append("\n## Bonus penjualan toko\n\n")
        if (!config.appliesTo(result.ym)) {
            sb.append("Bonus penjualan berlaku mulai ${monthLabel(config.startMonth)}.\n")
            return sb.toString()
        }
        sb.append("Penjualan dihitung (Toqoo, tanpa proyek CE LENY): **${rupiah(result.netSales)}**")
        if (asOf.isNotBlank()) sb.append(" (data sampai $asOf)")
        sb.append(if (paid) ". Status: **sudah dibayar** (angka dikunci).\n\n" else ". Status: belum dibayar (angka bisa berubah).\n\n")
        sb.append("Target: ")
        sb.append(config.sortedTiers.joinToString(" · ") { "${SalesBonus.juta(it.minSales)} → ${rupiah(it.perPerson)}/orang" })
        sb.append("\n\n")
        val tier = result.tier
        if (tier == null) {
            val next = result.nextTier
            sb.append(if (next != null) "Belum mencapai target ${SalesBonus.juta(next.minSales)} (kurang ${rupiah(result.shortfall)}).\n" else "Target belum diatur.\n")
            return sb.toString()
        }
        sb.append("Target ${SalesBonus.juta(tier.minSales)} tercapai: ${rupiah(tier.perPerson)}/orang x ${result.participants} peserta = uang bonus **${rupiah(result.pool)}**.\n\n")
        sb.append("| Nama | Ikut | Tidak masuk (hari) | Teguran | Bagian | Bonus |\n")
        sb.append("|---|---|---:|---:|---|---:|\n")
        result.people.forEach { p ->
            val days = if (p.absentHalfDays % 2 == 0) "${p.absentHalfDays / 2}" else "${p.absentHalfDays / 2},5"
            sb.append("| ${mdCell(p.employee.name)} | ${if (p.participating) "ya" else "tidak"} | $days | ${p.warnings} | ${p.share.label} | **${rupiah(p.payout)}** |\n")
        }
        sb.append("\nTotal dibagikan: ${rupiah(result.totalPaid)}")
        if (result.totalPaid != result.pool) sb.append(" (selisih pembulatan ${rupiah(result.pool - result.totalPaid)})")
        sb.append(".\n")
        val details = result.people.flatMap { p -> p.absences.map { p.employee.name to it } }
        if (details.isNotEmpty()) {
            sb.append("\nRincian tidak masuk:\n")
            details.forEach { (name, a) ->
                sb.append("- ${a.date} ${mdCell(name)}: ${if (a.halfDays >= 2) "1 hari" else "½ hari"} (${mdCell(a.reason)})\n")
            }
        }
        return sb.toString()
    }

    /** Teks bebas di sel tabel Markdown: "|" dan baris baru akan merusak kolom. */
    private fun mdCell(s: String): String = s.replace('|', '/').replace('\r', ' ').replace('\n', ' ').trim()

    private fun csvCell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    val DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
}
