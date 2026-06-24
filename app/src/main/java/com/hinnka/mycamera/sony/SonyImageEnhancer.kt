package com.hinnka.mycamera.sony

import android.graphics.Bitmap
import android.graphics.Color
import com.hinnka.mycamera.utils.PLog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Sony-specific image enhancement algorithms
 * 
 * Optimized for Sony Xperia devices with Exmor RS sensors
 * Addresses common issues with Sony's default processing:
 * - Over-aggressive noise reduction
 * - Oversaturated colors
 * - Poor detail in low light
 * - Unnatural bokeh
 */
class SonyImageEnhancer {
    
    companion object {
        private const val TAG = "SonyImageEnhancer"
        
        // Sony sensor profiles for color calibration
        private val SENSOR_PROFILES = mapOf(
            "IMX557" to SensorProfile(  // Main 24mm (Xperia 1 Mark 3)
                saturationMultiplier = 0.85f,
                contrastBoost = 1.05f,
                shadowDetail = 1.15f,
                highlightRecovery = 1.1f,
                colorTemperatureAdjust = 0.0f
            ),
            "IMX663" to SensorProfile(  // Telephoto 70/105mm (Xperia 1 Mark 3)
                saturationMultiplier = 0.90f,
                contrastBoost = 1.08f,
                shadowDetail = 1.10f,
                highlightRecovery = 1.05f,
                colorTemperatureAdjust = -0.02f
            ),
            "IMX363" to SensorProfile(  // Ultra-wide 16mm (Xperia 1 Mark 3)
                saturationMultiplier = 0.88f,
                contrastBoost = 1.03f,
                shadowDetail = 1.12f,
                highlightRecovery = 1.08f,
                colorTemperatureAdjust = 0.01f
            )
        )
        
        data class SensorProfile(
            val saturationMultiplier: Float,
            val contrastBoost: Float,
            val shadowDetail: Float,
            val highlightRecovery: Float,
            val colorTemperatureAdjust: Float
        )
    }
    
    /**
     * Apply adaptive noise reduction optimized for Sony sensors
     * Sony sensors have good low-light performance but Sony's NR is too aggressive
     */
    fun applyAdaptiveNoiseReduction(bitmap: Bitmap, isoLevel: Int = 100): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Adaptive NR strength based on ISO
        val nrStrength = when {
            isoLevel < 200 -> 0.0f
            isoLevel < 400 -> 0.15f
            isoLevel < 800 -> 0.25f
            isoLevel < 1600 -> 0.35f
            else -> 0.45f
        }
        
        if (nrStrength <= 0.0f) {
            return bitmap
        }
        
        val processed = IntArray(width * height)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val pixel = pixels[idx]
                
                // Apply bilateral filter-like smoothing
                val smoothed = applyBilateralFilter(pixels, x, y, width, height, nrStrength)
                processed[idx] = smoothed
            }
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(processed, 0, width, 0, 0, width, height)
        return result
    }
    
    /**
     * Apply natural color profile for Sony sensors
     * Reduces oversaturation while maintaining natural colors
     */
    fun applyNaturalColorProfile(bitmap: Bitmap, sensorModel: String = "IMX557"): Bitmap {
        val profile = SENSOR_PROFILES[sensorModel] ?: SENSOR_PROFILES["IMX557"]!!
        
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            
            // Convert to HSL for better color control
            val (h, s, l) = rgbToHsl(r, g, b)
            
            // Apply Sony-specific adjustments
            val adjustedSaturation = s * profile.saturationMultiplier
            val adjustedLightness = adjustLightnessForContrast(l, profile.contrastBoost)
            
            // Convert back to RGB
            val (newR, newG, newB) = hslToRgb(h, adjustedSaturation, adjustedLightness)
            
            pixels[i] = Color.rgb(newR, newG, newB)
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
    
    /**
     * Apply detail enhancement without artifacts
     * Multi-scale detail enhancement optimized for Sony sensors
     */
    fun applyDetailEnhancement(bitmap: Bitmap, strength: Float = 0.3f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Unsharp mask for detail enhancement
        val processed = IntArray(width * height)
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val center = pixels[idx]
                
                // Calculate local average
                val sum = pixels[idx - 1] + pixels[idx + 1] + 
                          pixels[idx - width] + pixels[idx + width]
                val average = sum / 4
                
                // Calculate difference (detail)
                val diff = calculatePixelDifference(center, average)
                
                // Apply selective sharpening
                val enhanced = if (diff > 10) {
                    // Enhance edges
                    applySelectiveSharpen(center, average, strength)
                } else {
                    // Preserve smooth areas
                    center
                }
                
                processed[idx] = enhanced
            }
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(processed, 0, width, 0, 0, width, height)
        return result
    }
    
    /**
     * Apply natural bokeh with realistic aperture simulation
     * Optimized for Sony Zeiss lenses
     */
    fun applyNaturalBokeh(bitmap: Bitmap, depthMap: FloatArray?, aperture: Float = 2.0f): Bitmap {
        if (depthMap == null) {
            return bitmap // Fallback if no depth map
        }
        
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val processed = pixels.copyOf()
        
        // Calculate blur radius based on depth and aperture
        val maxBlurRadius = (aperture * 5).toInt()
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val depth = depthMap.getOrElse(idx) { 0.5f }
                
                // Depth 0 = near, 1 = far
                // Blur increases with distance from focus point
                val blurRadius = if (depth > 0.3f) {
                    ((depth - 0.3f) * maxBlurRadius).toInt().coerceIn(0, maxBlurRadius)
                } else {
                    0
                }
                
                if (blurRadius > 0) {
                    processed[idx] = applyGaussianBlur(pixels, x, y, width, height, blurRadius)
                }
            }
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(processed, 0, width, 0, 0, width, height)
        return result
    }
    
    /**
     * Apply Sony-specific shadow and highlight recovery
     * Sony sensors have good dynamic range but Sony's processing doesn't utilize it well
     */
    fun applyDynamicRangeEnhancement(bitmap: Bitmap, shadowBoost: Float = 1.2f, highlightProtection: Float = 0.9f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            
            // Convert to HSL
            val (h, s, l) = rgbToHsl(r, g, b)
            
            // Enhance shadows (dark areas)
            val enhancedLightness = when {
                l < 0.3f -> l * shadowBoost
                l > 0.7f -> l * highlightProtection // Protect highlights
                else -> l
            }.coerceIn(0f, 1f)
            
            // Convert back to RGB
            val (newR, newG, newB) = hslToRgb(h, s, enhancedLightness)
            pixels[i] = Color.rgb(newR, newG, newB)
        }
        
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
    
    // Helper functions
    
    private fun applyBilateralFilter(pixels: IntArray, x: Int, y: Int, width: Int, height: Int, strength: Float): Int {
        val idx = y * width + x
        val center = pixels[idx]
        
        var sumR = 0
        var sumG = 0
        var sumB = 0
        var weight = 0f
        
        val radius = 2
        
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val nx = x + dx
                val ny = y + dy
                
                if (nx in 0 until width && ny in 0 until height) {
                    val nidx = ny * width + nx
                    val neighbor = pixels[nidx]
                    
                    val spatialWeight = 1f / (1f + (dx * dx + dy * dy).toFloat() / (radius * radius))
                    val colorWeight = 1f / (1f + calculatePixelDifference(center, neighbor) / 255f * strength)
                    
                    val totalWeight = spatialWeight * colorWeight
                    
                    sumR += Color.red(neighbor) * totalWeight
                    sumG += Color.green(neighbor) * totalWeight
                    sumB += Color.blue(neighbor) * totalWeight
                    weight += totalWeight
                }
            }
        }
        
        return Color.rgb(
            (sumR / weight).toInt(),
            (sumG / weight).toInt(),
            (sumB / weight).toInt()
        )
    }
    
    private fun calculatePixelDifference(p1: Int, p2: Int): Float {
        val r1 = Color.red(p1)
        val g1 = Color.green(p1)
        val b1 = Color.blue(p1)
        
        val r2 = Color.red(p2)
        val g2 = Color.green(p2)
        val b2 = Color.blue(p2)
        
        return sqrt(
            (r1 - r2).toFloat().pow(2) +
            (g1 - g2).toFloat().pow(2) +
            (b1 - b2).toFloat().pow(2)
        )
    }
    
    private fun applySelectiveSharpen(center: Int, average: Int, strength: Float): Int {
        val r = Color.red(center)
        val g = Color.green(center)
        val b = Color.blue(center)
        
        val avgR = Color.red(average)
        val avgG = Color.green(average)
        val avgB = Color.blue(average)
        
        val diffR = r - avgR
        val diffG = g - avgG
        val diffB = b - avgB
        
        return Color.rgb(
            (r + diffR * strength).toInt().coerceIn(0, 255),
            (g + diffG * strength).toInt().coerceIn(0, 255),
            (b + diffB * strength).toInt().coerceIn(0, 255)
        )
    }
    
    private fun applyGaussianBlur(pixels: IntArray, x: Int, y: Int, width: Int, height: Int, radius: Int): Int {
        val idx = y * width + x
        
        var sumR = 0
        var sumG = 0
        var sumB = 0
        var count = 0
        
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val nx = x + dx
                val ny = y + dy
                
                if (nx in 0 until width && ny in 0 until height) {
                    val nidx = ny * width + nx
                    val pixel = pixels[nidx]
                    
                    sumR += Color.red(pixel)
                    sumG += Color.green(pixel)
                    sumB += Color.blue(pixel)
                    count++
                }
            }
        }
        
        return Color.rgb(sumR / count, sumG / count, sumB / count)
    }
    
    private fun adjustLightnessForContrast(lightness: Float, contrastBoost: Float): Float {
        // S-curve for contrast enhancement
        val centered = lightness - 0.5f
        val adjusted = centered * contrastBoost + 0.5f
        return adjusted.coerceIn(0f, 1f)
    }
    
    private fun rgbToHsl(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        
        val max = max(rf, max(gf, bf))
        val min = min(rf, min(gf, bf))
        val delta = max - min
        
        val h = when {
            delta == 0f -> 0f
            max == rf -> ((gf - bf) / delta) % 6f
            max == gf -> ((bf - rf) / delta) + 2f
            else -> ((rf - gf) / delta) + 4f
        } * 60f
        
        val s = if (max == 0f) 0f else delta / max
        
        val l = (max + min) / 2f
        
        return Triple(h.coerceIn(0f, 360f), s, l)
    }
    
    private fun hslToRgb(h: Float, s: Float, l: Float): Triple<Int, Int, Int> {
        val c = (1f - abs(2 * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        
        val r = ((r1 + m) * 255).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).toInt().coerceIn(0, 255)
        
        return Triple(r, g, b)
    }
}
