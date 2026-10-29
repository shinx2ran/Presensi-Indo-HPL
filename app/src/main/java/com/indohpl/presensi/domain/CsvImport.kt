package com.indohpl.presensi.domain

import com.indohpl.presensi.data.Status
import java.time.LocalDate
import java.time.LocalTime

/** Satu baris presensi hasil baca file CSV detail (ekspor "presensi_YYYY-MM_detail.csv"). */
data class ImportRow(
    val line: Int,
    val date: LocalDate,
    val name: String,
    val status: String,
    val timeIn: String,          // HH:mm:ss atau kosong (IZIN/SAKIT)
    val lateMinutes: Int,
    val note: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Float?,
    val distanceMeters: Int?,
    val inLocation: Boolean,
    val locationNote: String,
)

data class ParseResult(val rows: List<ImportRow>, val errors: List<String>)

/**
 * Pembaca CSV detail presensi. Menerima semua format ekspor yang pernah dibuat aplikasi ini
 * (versi awal tanpa kolom GPS, versi GPS, dan varian dengan kolom jarak_<tempat>_m), karena
 * kolom dicari berdasarkan nama header, bukan posisi. Murni Kotlin supaya bisa diuji.
 */
object CsvImport {
    private val VALID_STATUS = setOf(Status.TEPAT, Status.TELAT, Status.IZIN, Status.SAKIT)

    fun parse(text: String): ParseResult {
        val table = parseCsv(text.removePrefix("﻿"))
        if (table.isEmpty()) return ParseResult(emptyList(), listOf("File kosong"))
        val header = table.first().map { it.trim().lowercase() }
        fun col(name: String) = header.indexOf(name)
        val iDate = col("tanggal")
        val iName = col("nama")
        val iStatus = col("status")
        val iTime = col("jam_masuk")
        if (iDate < 0 || iName < 0 || iStatus < 0 || iTime < 0) {
            return ParseResult(emptyList(), listOf("Bukan file detail presensi (butuh kolom tanggal, nama, status, jam_masuk)"))
        }
        val iLate = col("menit_telat")
        val iNote = col("catatan")
        val iLat = col("latitude")
        val iLon = col("longitude")
        val iAcc = col("akurasi_m")
        val iDist = header.indexOfFirst { it.startsWith("jarak_") && it.endsWith("_m") }
        val iInLoc = col("di_lokasi")
        val iLocNote = col("ket_lokasi")

        val rows = mutableListOf<ImportRow>()
        val errors = mutableListOf<String>()
        table.drop(1).forEachIndexed { idx, cells ->
            val line = idx + 2
            if (cells.all { it.isBlank() }) return@forEachIndexed
            fun cell(i: Int) = if (i in cells.indices) cells[i].trim() else ""
            val date = runCatching { LocalDate.parse(cell(iDate)) }.getOrNull()
            val name = cell(iName)
            val status = cell(iStatus).uppercase()
            val time = cell(iTime)
            when {
                date == null -> errors += "Baris $line: tanggal tidak valid \"${cell(iDate)}\""
                name.isEmpty() -> errors += "Baris $line: nama kosong"
                status !in VALID_STATUS -> errors += "Baris $line: status tidak dikenal \"$status\""
                (status == Status.TEPAT || status == Status.TELAT) && runCatching { LocalTime.parse(time) }.isFailure ->
                    errors += "Baris $line: jam masuk tidak valid \"$time\""
                else -> {
                    val hadir = status == Status.TEPAT || status == Status.TELAT
                    rows += ImportRow(
                        line = line,
                        date = date,
                        name = name,
                        status = status,
                        timeIn = if (hadir) LocalTime.parse(time).withNano(0).toString().let { if (it.length == 5) "$it:00" else it } else "",
                        lateMinutes = cell(iLate).toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        note = cell(iNote),
                        latitude = cell(iLat).toDoubleOrNull(),
                        longitude = cell(iLon).toDoubleOrNull(),
                        accuracy = cell(iAcc).toFloatOrNull(),
                        distanceMeters = cell(iDist).toIntOrNull(),
                        inLocation = cell(iInLoc).equals("YA", ignoreCase = true),
                        locationNote = cell(iLocNote),
                    )
                }
            }
        }
        return ParseResult(rows, errors)
    }

    /** CSV sederhana sesuai RFC 4180: pemisah koma, sel berkutip ganda boleh berisi koma/kutip/baris baru. */
    fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else {
                    cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    ',' -> { row += cell.toString(); cell.clear() }
                    '\r' -> {}
                    '\n' -> { row += cell.toString(); cell.clear(); rows += row.toList(); row.clear() }
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row.toList() }
        return rows
    }
}
