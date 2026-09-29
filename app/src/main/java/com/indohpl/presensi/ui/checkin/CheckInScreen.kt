package com.indohpl.presensi.ui.checkin

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indohpl.presensi.data.AppSettings
import com.indohpl.presensi.data.Employee
import com.indohpl.presensi.data.Repository
import com.indohpl.presensi.data.Status
import com.indohpl.presensi.domain.GeoCheck
import com.indohpl.presensi.domain.GeoRule
import com.indohpl.presensi.ui.StatusPill
import com.indohpl.presensi.ui.findFragmentActivity
import com.indohpl.presensi.ui.theme.Amber
import com.indohpl.presensi.ui.theme.Green
import com.indohpl.presensi.ui.theme.Red
import com.indohpl.presensi.util.Biometric
import com.indohpl.presensi.util.BiometricResult
import com.indohpl.presensi.util.LocationHelper
import com.indohpl.presensi.util.PhotoStamper
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { LOADING, BIOMETRIC, CAMERA, PROCESSING, CONFIRM, SAVED }

private data class Shot(val file: File, val capturedAt: LocalDateTime, val preview: ImageBitmap?, val geo: GeoCheck?)

private enum class GeoState { IDLE, SEARCHING, FOUND, FAILED }

private val FILE_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    repository: Repository,
    employeeId: Long,
    onFinished: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var employee by remember { mutableStateOf<Employee?>(null) }
    var step by remember { mutableStateOf(Step.LOADING) }
    var error by remember { mutableStateOf<String?>(null) }
    var shot by remember { mutableStateOf<Shot?>(null) }
    var savedStatus by remember { mutableStateOf<Pair<String, Int>?>(null) } // status, menit telat
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var hasLocationPermission by remember { mutableStateOf(LocationHelper.hasPermission(context)) }
    var geoAttempt by remember { mutableIntStateOf(0) }
    // Satu dialog izin untuk kamera + lokasi sekaligus.
    val allPermissions = remember { arrayOf(Manifest.permission.CAMERA) + LocationHelper.PERMISSIONS }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasCameraPermission = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasLocationPermission = LocationHelper.hasPermission(context)
        if (!hasCameraPermission) error = "Izin kamera ditolak. Aktifkan izin kamera untuk aplikasi ini di Pengaturan HP."
        if (hasLocationPermission) geoAttempt++
    }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    // ---- Lokasi GPS ----
    val settings by repository.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
    var geoState by remember { mutableStateOf(GeoState.IDLE) }
    var geo by remember { mutableStateOf<GeoCheck?>(null) }

    // Cari posisi setiap kali masuk langkah kamera (dan setiap "Coba lagi").
    LaunchedEffect(step, geoAttempt, hasLocationPermission) {
        if (step != Step.CAMERA) return@LaunchedEffect
        if (!hasLocationPermission) {
            geoState = GeoState.FAILED
            return@LaunchedEffect
        }
        geoState = GeoState.SEARCHING
        val loc = LocationHelper.current(context)
        if (loc == null) {
            geo = null
            geoState = GeoState.FAILED
        } else {
            geo = GeoRule.check(
                latitude = loc.latitude,
                longitude = loc.longitude,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                isMock = LocationHelper.isMock(loc),
                storeLat = settings.storeLat,
                storeLon = settings.storeLon,
                radiusMeters = settings.radiusMeters,
            )
            geoState = GeoState.FOUND
        }
    }

    // Boleh jepret? Jika "wajib di lokasi" aktif dan titik toko sudah diatur: harus dapat GPS dan di dalam radius.
    val locationRequired = settings.requireLocation && settings.hasStoreLocation
    val canCapture = !locationRequired || (geo?.inLocation == true)

    fun runBiometric() {
        val emp = employee ?: return
        val activity = context.findFragmentActivity()
        if (activity == null) {
            error = "Tidak bisa membuka verifikasi biometrik."
            return
        }
        Biometric.availabilityProblem(context)?.let {
            error = it
            return
        }
        error = null
        step = Step.BIOMETRIC
        Biometric.authenticate(
            activity = activity,
            title = "Verifikasi presensi",
            subtitle = "Absen masuk: ${emp.name}",
        ) { result ->
            when (result) {
                BiometricResult.Success -> {
                    step = Step.CAMERA
                    if (!hasCameraPermission || !hasLocationPermission) permissionLauncher.launch(allPermissions)
                }
                is BiometricResult.Failed -> error = "Verifikasi gagal: ${result.message}"
            }
        }
    }

    LaunchedEffect(employeeId) {
        employee = repository.employeeDao.getById(employeeId)
        if (employee == null) {
            error = "Karyawan tidak ditemukan."
        } else {
            // Cek dulu apakah sudah absen hari ini.
            val existing = repository.attendanceDao.find(employeeId, java.time.LocalDate.now().toString())
            if (existing != null) {
                onFinished("${employee!!.name} sudah absen hari ini (${existing.status}${if (existing.timeIn.isNotEmpty()) " " + existing.timeIn.take(5) else ""}).")
            } else {
                runBiometric()
            }
        }
    }

    fun takePhoto() {
        val emp = employee ?: return
        step = Step.PROCESSING
        val capturedAt = LocalDateTime.now()
        val tmp = File(context.cacheDir, "capture_${System.nanoTime()}.jpg")
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
        }
        val options = ImageCapture.OutputFileOptions.Builder(tmp).setMetadata(metadata).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    scope.launch {
                        try {
                            val monthDir = File(repository.photoRoot, capturedAt.toLocalDate().toString().take(7))
                            val target = File(monthDir, "${emp.name}_${capturedAt.format(FILE_TS)}.jpg")
                            val g = geo
                            val extra = if (g == null) {
                                listOf("GPS tidak didapat")
                            } else {
                                listOf(
                                    GeoRule.formatCoord(g.latitude, g.longitude) + (g.accuracy?.let { " (±${it.toInt()} m)" } ?: ""),
                                    when {
                                        g.isMock -> "LOKASI PALSU"
                                        g.distanceMeters == null -> "Titik toko belum diatur"
                                        g.inLocation -> "Di lokasi Indo HPL · ${GeoRule.formatDistance(g.distanceMeters)} dari titik toko"
                                        else -> "DI LUAR LOKASI · ${GeoRule.formatDistance(g.distanceMeters)} dari titik toko"
                                    },
                                )
                            }
                            val preview = withContext(Dispatchers.IO) {
                                PhotoStamper.stamp(tmp, target, emp.name, capturedAt, "Presensi Indo HPL · HP Kasir", extra)
                                tmp.delete()
                                BitmapFactory.decodeFile(target.absolutePath)?.asImageBitmap()
                            }
                            shot = Shot(target, capturedAt, preview, g)
                            step = Step.CONFIRM
                        } catch (e: Exception) {
                            error = "Gagal memproses foto: ${e.message}"
                            step = Step.CAMERA
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    error = "Gagal mengambil foto: ${exception.message}"
                    step = Step.CAMERA
                }
            },
        )
    }

    fun save() {
        val emp = employee ?: return
        val s = shot ?: return
        step = Step.PROCESSING
        scope.launch {
            val rec = repository.recordCheckIn(emp.id, s.capturedAt, s.file.absolutePath, s.geo)
            if (rec == null) {
                s.file.delete()
                onFinished("${emp.name} sudah absen hari ini.")
            } else {
                savedStatus = rec.status to rec.lateMinutes
                step = Step.SAVED
            }
        }
    }

    fun retake() {
        shot?.file?.delete()
        shot = null
        step = Step.CAMERA
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Absen Masuk · ${employee?.name ?: ""}") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (step != Step.SAVED) shot?.file?.delete()
                        onCancel()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (step) {
                Step.LOADING, Step.PROCESSING -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(if (step == Step.LOADING) "Memuat…" else "Memproses foto…")
                }

                Step.BIOMETRIC -> Centered {
                    Icon(Icons.Filled.Fingerprint, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Langkah 1 dari 2: Verifikasi biometrik", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tempelkan sidik jari / pindai wajah / masukkan PIN HP kasir.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { runBiometric() }) { Text("Coba lagi") }
                    }
                }

                Step.CAMERA -> {
                    if (!hasCameraPermission) {
                        Centered {
                            Icon(Icons.Filled.PhotoCamera, null, Modifier.size(72.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("Aplikasi butuh izin kamera untuk selfie.", textAlign = TextAlign.Center)
                            error?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { permissionLauncher.launch(allPermissions) }) { Text("Beri izin kamera") }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Text(
                                "Langkah 2 dari 2: Selfie ${employee?.name ?: ""}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                            CameraPreview(
                                imageCapture = imageCapture,
                                lensFacing = lensFacing,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                onError = { error = it },
                            )
                            error?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            LocationStatus(
                                state = geoState,
                                geo = geo,
                                hasPermission = hasLocationPermission,
                                storeSet = settings.hasStoreLocation,
                                required = locationRequired,
                                onRetry = {
                                    if (hasLocationPermission) geoAttempt++ else permissionLauncher.launch(allPermissions)
                                },
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = {
                                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                                        CameraSelector.LENS_FACING_BACK
                                    } else {
                                        CameraSelector.LENS_FACING_FRONT
                                    }
                                }) { Icon(Icons.Filled.Cameraswitch, contentDescription = "Ganti kamera", Modifier.size(32.dp)) }
                                FilledIconButton(
                                    onClick = { takePhoto() },
                                    enabled = canCapture,
                                    modifier = Modifier.size(80.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(),
                                ) { Icon(Icons.Filled.PhotoCamera, contentDescription = "Jepret", Modifier.size(40.dp)) }
                                Spacer(Modifier.width(48.dp))
                            }
                        }
                    }
                }

                Step.CONFIRM -> {
                    val s = shot
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        s?.preview?.let {
                            Image(bitmap = it, contentDescription = "Selfie", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Waktu foto: ${s?.capturedAt?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")) ?: ""}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        s?.geo?.let { g ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Lokasi: ${GeoRule.formatCoord(g.latitude, g.longitude)} · ${g.note}" +
                                    (g.distanceMeters?.let { " (${GeoRule.formatDistance(it)})" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (g.inLocation) Green else MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { retake() }) { Text("Foto ulang") }
                            Button(onClick = { save() }) { Text("Simpan presensi") }
                        }
                    }
                }

                Step.SAVED -> {
                    val (status, late) = savedStatus ?: (Status.TEPAT to 0)
                    val ok = status == Status.TEPAT
                    Centered {
                        Icon(
                            Icons.Filled.CheckCircle,
                            null,
                            Modifier.size(96.dp),
                            tint = if (ok) Green else Red,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "${employee?.name} tercatat hadir",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        StatusPill(status = status, text = if (ok) "Tepat waktu" else "Telat $late menit")
                        Spacer(Modifier.height(8.dp))
                        shot?.geo?.let { g ->
                            Text(
                                if (g.inLocation) "Di lokasi Indo HPL" else "Di luar lokasi toko (${g.note})",
                                color = if (g.inLocation) Green else MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            shot?.capturedAt?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")) ?: "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = {
                            onFinished("${employee?.name}: ${if (ok) "tepat waktu" else "telat $late menit"}")
                        }) { Text("Selesai") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationStatus(
    state: GeoState,
    geo: GeoCheck?,
    hasPermission: Boolean,
    storeSet: Boolean,
    required: Boolean,
    onRetry: () -> Unit,
) {
    val (text, color) = when {
        !hasPermission -> "Izin lokasi belum diberikan." to MaterialTheme.colorScheme.error
        state == GeoState.IDLE || state == GeoState.SEARCHING -> "Mencari posisi GPS…" to MaterialTheme.colorScheme.onSurfaceVariant
        state == GeoState.FAILED || geo == null -> "GPS tidak didapat. Nyalakan Lokasi HP, coba di dekat jendela." to MaterialTheme.colorScheme.error
        geo.isMock -> "Terdeteksi lokasi palsu (fake GPS)." to MaterialTheme.colorScheme.error
        !storeSet -> "Posisi ${GeoRule.formatCoord(geo.latitude, geo.longitude)}. Titik toko belum diatur di Pengaturan." to Amber
        geo.inLocation -> "Di lokasi Indo HPL · ${GeoRule.formatDistance(geo.distanceMeters ?: 0)} dari titik toko" to Green
        else -> "Di luar lokasi toko · ${GeoRule.formatDistance(geo.distanceMeters ?: 0)} dari titik toko" to MaterialTheme.colorScheme.error
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (state == GeoState.SEARCHING) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else if (state == GeoState.FAILED || geo?.inLocation != true || !hasPermission) {
            TextButton(onClick = onRetry) { Text("Coba lagi") }
        }
    }
    if (required && geo?.inLocation != true) {
        Text(
            "Absen hanya bisa disimpan di lokasi toko.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
