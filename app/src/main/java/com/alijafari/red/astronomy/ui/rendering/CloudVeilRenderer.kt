package com.alijafari.red.astronomy.ui.rendering

import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import com.alijafari.red.astronomy.domain.SkyCanvasTheme

/**
 * Slow-drifting cloud veils for the full-bleed Home scene.
 *
 * These are atmosphere, not a forecast: ZIG has no weather feed, so this layer never claims a cloud
 * cover. It draws two or three very soft, very translucent veils that drift at different speeds to give
 * the still sky a sense of depth and motion — the same job the drifting clouds do in Samsung's scene,
 * minus the weather meaning. Themes that already supply their own cloud art (Papercraft's cut-outs)
 * opt out through [ScenePalette.cloudStrength]; Real Sky's photograph keeps only a faint veil, for motion.
 *
 * Cost: the veils are pre-rendered once into small bitmaps and drawn as a handful of tinted images, so a
 * frame costs a few textured quads instead of dozens of radial gradients.
 */
object CloudVeilRenderer {

    /** Sprite height / width, kept in one place so the drawn shapes do not stretch. */
    private const val SPRITE_ASPECT = 0.62f

    private class Layer(
        val count: Int,
        val yFraction: Float,
        val widthFraction: Float,
        val alpha: Float,
        val speedPxPerSecond: Float,
        val streak: Boolean,
        val bobFraction: Float
    )

    private val LAYERS = listOf(
        // High, thin cirrus: wide, faint and slow.
        Layer(count = 2, yFraction = 0.20f, widthFraction = 0.62f, alpha = 0.16f, speedPxPerSecond = 2.4f, streak = true, bobFraction = 0.004f),
        // Mid veil: the layer that actually reads as cloud.
        Layer(count = 3, yFraction = 0.44f, widthFraction = 0.40f, alpha = 0.30f, speedPxPerSecond = 6.5f, streak = false, bobFraction = 0.006f),
        // Low veil, just above the ridge: fastest, most parallax.
        Layer(count = 2, yFraction = 0.74f, widthFraction = 0.52f, alpha = 0.20f, speedPxPerSecond = 11.0f, streak = false, bobFraction = 0.008f)
    )

    /**
     * Draws the veils for [theme] at [frameTimeMs]. The caller is responsible for having drawn the sky
     * behind them; the veils are meant to sit above stars and below nothing but the readout.
     */
    fun draw(
        drawScope: DrawScope,
        theme: SkyCanvasTheme,
        lighting: LightingState,
        frameTimeMs: Long,
        composition: SceneComposition
    ) {
        val palette = scenePalette(theme, lighting)
        if (palette.cloudStrength <= 0.01f) return

        val day = lighting.ambientBrightness.coerceIn(0f, 1f)
        val presence = palette.cloudStrength * (0.38f + 0.62f * day)
        if (presence <= 0.01f) return

        val width = composition.widthPx
        val height = composition.heightPx
        val horizonY = composition.horizonY
        val t = ((frameTimeMs % 86_400_000L) / 1000f)
        val tint = ColorFilter.tint(palette.cloud, BlendMode.SrcIn)
        val puff = CloudSprites.puff
        val streak = CloudSprites.streak

        LAYERS.forEachIndexed { layerIndex, layer ->
            val sprite = if (layer.streak) streak else puff
            val cloudW = width * layer.widthFraction
            val cloudH = cloudW * SPRITE_ASPECT * if (layer.streak) 0.55f else 1f
            val span = cloudW + width
            val baseY = horizonY * layer.yFraction
            val bob = height * layer.bobFraction

            for (i in 0 until layer.count) {
                val seed = pseudoRandom(layerIndex * 131 + i * 37)
                val x = ((seed * span + t * layer.speedPxPerSecond) % span) - cloudW
                val y = baseY + bob * kotlin.math.sin(t * 0.11f + i * 1.7f) -
                    cloudH * 0.5f + (pseudoRandom(layerIndex * 71 + i * 17) - 0.5f) * height * 0.02f
                val alpha = layer.alpha * presence * (0.75f + 0.25f * pseudoRandom(layerIndex * 91 + i * 53))
                if (alpha <= 0.004f) continue
                val scaleX = cloudW / sprite.width
                val scaleY = cloudH / sprite.height

                drawScope.withTransform({
                    translate(left = x, top = y)
                    scale(scaleX = scaleX, scaleY = scaleY, pivot = Offset.Zero)
                }) {
                    drawImage(
                        image = sprite,
                        topLeft = Offset.Zero,
                        alpha = alpha.coerceIn(0f, 1f),
                        colorFilter = tint
                    )
                }
            }
        }
    }

    /** Deterministic 0..1 value for a seed. No allocation, no shared mutable state. */
    private fun pseudoRandom(seed: Int): Float {
        var x = seed * 1_103_515_245 + 12_345
        x = x xor (x ushr 13)
        x *= 0x5bd1e995
        x = x xor (x ushr 15)
        return (x and 0x7FFFFFFF) / 16_777_215f
    }
}

/**
 * The two cloud sprites, rendered with `android.graphics` the first time they are needed.
 *
 * They are built once per process and never mutated: an 8-bit alpha ramp of overlapping soft blobs for the
 * cumulus veil, and a stretched version of the same idea for cirrus.
 */
private object CloudSprites {

    val puff: ImageBitmap by lazy { build(w = 256, h = 160, blobs = listOf(Blob(128f, 112f, 74f, 210), Blob(76f, 118f, 54f, 180), Blob(182f, 118f, 54f, 180), Blob(104f, 86f, 50f, 150), Blob(158f, 90f, 46f, 150), Blob(128f, 74f, 40f, 120)), flattenFrom = 112f) }

    val streak: ImageBitmap by lazy { build(w = 512, h = 128, blobs = listOf(Blob(256f, 74f, 190f, 150), Blob(150f, 82f, 120f, 120), Blob(370f, 80f, 130f, 120)), flattenFrom = 86f) }

    private class Blob(val cx: Float, val cy: Float, val radius: Float, val alpha: Int)

    private fun build(w: Int, h: Int, blobs: List<Blob>, flattenFrom: Float): ImageBitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG)

        for (blob in blobs) {
            paint.shader = RadialGradient(
                blob.cx,
                blob.cy,
                blob.radius,
                intArrayOf(argb(blob.alpha), argb(blob.alpha * 2 / 3), argb(0)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(blob.cx, blob.cy, blob.radius, paint)
        }

        // Flatten the underside: clouds in the scene are seen from below, so their base is a soft shelf
        // rather than a ring of blobs.
        val erase = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        erase.shader = android.graphics.LinearGradient(
            0f,
            flattenFrom,
            0f,
            h.toFloat(),
            intArrayOf(0x00000000, 0xFF000000.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        erase.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        canvas.drawRect(0f, flattenFrom, w.toFloat(), h.toFloat(), erase)

        return bitmap.asImageBitmap()
    }

    private fun argb(alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or 0x00FFFFFF
}
