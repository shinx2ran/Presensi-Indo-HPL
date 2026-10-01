package com.indohpl.presensi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.cloud.CloudSync
import com.indohpl.presensi.cloud.FirebaseCloud
import com.indohpl.presensi.data.CloudConfig
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.ui.theme.Green
import kotlinx.coroutines.launch

/** Pengaturan sinkronisasi ke Firebase (Firestore). Khusus owner. */
@Composable
fun CloudSection(repository: Repository, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by repository.settingsStore.cloud.collectAsStateWithLifecycle(null)
    val unsynced by repository.attendanceDao.observeUnsyncedCount().collectAsStateWithLifecycle(0)
    val lastMessage by CloudSync.lastMessage.collectAsStateWithLifecycle()

    var enabled by remember { mutableStateOf(false) }
    var projectId by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(saved) {
        val c = saved ?: return@LaunchedEffect
        if (!loaded) {
            enabled = c.enabled; projectId = c.projectId; appId = c.appId; apiKey = c.apiKey; email = c.email; password = c.password
            loaded = true
        }
    }

    fun current() = CloudConfig(enabled, projectId.trim(), appId.trim(), apiKey.trim(), email.trim(), password)

    fun save(thenMessage: String = "Pengaturan cloud disimpan") {
        val c = current()
        if (c.enabled && !c.isComplete) { onMessage("Lengkapi semua kolom Firebase dulu"); return }
        scope.launch {
            repository.settingsStore.saveCloud(c)
            if (c.enabled) CloudSync.schedule(context)
            onMessage(thenMessage)
        }
    }

    fun test() {
        val c = current()
        if (!c.isComplete) { onMessage("Lengkapi semua kolom Firebase dulu"); return }
        testing = true
        scope.launch {
            val result = runCatching { FirebaseCloud(context, c).test() }
            testing = false
            result.onSuccess { msg ->
                repository.settingsStore.saveCloud(c.copy(enabled = true))
                enabled = true
                CloudSync.schedule(context)
                onMessage("$msg. Sinkronisasi diaktifkan.")
            }.onFailure { e ->
                onMessage("Gagal: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sinkronisasi cloud (Firebase)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Setiap presensi dikirim ke Firestore milik Anda (gratis, paket Spark). Dari HP Anda sendiri, pasang aplikasi ini, isi data yang sama, lalu di Rekap pilih sumber \"Cloud\". Panduan membuat proyek Firebase ada di berkas docs/FIREBASE.md di repo.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Kirim presensi ke cloud")
                Text(
                    when {
                        !enabled -> "Nonaktif"
                        unsynced > 0 -> "$unsynced presensi menunggu dikirim" + if (lastMessage.isNotBlank()) " · $lastMessage" else ""
                        else -> "Semua tersinkron" + if (lastMessage.isNotBlank()) " · $lastMessage" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled && unsynced == 0) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it; save(if (it) "Cloud diaktifkan" else "Cloud dinonaktifkan") })
        }
        OutlinedTextField(value = projectId, onValueChange = { projectId = it }, label = { Text("Project ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("presensi-indo-hpl") })
        OutlinedTextField(value = appId, onValueChange = { appId = it }, label = { Text("App ID (mobilesdk_app_id)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("1:1234567890:android:abc123") })
        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API key (current_key)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("AIza…") })
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email login Firebase") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password login Firebase") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { test() }, enabled = !testing, modifier = Modifier.weight(1f)) { Text(if (testing) "Menguji…" else "Hubungkan & uji") }
            OutlinedButton(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("Simpan") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { CloudSync.schedule(context); onMessage("Sinkronisasi dijalankan") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Sinkron sekarang") }
            OutlinedButton(onClick = { scope.launch { repository.resyncAll(); onMessage("Semua data akan dikirim ulang") } }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Kirim ulang semua") }
        }
        Spacer(Modifier.height(4.dp))
    }
}
