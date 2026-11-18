package com.proxdr.sdrconverter

import android.graphics.Bitmap
import android.graphics.Gainmap
import kotlin.math.max
import kotlin.math.pow

object ProXdrProcessor {

    data class Params(
        val highlightBoost: Float, // 0.0 to 1.5
        val shadowCrush: Float,    // 0.0 to 1.0
        val sCurveContrast: Float  // 0.0 to 1.0
    )

    fun processToSdr(source: Bitmap, params: Params): Bitmap {
        val width = source.width
        val height = source.height
        val baseBitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        baseBitmap.gainmap = null

        val gainmap: Gainmap? = source.gainmap
        val gainmapBitmap: Bitmap? = gainmap?.gainmapContents?.let { gm ->
            if (gm.width != width || gm.height != height) {
                Bitmap.createScaledBitmap(gm, width, height, true)
            } else {
                gm
            }
        }

        val pixels = IntArray(width * height)
        baseBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gmPixels: IntArray? = gainmapBitmap?.let { gm ->
            val arr = IntArray(width * height)
            val compatibleGm = if (gm.config == Bitmap.Config.ALPHA_8) {
                gm.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                gm
            }
            compatibleGm.getPixels(arr, 0, width, 0, 0, width, height)
            arr
        }

        val ratioMax = gainmap?.ratioMax ?: floatArrayOf(2.5f, 2.5f, 2.5f)
        val maxBoostR = max(1.0f, ratioMax[0])
        val maxBoostG = max(1.0f, ratioMax[1])
        val maxBoostB = max(1.0f, ratioMax[2])

        val hlStrength = params.highlightBoost
        val shStrength = params.shadowCrush
        val contrastStrength = params.sCurveContrast

        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24
            var r = ((c shr 16) and 0xFF) / 255.0f
            var g = ((c shr 8) and 0xFF) / 255.0f
            var b = (c and 0xFF) / 255.0f

            // 1. Convert sRGB to approximate linear light
            var rLin = r.pow(2.2f)
            var gLin = g.pow(2.2f)
            var bLin = b.pow(2.2f)

            val luma = 0.2126f * rLin + 0.7152f * gLin + 0.0722f * bLin

            // 2. Apply Gainmap / ProXDR Highlight Boost
            if (gmPixels != null) {
                val gmColor = gmPixels[i]
                val gmR = ((gmColor shr 16) and 0xFF) / 255.0f
                val gmG = ((gmColor shr 8) and 0xFF) / 255.0f
                val gmB = (gmColor and 0xFF) / 255.0f

                val gainR = 1.0f + gmR * (maxBoostR - 1.0f) * hlStrength
                val gainG = 1.0f + gmG * (maxBoostG - 1.0f) * hlStrength
                val gainB = 1.0f + gmB * (maxBoostB - 1.0f) * hlStrength

                rLin *= gainR
                gLin *= gainG
                bLin *= gainB
            } else {
                val highlightMask = luma.pow(1.6f)
                val boost = 1.0f + highlightMask * hlStrength * 0.85f
                rLin *= boost
                gLin *= boost
                bLin *= boost
            }

            // 3. Darken Dark Spots (Shadow Toe Crush)
            if (shStrength > 0f) {
                val shadowGamma = 1.0f + shStrength * 0.65f
                val shadowFactor = (1.0f - luma).coerceIn(0f, 1f).pow(1.5f)
                val effectiveGamma = 1.0f + (shadowGamma - 1.0f) * shadowFactor
                rLin = rLin.coerceAtLeast(0f).pow(effectiveGamma)
                gLin = gLin.coerceAtLeast(0f).pow(effectiveGamma)
                bLin = bLin.coerceAtLeast(0f).pow(effectiveGamma)
            }

            // 4. Soft-Knee Filmic Highlight Roll-Off
            rLin = filmicToneMap(rLin)
            gLin = filmicToneMap(gLin)
            bLin = filmicToneMap(bLin)

            // Convert back to perceptual sRGB [0..1]
            r = rLin.coerceIn(0f, 1f).pow(1.0f / 2.2f)
            g = gLin.coerceIn(0f, 1f).pow(1.0f / 2.2f)
            b = bLin.coerceIn(0f, 1f).pow(1.0f / 2.2f)

            // 5. Global S-Curve Contrast (Brightens brights, darkens darks)
            if (contrastStrength > 0f) {
                r = applySCurve(r, contrastStrength)
                g = applySCurve(g, contrastStrength)
                b = applySCurve(b, contrastStrength)
            }

            // 6. Subtle saturation compensation
            val finalLuma = 0.299f * r + 0.587f * g + 0.114f * b
            val satBoost = 1.0f + (contrastStrength * 0.15f)
            r = (finalLuma + (r - finalLuma) * satBoost).coerceIn(0f, 1f)
            g = (finalLuma + (g - finalLuma) * satBoost).coerceIn(0f, 1f)
            b = (finalLuma + (b - finalLuma) * satBoost).coerceIn(0f, 1f)

            val outR = (r * 255.0f + 0.5f).toInt().coerceIn(0, 255)
            val outG = (g * 255.0f + 0.5f).toInt().coerceIn(0, 255)
            val outB = (b * 255.0f + 0.5f).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
        }

        baseBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return baseBitmap
    }

    private fun filmicToneMap(x: Float): Float {
        if (x <= 0.75f) return x
        val excess = x - 0.75f
        return 0.75f + 0.25f * (excess / (excess + 0.35f))
    }

    private fun applySCurve(v: Float, amount: Float): Float {
        val s = v * v * (3.0f - 2.0f * v)
        return v + (s - v) * amount
    }
}
