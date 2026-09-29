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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

private const val PIN_MIN = 4
private const val PIN_MAX = 8
private const val MAX_ATTEMPTS = 5
private const val LOCK_MS = 30_000L

/**
 * Gerbang PIN owner. Isi [content] hanya tampil setelah PIN benar.
 * Status "terbuka" hanya disimpan dalam memori layar ini: pindah tab = terkunci lagi.
 * Belum ada PIN -> owner diminta membuat PIN dulu.
 */
@Composable
fun OwnerGate(repository: Repository, content: @Composable () -> Unit) {
    val hasPin by repository.settingsStore.hasOwnerPin.collectAsStateWithLifecycle(null)
    var unlocked by remember { mutableStateOf(false) }

    when {
        unlocked -> content()
        hasPin == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        }
        hasPin == false -> CreatePinScreen(repository) { unlocked = true }
        else -> EnterPinScreen(repository) { unlocked = true }
    }
}

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
private fun EnterPinScreen(repository: Repository, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableIntStateOf(0) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val locked = now < lockedUntil

    LaunchedEffect(lockedUntil) {
        while (System.currentTimeMillis() < lockedUntil) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }

    fun submit() {
        if (locked) return
        scope.launch {
            if (repository.settingsStore.verifyOwnerPin(pin)) {
                onUnlocked()
            } else {
                pin = ""
                attempts++
                if (attempts >= MAX_ATTEMPTS) {
                    attempts = 0
                    lockedUntil = System.currentTimeMillis() + LOCK_MS
                    error = "Terlalu banyak percobaan."
                } else {
                    error = "PIN salah (${MAX_ATTEMPTS - attempts} percobaan lagi)"
                }
            }
        }
    }

    PinLayout(
        title = "Khusus owner",
        subtitle = "Masukkan PIN owner untuk membuka Pengaturan.",
        error = if (locked) "Terkunci ${((lockedUntil - now) / 1000) + 1} detik." else error,
    ) {
        PinField(pin, { pin = it }, "PIN owner", enabled = !locked, onDone = { submit() })
        Spacer(Modifier.height(16.dp))
        Button(onClick = { submit() }, enabled = !locked && pin.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Buka") }
    }
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
private fun PinField(
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
