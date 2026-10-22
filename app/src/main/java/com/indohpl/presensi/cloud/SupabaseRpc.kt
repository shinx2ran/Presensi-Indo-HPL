package com.indohpl.presensi.cloud

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Jawaban gagal dari RPC Supabase (PostgREST): HTTP 4xx/5xx dengan JSON `{"code": "...", "message": "..."}`.
 * [message] berbahasa Indonesia dari server dan boleh ditampilkan ke pengguna.
 */
class CloudException(val httpStatus: Int, val code: String, message: String) : Exception(message) {
    /** Kunci ditolak: `code = "28000"` (kunci tidak valid / HP sudah diputus) atau HTTP 401/403. */
    val isAuthError: Boolean get() = code == AUTH_CODE || httpStatus == 401 || httpStatus == 403

    /** Kunci HP ini sendiri yang ditolak server (HP diputus owner / kunci tidak dikenal). */
    val isKeyRejected: Boolean get() = code == AUTH_CODE

    /** Gangguan sementara di sisi server: layak dicoba lagi nanti. */
    val isTemporary: Boolean get() = httpStatus >= 500 || httpStatus == 408 || httpStatus == 429

    companion object {
        const val AUTH_CODE = "28000"
        const val INVALID_CODE = "22023"
    }
}

/**
 * Pemanggil RPC Supabase tanpa pustaka tambahan (HttpURLConnection + org.json bawaan Android).
 * `POST {URL}/rest/v1/rpc/<fungsi>` dengan header apikey; semua parameter p_* dikirim (boleh JSONObject.NULL).
 */
object SupabaseRpc {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * Mengembalikan isi jawaban (JSON hasil fungsi) sebagai teks.
     * HTTP selain 2xx -> [CloudException]; gangguan jaringan -> [IOException].
     */
    suspend fun call(function: String, params: JSONObject): String = withContext(Dispatchers.IO) {
        val body = params.toString().toByteArray(Charsets.UTF_8)
        val conn = URL("${CloudEndpoint.SUPABASE_URL}/rest/v1/rpc/$function").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.useCaches = false
            conn.doOutput = true
            conn.setRequestProperty("apikey", CloudEndpoint.PUBLISHABLE_KEY)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            if (status in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                val text = runCatching { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull()
                throw parseError(status, text)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** `{"code": "28000", "message": "..."}` -> CloudException. Jawaban bukan JSON (mis. halaman gateway) tetap aman. */
    fun parseError(status: Int, body: String?): CloudException {
        val o = runCatching { JSONObject(body ?: "") }.getOrNull()
        val code = (o?.opt("code") as? String).orEmpty()
        val message = (o?.opt("message") as? String)?.takeIf { it.isNotBlank() }
            ?: (o?.opt("msg") as? String)?.takeIf { it.isNotBlank() }
            ?: "Server menjawab HTTP $status"
        return CloudException(status, code, message)
    }
}
