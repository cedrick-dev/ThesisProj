package com.kidsafe.secure.nsfw

import android.content.Context
import android.graphics.*
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import android.view.View

class BlurOverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "BlurOverlayView"
    }

    // Paint for the opaque gradient overlay with blur effect
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Add blur effect
        maskFilter = BlurMaskFilter(25f, BlurMaskFilter.Blur.NORMAL)
    }

    // Additional solid overlay paint for more opacity
    private val solidOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 40, 40, 40) // Very opaque dark gray
    }

    // Gradient shader (will be created once view size is known)
    private var gradientShader: LinearGradient? = null

    // Track whether NSFW content is detected
    private var isNsfwDetected = false

    // Optional: Warning text paint
    private val warningTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 56f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        setShadowLayer(12f, 0f, 0f, Color.BLACK)
    }

    private val warningSubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // Required for BlurMaskFilter
        Log.d(TAG, "BlurOverlayView initialized with opaque blurred gradient overlay")
    }

    /**
     * Update the overlay based on whether NSFW content is detected
     * @param hasNsfwContent true if any NSFW predictions exist, false otherwise
     */
    fun updateNsfwStatus(hasNsfwContent: Boolean) {
        if (isNsfwDetected != hasNsfwContent) {
            isNsfwDetected = hasNsfwContent
            Log.d(TAG, "NSFW status changed: $isNsfwDetected")
            invalidate()
        }
    }

    /**
     * Convenience method to work with predictions list
     * @param predictions List of NSFW predictions
     */
    fun updatePredictions(predictions: List<Prediction>) {
        updateNsfwStatus(predictions.isNotEmpty())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (w > 0 && h > 0) {
            // Create a more opaque gradient with blur effect
            gradientShader = LinearGradient(
                0f, 0f,
                0f, h.toFloat(),
                intArrayOf(
                    Color.argb(250, 35, 35, 35),   // Almost opaque very dark gray at top
                    Color.argb(255, 50, 50, 50),   // Fully opaque dark gray in middle
                    Color.argb(250, 35, 35, 35)    // Almost opaque very dark gray at bottom
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )

            overlayPaint.shader = gradientShader

            Log.d(TAG, "Opaque blurred gradient created for view size: ${w}x${h}")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Only draw overlay if NSFW content is detected
        if (!isNsfwDetected) return

        // Draw multiple layers for stronger blur/opacity effect

        // Layer 1: Solid base layer (very opaque)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), solidOverlayPaint)

        // Layer 2: Blurred gradient overlay on top
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)

        // Draw warning message
        val centerX = width / 2f
        val centerY = height / 2f

        // Warning icon (⚠ symbol)
        canvas.drawText("⚠", centerX, centerY - 80f, warningTextPaint)

        // Main warning text
        canvas.drawText("INAPPROPRIATE CONTENT DETECTED", centerX, centerY + 20f, warningTextPaint)

        // Subtitle
        canvas.drawText("Content has been blocked for your safety", centerX, centerY + 70f, warningSubTextPaint)
    }

    /**
     * Clean up resources (minimal cleanup needed now)
     */
    fun cleanup() {
        gradientShader = null
        Log.d(TAG, "Cleanup completed")
    }

    /**
     * Customize the overlay opacity level
     * @param opacity 0-255, where 255 is fully opaque
     */
    fun setOverlayOpacity(opacity: Int) {
        val clampedOpacity = opacity.coerceIn(0, 255)
        solidOverlayPaint.color = Color.argb(clampedOpacity, 40, 40, 40)
        if (isNsfwDetected) {
            invalidate()
        }
    }

    /**
     * Customize the blur radius
     * @param radius blur radius in pixels (1-25 recommended)
     */
    fun setBlurRadius(radius: Float) {
        val clampedRadius = radius.coerceIn(1f, 25f)
        overlayPaint.maskFilter = BlurMaskFilter(clampedRadius, BlurMaskFilter.Blur.NORMAL)
        if (isNsfwDetected) {
            invalidate()
        }
    }

    /**
     * Customize the overlay appearance
     */
    fun setOverlayColors(topColor: Int, midColor: Int, bottomColor: Int) {
        if (width > 0 && height > 0) {
            gradientShader = LinearGradient(
                0f, 0f,
                0f, height.toFloat(),
                intArrayOf(topColor, midColor, bottomColor),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            overlayPaint.shader = gradientShader
            if (isNsfwDetected) {
                invalidate()
            }
        }
    }

    /**
     * Set custom warning message
     */
    fun setWarningText(mainText: String, subText: String = "") {
        // Store these if you want to customize the text
        // For now, they're hardcoded in onDraw
        // You could add member variables to store these
    }
}