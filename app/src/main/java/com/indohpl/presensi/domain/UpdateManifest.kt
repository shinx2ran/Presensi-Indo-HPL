package com.indohpl.presensi.domain

import org.json.JSONObject

/**
 * Isi berkas `update-<varian>.json` yang diterbitkan CI di branch apk-build.
 * Murni Kotlin + org.json (bawaan Android) supaya bisa diuji unit test.
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
) {
    companion object {
        /** null bila teks bukan manifest yang sah (rusak, tanpa versi, atau URL bukan https). */
        fun parse(text: String?): UpdateManifest? {
            if (text.isNullOrBlank()) return null
            val m = runCatching {
                val o = JSONObject(text)
                UpdateManifest(
                    versionCode = o.getInt("versionCode"),
                    versionName = o.optString("versionName", o.getInt("versionCode").toString()),
                    apkUrl = o.getString("apkUrl"),
                    sha256 = o.optString("sha256", "").lowercase(),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    notes = o.optString("notes", ""),
                )
            }.getOrNull() ?: return null
            return m.takeIf { it.versionCode > 0 && it.apkUrl.startsWith("https://") }
        }

        fun isNewer(manifest: UpdateManifest, installedVersionCode: Int): Boolean = manifest.versionCode > installedVersionCode
    }
}
