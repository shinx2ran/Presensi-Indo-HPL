package com.indohpl.presensi.data.backup

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Menulis file cadangan ke folder publik Download/PresensiIndoHPL supaya TETAP ADA walau aplikasi dihapus.
 * Android 10+ lewat MediaStore (tanpa izin); Android 9 ke bawah lewat izin WRITE_EXTERNAL_STORAGE.
 */
class LocalBackup(context: Context) {
    private val appContext: Context = context.applicationContext

    val folderLabel = "Download/PresensiIndoHPL"

    fun needsStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /** Menulis [json] ke file `presensi-cadangan-yyyy-MM-dd.json`; hanya 7 cadangan terbaru yang disimpan. */
    suspend fun write(json: String, date: LocalDate): Result<String> = withContext(Dispatchers.IO) {
        try {
            val name = "$FILE_PREFIX$date$FILE_SUFFIX"
            // File cadangan hari ini yang LEBIH BESAR (mis. dari sebelum aplikasi dipasang ulang) tidak ditimpa;
            // tulis ke file kedua hari itu "-baru" supaya data lama tetap bisa dipulihkan.
            val alt = "$FILE_PREFIX$date-baru$FILE_SUFFIX"
            val written = if (Build.VERSION.SDK_INT >= 29) {
                writeMediaStore(name, alt, json).also { pruneMediaStore() }
            } else {
                if (needsStoragePermission()) throw IllegalStateException("Izin penyimpanan belum diberikan")
                writeLegacyFile(name, alt, json).also { pruneLegacyFiles() }
            }
            Result.success(written)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Titik awal pemilih file (SAF) supaya langsung membuka folder cadangan. */
    fun pickerInitialUri(): Uri =
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/PresensiIndoHPL")

    // ---------- Android 10+ (MediaStore) ----------

    @RequiresApi(29)
    private fun writeMediaStore(primary: String, alt: String, json: String): String {
        val resolver = appContext.contentResolver
        val bytes = json.toByteArray(Charsets.UTF_8)
        var name = primary
        var existing = queryEntries(primary).firstOrNull()
        if (existing != null) {
            val oldUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existing.first)
            val oldSize = runCatching { resolver.openFileDescriptor(oldUri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
            if (oldSize > bytes.size) {
                name = alt
                existing = queryEntries(alt).firstOrNull()
            }
        }
        if (existing != null) {
            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existing.first)
            try {
                resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IOException("Gagal membuka file cadangan")
                return name
            } catch (e: Exception) {
                // Tidak bisa menimpa: hapus lalu buat baru.
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Gagal membuat file cadangan")
        try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IOException("Gagal membuka file cadangan")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return name
    }

    /** Entri (id, nama) di folder cadangan yang terlihat oleh aplikasi ini. [namePattern] memakai LIKE / sama persis. */
    @RequiresApi(29)
    private fun queryEntries(namePattern: String): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        // RELATIVE_PATH disimpan MediaStore dengan "/" di akhir; terima dua bentuk supaya aman.
        val rel = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
        val selection = "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?) " +
            "AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("$rel/", rel, namePattern)
        appContext.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.MediaColumns.DISPLAY_NAME} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (c.moveToNext()) out += c.getLong(idCol) to c.getString(nameCol)
        }
        return out
    }

    @RequiresApi(29)
    private fun pruneMediaStore() {
        val resolver = appContext.contentResolver
        // Terbaru dulu (nama berisi tanggal, jadi urut nama = urut tanggal).
        queryEntries("$FILE_PREFIX%$FILE_SUFFIX").sortedByDescending { it.second }.drop(KEEP).forEach { (id, _) ->
            runCatching { resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
        }
    }

    // ---------- Android 9 ke bawah (file biasa) ----------

    @Suppress("DEPRECATION")
    private fun legacyDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    private fun writeLegacyFile(primary: String, alt: String, json: String): String {
        val dir = legacyDir()
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Gagal membuat folder $folderLabel")
        val bytes = json.toByteArray(Charsets.UTF_8)
        val old = File(dir, primary)
        val name = if (old.isFile && old.length() > bytes.size) alt else primary
        File(dir, name).writeBytes(bytes)
        return name
    }

    private fun pruneLegacyFiles() {
        legacyDir().listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val FOLDER = "PresensiIndoHPL"
        const val FILE_PREFIX = "presensi-cadangan-"
        const val FILE_SUFFIX = ".json"
        const val KEEP = 14 // sampai 2 file per hari (biasa + "-baru"), kira-kira seminggu
    }
}
