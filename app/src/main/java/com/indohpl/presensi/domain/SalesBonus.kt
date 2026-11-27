package com.indohpl.presensi.domain

import com.indohpl.presensi.data.AttendanceRecord
import com.indohpl.presensi.data.BonusEvent
import com.indohpl.presensi.data.BonusEventType
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Status
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** Satu tingkat target: penjualan bulan itu minimal [minSales] -> bonus [perPerson] per orang. */
data class SalesTier(val minSales: Long, val perPerson: Long)

/** Pengaturan bonus penjualan (bisa diubah owner; target ditinjau ulang setelah masa percobaan). */
data class SalesBonusConfig(
    val tiers: List<SalesTier> = SalesBonus.DEFAULT_TIERS,
    val startMonth: YearMonth = SalesBonus.DEFAULT_START,
) {
    /** Tingkat yang valid, urut dari target terkecil. */
    val sortedTiers: List<SalesTier> get() = tiers.filter { it.minSales > 0 && it.perPerson > 0 }.sortedBy { it.minSales }

    fun appliesTo(ym: YearMonth): Boolean = !ym.isBefore(startMonth)
}

/**
 * Bagian bonus menurut kehadiran. [quarters] = bagian dalam perempat (penuh = 4).
 * "Masuk terus" penuh; tidak masuk sampai 1 hari 3/4; sampai 2 hari 1/4; lebih dari 2 hari tidak dapat.
 */
enum class BonusShare(val quarters: Int, val label: String) {
    FULL(4, "penuh"),
    THREE_QUARTERS(3, "¾"),
    ONE_QUARTER(1, "¼"),
    NONE(0, "tidak dapat"),
}

/** Satu hari yang dihitung "tidak masuk" untuk bonus penjualan. [halfDays]: 2 = sehari, 1 = setengah hari. */
data class AbsenceItem(val date: LocalDate, val halfDays: Int, val reason: String)

data class SalesBonusPerson(
    val employee: Employee,
    val participating: Boolean,
    /** Tidak masuk dalam setengah hari (2 = 1 hari). */
    val absentHalfDays: Int,
    val absences: List<AbsenceItem>,
    val warnings: Int,
    /** Bagian menurut kehadiran saja (untuk ditampilkan ke karyawan). */
    val attendanceShare: BonusShare,
    /** Bagian akhir: kehadiran, lalu teguran ke-2 dalam sebulan = tidak dapat. */
    val share: BonusShare,
    val payout: Long,
) {
    /** Tidak masuk dalam hari, mis. 1.5. */
    val absentDays: Double get() = absentHalfDays / 2.0
}

data class SalesBonusResult(
    val ym: YearMonth,
    val netSales: Long,
    /** Tingkat yang tercapai; null = belum mencapai target terendah (atau bulan sebelum program berlaku). */
    val tier: SalesTier?,
    /** Target berikutnya; null = target tertinggi sudah tercapai. */
    val nextTier: SalesTier?,
    /** Kurang berapa lagi ke [nextTier]; 0 bila tidak ada target berikutnya. */
    val shortfall: Long,
    val participants: Int,
    /** Uang bonus yang dikumpulkan = bonus per orang x jumlah peserta. */
    val pool: Long,
    val people: List<SalesBonusPerson>,
) {
    val totalPaid: Long get() = people.sumOf { it.payout }
}

/**
 * Bonus penjualan toko (berlaku Desember 2026): penjualan bulanan mencapai target -> bonus per orang.
 * Uang bonus dikumpulkan (bonus per orang x peserta), lalu dibagi menurut bagian kehadiran; bagian yang
 * berkurang otomatis jatuh ke teman yang lain. Murni Kotlin supaya bisa diuji.
 */
object SalesBonus {
    val DEFAULT_TIERS = listOf(
        SalesTier(600_000_000L, 500_000L),
        SalesTier(650_000_000L, 700_000L),
        SalesTier(700_000_000L, 900_000L),
        SalesTier(750_000_000L, 1_100_000L),
    )
    val DEFAULT_START: YearMonth = YearMonth.of(2026, 12)

    /** "Terlambat lebih dari 2 jam" dihitung tidak masuk setengah hari. Menit telat diukur dari jam masuk resmi. */
    const val LATE_HALF_DAY_MINUTES = 120

    /** Teguran ke-2 dalam sebulan = tidak dapat bonus. */
    const val WARNINGS_FOR_NO_BONUS = 2

    fun tierFor(config: SalesBonusConfig, netSales: Long): SalesTier? =
        config.sortedTiers.lastOrNull { netSales >= it.minSales }

    fun nextTierFor(config: SalesBonusConfig, netSales: Long): SalesTier? =
        config.sortedTiers.firstOrNull { netSales < it.minSales }

    /** Penjualan yang dihitung: penjualan Toqoo dikurangi proyek CE LENY, tidak pernah negatif. */
    fun netSales(grossSales: Long, excludedSales: Long): Long = (grossSales - excludedSales).coerceAtLeast(0L)

    /** Bagian menurut jumlah setengah hari tidak masuk. */
    fun attendanceShare(absentHalfDays: Int): BonusShare = when {
        absentHalfDays <= 0 -> BonusShare.FULL
        absentHalfDays <= 2 -> BonusShare.THREE_QUARTERS // sampai 1 hari
        absentHalfDays <= 4 -> BonusShare.ONE_QUARTER // sampai 2 hari
        else -> BonusShare.NONE // lebih dari 2 hari
    }

    fun finalShare(absentHalfDays: Int, warnings: Int): BonusShare =
        if (warnings >= WARNINGS_FOR_NO_BONUS) BonusShare.NONE else attendanceShare(absentHalfDays)

    /**
     * Hari "tidak masuk" satu karyawan pada bulan [ym], sampai [today]:
     * - hari kerja yang sudah lewat tanpa catatan (bolos), izin, atau sakit = 1 hari (tidak ada jatah izin);
     * - telat lebih dari 2 jam = 1/2 hari (telat yang dianulir owner sudah TEPAT, jadi tidak dihitung);
     * - catatan owner "setengah hari / pulang cepat" = 1/2 hari;
     * - per tanggal paling banyak 1 hari. Hari libur / bukan hari kerja dan tanggal setelah [today] tidak dihitung.
     *   Hari ini tanpa catatan belum dihitung (karyawan masih bisa absen).
     */
    fun absences(
        ym: YearMonth,
        today: LocalDate,
        employeeId: Long,
        records: List<AttendanceRecord>,
        events: List<BonusEvent>,
        workDayOfWeeks: Set<DayOfWeek>,
        holidays: Set<LocalDate>,
    ): List<AbsenceItem> {
        val byDate = records.filter { it.employeeId == employeeId }.associateBy { it.date }
        val halfDayEvents = events
            .filter { it.employeeId == employeeId && it.type == BonusEventType.SETENGAH_HARI }
            .groupingBy { it.date }.eachCount()
        val out = mutableListOf<AbsenceItem>()
        for (d in BonusCalculator.workDays(ym, workDayOfWeeks, holidays)) {
            if (d.isAfter(today)) break
            val key = d.toString()
            val r = byDate[key]
            val reasons = mutableListOf<String>()
            var half = 0
            when {
                r == null && d.isBefore(today) -> { half += 2; reasons += "tidak absen" }
                r?.status == Status.IZIN -> { half += 2; reasons += "izin" }
                r?.status == Status.SAKIT -> { half += 2; reasons += "sakit" }
                r?.status == Status.TELAT && r.lateMinutes > LATE_HALF_DAY_MINUTES -> {
                    half += 1; reasons += "telat ${formatMinutes(r.lateMinutes)}"
                }
            }
            val halfMarks = halfDayEvents[key] ?: 0
            if (halfMarks > 0) { half += halfMarks; reasons += "setengah hari" }
            if (half > 0) out += AbsenceItem(d, half.coerceAtMost(2), reasons.joinToString(" + "))
        }
        return out
    }

    /** Jumlah teguran pada bulan [ym]. */
    fun warnings(ym: YearMonth, employeeId: Long, events: List<BonusEvent>): Int {
        val prefix = ym.toString()
        return events.count { it.employeeId == employeeId && it.type == BonusEventType.TEGURAN && it.date.startsWith(prefix) }
    }

    /**
     * Membagi [pool] menurut bobot (perempat). Tiap orang = pool x bobot / total bobot, dibulatkan ke rupiah terdekat
     * (setengah ke atas). Orang dengan bobot sama selalu mendapat jumlah sama; total bisa selisih beberapa rupiah
     * dari [pool] karena pembulatan. Total bobot 0 = tidak ada yang dibayar.
     */
    fun distribute(pool: Long, quartersById: Map<Long, Int>): Map<Long, Long> {
        val total = quartersById.values.sumOf { it.coerceAtLeast(0).toLong() }
        if (pool <= 0L || total <= 0L) return quartersById.mapValues { 0L }
        return quartersById.mapValues { (_, q) ->
            val w = q.coerceAtLeast(0).toLong()
            (2 * pool * w + total) / (2 * total)
        }
    }

    /**
     * Hitung bonus penjualan bulan [ym].
     * [employees]: karyawan yang ditampilkan (biasanya semua yang aktif); yang ada di [excludedIds] tidak ikut
     * (mis. keluar sebelum akhir bulan, atau belum bulan penuh pertamanya) dan tidak menambah uang bonus.
     */
    fun calculate(
        ym: YearMonth,
        today: LocalDate,
        netSales: Long,
        config: SalesBonusConfig,
        employees: List<Employee>,
        excludedIds: Set<Long>,
        records: List<AttendanceRecord>,
        events: List<BonusEvent>,
        workDayOfWeeks: Set<DayOfWeek>,
        holidays: Set<LocalDate>,
    ): SalesBonusResult {
        val applies = config.appliesTo(ym)
        val tier = if (applies) tierFor(config, netSales) else null
        val next = if (applies) nextTierFor(config, netSales) else null
        val shortfall = next?.let { (it.minSales - netSales).coerceAtLeast(0L) } ?: 0L

        data class Draft(val emp: Employee, val participating: Boolean, val absences: List<AbsenceItem>, val warnings: Int)
        val drafts = employees.map { emp ->
            val abs = absences(ym, today, emp.id, records, events, workDayOfWeeks, holidays)
            Draft(emp, emp.id !in excludedIds, abs, warnings(ym, emp.id, events))
        }
        val participants = drafts.count { it.participating }
        val pool = (tier?.perPerson ?: 0L) * participants
        val quarters = drafts.filter { it.participating }.associate { d ->
            d.emp.id to finalShare(d.absences.sumOf { it.halfDays }, d.warnings).quarters
        }
        val payouts = distribute(pool, quarters)
        val people = drafts.map { d ->
            val half = d.absences.sumOf { it.halfDays }
            SalesBonusPerson(
                employee = d.emp,
                participating = d.participating,
                absentHalfDays = half,
                absences = d.absences,
                warnings = d.warnings,
                attendanceShare = attendanceShare(half),
                share = if (d.participating) finalShare(half, d.warnings) else BonusShare.NONE,
                payout = payouts[d.emp.id] ?: 0L,
            )
        }
        return SalesBonusResult(ym, netSales, tier, next, shortfall, participants, pool, people)
    }

    // ---------- Teks ----------

    private val monthNames = listOf(
        "Januari", "Februari", "Maret", "April", "Mei", "Juni",
        "Juli", "Agustus", "September", "Oktober", "November", "Desember",
    )

    /** "Rp500.000" (gaya pengumuman WA, tanpa spasi). */
    fun rp(v: Long): String = "Rp" + String.format(Locale.US, "%,d", v).replace(',', '.')

    /** 600_000_000 -> "600 juta", 612_500_000 -> "612,5 juta", 1_250_000_000 -> "1.250 juta". */
    fun juta(v: Long): String {
        val whole = v / 1_000_000
        val rest = v % 1_000_000
        val wholeText = String.format(Locale.US, "%,d", whole).replace(',', '.')
        if (rest == 0L) return "$wholeText juta"
        val frac = String.format(Locale.US, "%06d", rest).trimEnd('0')
        return "$wholeText,$frac juta"
    }

    private fun formatMinutes(m: Int): String = if (m % 60 == 0) "${m / 60} jam" else "${m / 60} jam ${m % 60} mnt"

    /** Ambil angka rupiah dari teks isian ("612.500.000", "Rp 612,500,000") -> 612500000; null bila kosong/terlalu besar. */
    fun parseRupiah(text: String): Long? {
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty() || digits.length > 15) return null
        return digits.toLong()
    }

    /** Teks "600000000:500000;650000000:700000;..." untuk disimpan; [decodeTiers] membaca kembali. */
    fun encodeTiers(tiers: List<SalesTier>): String = tiers.joinToString(";") { "${it.minSales}:${it.perPerson}" }

    /** Teks rusak/kosong -> [DEFAULT_TIERS]. */
    fun decodeTiers(text: String?): List<SalesTier> {
        if (text.isNullOrBlank()) return DEFAULT_TIERS
        val parsed = text.split(';').mapNotNull { part ->
            val (a, b) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val min = a.trim().toLongOrNull() ?: return@mapNotNull null
            val per = b.trim().toLongOrNull() ?: return@mapNotNull null
            if (min > 0 && per > 0) SalesTier(min, per) else null
        }
        return parsed.ifEmpty { DEFAULT_TIERS }
    }

    /**
     * Pengumuman untuk grup WA (tanggal 10, 20, 25, dan hasil akhir tanggal 1). Hanya angka tim; bagian per orang
     * tidak diumumkan. [asOf] = data penjualan sampai tanggal itu; [final] = bulan sudah selesai dihitung.
     */
    fun announcement(result: SalesBonusResult, config: SalesBonusConfig, asOf: LocalDate, final: Boolean): String {
        val ym = result.ym
        val monthLabel = "${monthNames[ym.monthValue - 1]} ${ym.year}"
        val sb = StringBuilder()
        sb.append("*PENJUALAN TOKO INDO HPL*\n")
        if (final) {
            sb.append("🏁 *Hasil akhir $monthLabel*\n")
        } else {
            sb.append("📊 *$monthLabel* · sampai ${asOf.dayOfMonth} ${monthNames[asOf.monthValue - 1]}\n")
        }
        sb.append("Penjualan: *${rp(result.netSales)}*\n\n")
        val tier = result.tier
        val next = result.nextTier
        when {
            tier == null && next == null -> sb.append("Target bonus penjualan belum diatur.\n")
            tier == null && final -> sb.append("❌ Belum mencapai target ${juta(next!!.minSales)} (kurang ${rp(result.shortfall)})\n")
            else -> {
                if (tier != null) sb.append("✅ Target ${juta(tier.minSales)} tercapai → *${rp(tier.perPerson)} / orang*\n")
                when {
                    final -> {}
                    next != null -> sb.append("🎯 Kurang *${rp(result.shortfall)}* lagi ke ${juta(next.minSales)} (${rp(next.perPerson)} / orang)\n")
                    else -> sb.append("🏆 Target tertinggi sudah tercapai!\n")
                }
            }
        }
        sb.append('\n')
        if (final) {
            if (tier != null) {
                val nextMonth = ym.plusMonths(1)
                sb.append("Bonus dicairkan tanggal 1 ${monthNames[nextMonth.monthValue - 1]} ${nextMonth.year}. Terima kasih semua 🙏\n")
            } else {
                sb.append("Bulan ini belum dapat bonus. Bulan depan kita kejar bersama 💪\n")
            }
        } else {
            sb.append("Ayo kejar bersama 💪 Layani pembeli dengan baik dan masuk terus.\n")
        }
        sb.append("_Angka per orang berlaku kalau semua masuk terus._")
        return sb.toString()
    }
}
