package com.alijafari.red.astronomy.ui.screens.ar

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.alijafari.red.astronomy.ar.core.ArCameraModel
import com.alijafari.red.astronomy.ar.core.ArSkyLighting
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Canvas rendition of the AR sky for devices without OpenGL ES 3 (and for the instant before the GL
 * sky shows its first frame): the same physically based sky colours as the GL shader
 * ([ArSkyLighting.displayColorAt]), evaluated on a coarse grid through the same virtual camera and
 * drawn with bilinear filtering — the sky is smooth at that scale. The Milky Way is GL-only.
 */
@Composable
fun SimulatedSkyFallback(lighting: ArSkyLighting, cameraModel: ArCameraModel?) {
    val holder = remember { FallbackBuffers() }
    DisposableEffect(holder) {
        onDispose { holder.release() }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cam = cameraModel
        val w = size.width
        val h = size.height
        if (cam == null || w <= 0f || h <= 0f) {
            drawRect(Color.Black)
            return@Canvas
        }
        val gw = GRID_WIDTH
        val gh = (gw * h / w).roundToInt().coerceIn(8, 160)
        val bmp = holder.bitmap(gw, gh)
        val pixels = holder.pixels(gw * gh)
        val c2e = cam.cameraToEnuMatrix(holder.matrix)
        val f = cam.focalLengthViewPx()
        val out = holder.out
        val tmp = holder.tmp
        for (j in 0 until gh) {
            val y = ((j + 0.5) / gh * h - h / 2.0) / f
            for (i in 0 until gw) {
                val x = ((i + 0.5) / gw * w - w / 2.0) / f
                // Camera frame: x right, y down, z along the boresight.
                var e = c2e[0] * x + c2e[1] * y + c2e[2]
                var n = c2e[3] * x + c2e[4] * y + c2e[5]
                var u = c2e[6] * x + c2e[7] * y + c2e[8]
                val norm = sqrt(e * e + n * n + u * u)
                e /= norm; n /= norm; u /= norm
                lighting.displayColorAt(e, n, u, out, tmp)
                val r = (out[0] * 255.0).roundToInt().coerceIn(0, 255)
                val g = (out[1] * 255.0).roundToInt().coerceIn(0, 255)
                val b = (out[2] * 255.0).roundToInt().coerceIn(0, 255)
                pixels[j * gw + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, gw, 0, 0, gw, gh)
        holder.dst.set(0f, 0f, w, h)
        drawIntoCanvas { it.nativeCanvas.drawBitmap(bmp, null, holder.dst, holder.paint) }
    }
}

private const val GRID_WIDTH = 48

private class FallbackBuffers {
    private var bmp: Bitmap? = null
    private var px: IntArray = IntArray(0)
    val matrix = DoubleArray(9)
    val out = DoubleArray(3)
    val tmp = DoubleArray(3)
    val dst = RectF()
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    fun bitmap(w: Int, h: Int): Bitmap {
        val b = bmp
        if (b != null && !b.isRecycled && b.width == w && b.height == h) return b
        b?.recycle()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp = it }
    }

    fun pixels(n: Int): IntArray {
        if (px.size != n) px = IntArray(n)
        return px
    }

    fun release() {
        bmp?.recycle()
        bmp = null
    }
}
