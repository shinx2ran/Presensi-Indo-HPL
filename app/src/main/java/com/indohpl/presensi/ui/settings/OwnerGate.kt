package com.indohpl.presensi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.data.Repository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val PIN_MIN = 4
internal const val PIN_MAX = 8

/**
 * Gerbang PIN owner. Isi [content] hanya tampil setelah PIN benar.
 * Status "terbuka" hanya disimpan dalam memori layar ini: pindah tab = terkunci lagi.
 * Belum ada PIN -> owner diminta membuat PIN dulu.
 */
@Composable
fun OwnerGate(repository: Repository, content: @Composable () -> Unit) {
    val hasPin by repository.settingsStore.hasOwnerPin.collectAsStateWithLifecycle(null)
    var unlocked by remember { mutableStateOf(false) }
    var failedWarning by remember { mutableIntStateOf(0) }

    when {
        unlocked -> content()
        hasPin == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        }
        hasPin == false -> CreatePinScreen(repository) { unlocked = true }
        else -> EnterPinScreen(repository) { failedBefore -> failedWarning = failedBefore; unlocked = true }
    }

    if (failedWarning > 0) {
        AlertDialog(
            onDismissRequest = { failedWarning = 0 },
            title = { Text("Ada yang mencoba PIN") },
            text = { Text(pinFailedWarningText(failedWarning)) },
            confirmButton = { TextButton(onClick = { failedWarning = 0 }) { Text("Mengerti") } },
        )
    }
}

/** Kalimat peringatan untuk owner setelah berhasil masuk, bila sebelumnya ada PIN salah. */
internal fun pinFailedWarningText(failedBefore: Int): String =
    "Sebelum Anda masuk, ada $failedBefore kali PIN salah. Kalau itu bukan Anda, ganti PIN di bagian bawah Pengaturan."

@Composable
private fun CreatePinScreen(repository: Repository, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    PinLayout(
        title = "Buat PIN owner",
        subtitle = "Tab Pengaturan (aturan, uang rajin, lokasi toko, ekspor) hanya untuk owner. Buat PIN $PIN_MIN–$PIN_MAX angka. Simpan baik-baik: PIN yang lupa hanya bisa direset dengan menghapus data aplikasi.",
        error = error,
    ) {
        PinField(pin1, { pin1 = it }, "PIN baru")
        Spacer(Modifier.height(8.dp))
        PinField(pin2, { pin2 = it }, "Ulangi PIN")
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                when {
                    pin1.length < PIN_MIN -> error = "PIN minimal $PIN_MIN angka"
                    pin1 != pin2 -> error = "PIN tidak sama"
                    else -> scope.launch {
                        repository.settingsStore.setOwnerPin(pin1)
                        onDone()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Simpan PIN") }
    }
}

@Composable
private fun EnterPinScreen(repository: Repository, onUnlocked: (failedBefore: Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val lock = rememberPinLock(repository)
    val locked = lock.isLocked

    fun submit() {
        if (locked) return
        scope.launch {
            val r = repository.settingsStore.checkOwnerPin(pin)
            pin = ""
            when {
                r.ok -> onUnlocked(r.failedBefore)
                r.lockedUntil > 0L -> { lock.lockUntil(r.lockedUntil); error = "Terlalu banyak percobaan." }
                else -> error = "PIN salah (${r.attemptsLeft} percobaan lagi)"
            }
        }
    }

    PinLayout(
        title = "Khusus owner",
        subtitle = "Masukkan PIN owner untuk membuka Pengaturan.",
        error = if (locked) lock.label() else error,
    ) {
        PinField(pin, { pin = it }, "PIN owner", enabled = !locked, onDone = { submit() })
        Spacer(Modifier.height(16.dp))
        Button(onClick = { submit() }, enabled = !locked && pin.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Buka") }
    }
}

/** Status kunci PIN yang dibaca dari penyimpanan saat layar/dialog dibuka, lengkap dengan hitung mundur. */
internal class PinLockState {
    var lockedUntil by mutableLongStateOf(0L)
    var now by mutableLongStateOf(System.currentTimeMillis())
    val isLocked: Boolean get() = now < lockedUntil
    fun lockUntil(until: Long) {
        now = System.currentTimeMillis()
        lockedUntil = until
    }
    fun label(): String {
        val s = ((lockedUntil - now) / 1000) + 1
        return if (s >= 60) "Terkunci ${s / 60} menit ${s % 60} detik." else "Terkunci $s detik."
    }
}

@Composable
internal fun rememberPinLock(repository: Repository): PinLockState {
    val state = remember { PinLockState() }
    LaunchedEffect(Unit) { state.lockUntil(repository.settingsStore.ownerPinLockedUntil()) }
    LaunchedEffect(state.lockedUntil) {
        while (System.currentTimeMillis() < state.lockedUntil) {
            state.now = System.currentTimeMillis()
            delay(500)
        }
        state.now = System.currentTimeMillis()
    }
    return state
}

@Composable
private fun PinLayout(title: String, subtitle: String, error: String?, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        content()
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun PinField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(PIN_MAX)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}
