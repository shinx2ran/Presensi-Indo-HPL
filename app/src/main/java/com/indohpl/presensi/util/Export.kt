package com.indohpl.presensi.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Bagikan / salin hasil rekap ke aplikasi lain (Claude, WhatsApp, Drive, dll). */
object Export {
    private fun exportDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun writeText(context: Context, fileName: String, content: String): File =
        File(exportDir(context), fileName).apply { writeText(content, Charsets.UTF_8) }

    fun shareFiles(context: Context, files: List<File>, mimeType: String, subject: String) {
        val authority = "${context.packageName}.fileprovider"
        val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first()) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        }
        intent.type = mimeType
        intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, subject))
    }

    fun shareText(context: Context, text: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, subject))
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
