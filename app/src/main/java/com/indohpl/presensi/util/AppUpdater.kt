package com.indohpl.presensi.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.indohpl.presensi.BuildConfig
import com.indohpl.presensi.domain.UpdateManifest
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Keadaan pembaruan OTA, dibagi oleh Beranda dan Pengaturan. */
sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpToDate(val message: String) : UpdateState()
    data class Available(val manifest: UpdateManifest) : UpdateState()
    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdateState() // -1 = tidak diketahui
    data class Ready(val manifest: UpdateManifest, val file: File) : UpdateState()
    data class Failed(val message: String, val manifest: UpdateManifest?) : UpdateState()
}

/**
 * Pembaruan aplikasi lewat internet (OTA) tanpa Play Store:
 * 1. baca `update-<varian>.json` di branch apk-build (diterbitkan CI tiap build bertanda tangan tetap),
 * 2. bila versinya lebih baru, unduh APK, cek SHA-256 + nama paket + nomor versi,
 * 3. buka dialog pasang Android (pengguna cukup menekan "Perbarui"). Data presensi tetap ada.
 */
object AppUpdater {
    private const val RAW_BASE = "https://raw.githubusercontent.com/shinx2ran/Presensi-Indo-HPL/apk-build"
    private const val AUTO_CHECK_INTERVAL_MS = 30 * 60_000L

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    @Volatile private var lastAutoCheck = 0L
    private val checkLock = Mutex()

    val installedVersionCode: Int get() = BuildConfig.VERSION_CODE
    val installedVersionName: String get() = BuildConfig.VERSION_NAME
    private val manifestUrl: String get() = "$RAW_BASE/update-${BuildConfig.FLAVOR}.json"

    /** Cek otomatis (Beranda/Pengaturan dibuka), paling sering sekali per 30 menit, diam bila gagal. */
    suspend fun checkIfDue(context: Context) {
        if (System.currentTimeMillis() - lastAutoCheck < AUTO_CHECK_INTERVAL_MS) return
        check(context, userInitiated = false)
    }

    suspend fun check(context: Context, userInitiated: Boolean) {
        val current = _state.value
        if (current is UpdateState.Downloading || current is UpdateState.Ready) return // jangan ganggu yang sedang berjalan
        if (!checkLock.tryLock()) return
        try {
            lastAutoCheck = System.currentTimeMillis()
            if (userInitiated) _state.value = UpdateState.Checking
            val text = withContext(Dispatchers.IO) {
                // Parameter t = penangkal cache CDN GitHub (berubah tiap menit).
                httpGetText("$manifestUrl?t=${System.currentTimeMillis() / 60_000}")
            }
            val m = UpdateManifest.parse(text)
            _state.value = when {
                m != null && UpdateManifest.isNewer(m, installedVersionCode) -> UpdateState.Available(m)
                !userInitiated -> UpdateState.Idle
                text == null -> UpdateState.UpToDate("Belum ada pembaruan otomatis yang diterbitkan.")
                m == null -> UpdateState.Failed("Info pembaruan tidak terbaca. Coba lagi nanti.", null)
                else -> UpdateState.UpToDate("Sudah versi terbaru ($installedVersionName).")
            }
        } catch (e: CancellationException) {
            _state.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = if (userInitiated) {
                UpdateState.Failed("Gagal memeriksa pembaruan (cek internet): ${e.message ?: e.javaClass.simpleName}", null)
            } else {
                UpdateState.Idle
            }
        } finally {
            checkLock.unlock()
        }
    }

    /** Unduh di [scope] milik aplikasi, jadi tetap berjalan walau pengguna pindah layar. */
    fun download(scope: CoroutineScope, context: Context, manifest: UpdateManifest) {
        if (_state.value is UpdateState.Downloading) return
        val app = context.applicationContext
        _state.value = UpdateState.Downloading(manifest, -1f)
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val part = File(dir, "download.part")
                val apk = File(dir, "${BuildConfig.FLAVOR}-${manifest.versionCode}.apk")
                val digest = MessageDigest.getInstance("SHA-256")
                val conn = open(manifest.apkUrl)
                try {
                    if (conn.responseCode !in 200..299) error("server menjawab HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: manifest.sizeBytes
                    conn.inputStream.use { input ->
                        FileOutputStream(part).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastEmit = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                done += n
                                if (total > 0 && done - lastEmit >= 256 * 1024) {
                                    lastEmit = done
                                    _state.value = UpdateState.Downloading(manifest, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (manifest.sha256.isNotBlank() && hash != manifest.sha256) {
                    part.delete()
                    error("file rusak saat diunduh (checksum tidak cocok)")
                }
                if (!part.renameTo(apk)) error("gagal menyimpan file")
                @Suppress("DEPRECATION")
                val info = app.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                if (info == null || info.packageName != app.packageName) {
                    apk.delete()
                    error("file bukan pembaruan untuk aplikasi ini")
                }
                if (PackageInfoCompat.getLongVersionCode(info) <= installedVersionCode) {
                    apk.delete()
                    error("versi file tidak lebih baru dari yang terpasang")
                }
                _state.value = UpdateState.Ready(manifest, apk)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(manifest)
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed("Gagal mengunduh pembaruan: ${e.message ?: e.javaClass.simpleName}", manifest)
            }
        }
    }

    /**
     * Buka dialog pasang Android. Mengembalikan false bila izin "Pasang aplikasi tidak dikenal" untuk aplikasi ini
     * belum diberikan; halaman izinnya dibuka, lalu pengguna kembali dan menekan Pasang lagi.
     */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    /** Hapus APK lama yang sudah terpasang (dipanggil saat aplikasi dibuka). */
    fun cleanup(context: Context) {
        val dir = File(context.cacheDir, "updates")
        val pattern = Regex("""${Regex.escape(BuildConfig.FLAVOR)}-(\d+)\.apk""")
        dir.listFiles()?.forEach { f ->
            val code = pattern.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
            if (code == null || code <= installedVersionCode) f.delete()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }

    /** null bila berkas belum ada (404). */
    private fun httpGetText(url: String): String? {
        val c = open(url)
        try {
            if (c.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
