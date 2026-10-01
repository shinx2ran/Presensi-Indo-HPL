package com.indohpl.presensi.cloud

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.indohpl.presensi.data.CloudConfig
import java.time.YearMonth
import kotlinx.coroutines.tasks.await

/**
 * Akses Firestore memakai konfigurasi yang dimasukkan owner (tanpa google-services.json).
 * Setiap pemanggilan memastikan FirebaseApp bernama "presensi" sudah diinisialisasi dan user sudah login.
 */
class FirebaseCloud(private val context: Context, private val config: CloudConfig) {
    private val appName = "presensi"

    private fun app(): FirebaseApp {
        FirebaseApp.getApps(context).firstOrNull { it.name == appName }?.let { existing ->
            // Konfigurasi berubah -> buat ulang.
            if (existing.options.projectId == config.projectId && existing.options.apiKey == config.apiKey) return existing
            existing.delete()
        }
        val options = FirebaseOptions.Builder()
            .setProjectId(config.projectId)
            .setApplicationId(config.appId)
            .setApiKey(config.apiKey)
            .build()
        return FirebaseApp.initializeApp(context.applicationContext, options, appName)
    }

    private suspend fun db(): FirebaseFirestore {
        val app = app()
        val auth = FirebaseAuth.getInstance(app)
        val user = auth.currentUser
        if (user == null || user.email != config.email) {
            auth.signInWithEmailAndPassword(config.email, config.password).await()
        }
        return FirebaseFirestore.getInstance(app)
    }

    private suspend fun collection() = db().collection("attendance")

    /** Uji koneksi: login + tulis dokumen kecil ke koleksi "meta". */
    suspend fun test(): String {
        val d = db()
        d.collection("meta").document("ping").set(mapOf("at" to FieldValue.serverTimestamp(), "from" to deviceName())).await()
        val user = FirebaseAuth.getInstance(app()).currentUser
        return "Terhubung ke proyek ${config.projectId} sebagai ${user?.email ?: "?"}"
    }

    suspend fun upload(docId: String, data: Map<String, Any?>) {
        val payload = data.toMutableMap()
        payload["uploadedAt"] = FieldValue.serverTimestamp()
        collection().document(docId).set(payload).await()
    }

    suspend fun delete(docId: String) {
        collection().document(docId).delete().await()
    }

    suspend fun getMonth(ym: YearMonth): List<CloudRecord> {
        val snap = collection()
            .whereGreaterThanOrEqualTo("date", ym.atDay(1).toString())
            .whereLessThanOrEqualTo("date", ym.atEndOfMonth().toString())
            .get()
            .await()
        return snap.documents.mapNotNull { doc -> doc.data?.let { CloudMapper.fromMap(it) } }
            .sortedWith(compareBy({ it.record.date }, { it.record.timestamp }))
    }

    companion object {
        fun deviceName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
    }
}
