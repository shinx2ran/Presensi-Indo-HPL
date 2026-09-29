package com.indohpl.presensi.ui.checkin

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Ambil ProcessCameraProvider dengan coroutine (bungkus ListenableFuture). */
suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

/**
 * Pratinjau kamera CameraX. [imageCapture] dibuat oleh pemanggil supaya tombol jepret
 * bisa memakainya. Mengganti [lensFacing] otomatis mengikat ulang kamera.
 */
@Composable
fun CameraPreview(
    imageCapture: ImageCapture,
    lensFacing: Int,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    LaunchedEffect(lensFacing) {
        try {
            val provider = context.awaitCameraProvider()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            if (!provider.hasCamera(selector)) {
                // HP tanpa kamera depan: pakai kamera apa pun yang ada.
                selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        } catch (e: Exception) {
            onError("Kamera gagal dibuka: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
