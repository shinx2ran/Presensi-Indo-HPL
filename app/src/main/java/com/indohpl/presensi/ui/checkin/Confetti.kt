package com.indohpl.presensi.ui.checkin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.indohpl.presensi.ui.theme.Blue
import com.indohpl.presensi.ui.theme.Green
import com.indohpl.presensi.ui.theme.GreenLight
import com.indohpl.presensi.ui.theme.Amber
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

private const val BURST_MILLIS = 1400
private const val PARTICLE_COUNT = 40

/** Satu keping confetti. Kecepatan dalam dp per detik, ukuran dalam dp. Hanya hiasan, bukan hadiah. */
private class Particle(
    val vx: Float,
    val vy: Float,
    val size: Float,
    val color: Color,
    val spin: Float,
    val isRect: Boolean,
)

/**
 * Ledakan confetti ringan di atas layar. Diputar SEKALI saat [trigger] menjadi true; bila false tidak menggambar apa pun.
 * Canvas biasa tidak menangkap sentuhan, jadi tombol di bawahnya tetap bisa ditekan.
 */
@Composable
fun ConfettiBurst(trigger: Boolean, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val particles = remember {
        val rnd = Random(42)
        val colors = listOf(Green, Amber, Blue, GreenLight)
        List(PARTICLE_COUNT) { i ->
            // Menyebar ke atas membentuk kipas (sekitar -150 derajat sampai -30 derajat), lalu jatuh karena gravitasi.
            val angle = (-Math.PI * 5.0 / 6.0 + rnd.nextDouble() * (Math.PI * 2.0 / 3.0)).toFloat()
            val speed = 140f + rnd.nextFloat() * 300f
            Particle(
                vx = cos(angle) * speed,
                vy = sin(angle) * speed,
                size = 5f + rnd.nextFloat() * 5f,
                color = colors[i % colors.size],
                spin = (rnd.nextFloat() - 0.5f) * 720f,
                isRect = rnd.nextBoolean(),
            )
        }
    }

    LaunchedEffect(trigger) {
        if (trigger) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(BURST_MILLIS, easing = LinearEasing))
        }
    }

    val p = progress.value
    if (!trigger || p <= 0f || p >= 1f) return

    Canvas(modifier.fillMaxSize()) {
        val dp = 1.dp.toPx()
        val t = p * BURST_MILLIS / 1000f
        val gravity = 700f
        val alpha = if (p < 0.6f) 1f else ((1f - p) / 0.4f).coerceIn(0f, 1f)
        val originX = size.width / 2f
        val originY = size.height * 0.18f
        particles.forEach { q ->
            val x = originX + q.vx * t * dp
            val y = originY + (q.vy * t + 0.5f * gravity * t * t) * dp
            val s = q.size * dp
            val color = q.color.copy(alpha = alpha)
            if (q.isRect) {
                rotate(q.spin * t, pivot = Offset(x, y)) {
                    drawRect(color, topLeft = Offset(x - s / 2f, y - s / 2f), size = Size(s, s * 0.6f))
                }
            } else {
                drawCircle(color, radius = s / 2f, center = Offset(x, y))
            }
        }
    }
}
