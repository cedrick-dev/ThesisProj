package com.kidsafe.secure.nsfw

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.View

class BlurOverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "BlurOverlayView"
    }

    // Strong Gaussian blur effect paint
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(240, 30, 30, 30) // Very dark, almost opaque
        // Strong Gaussian blur - radius of 50 for heavy blur
        maskFilter = BlurMaskFilter(50f, BlurMaskFilter.Blur.NORMAL)
    }

    // Additional layered blur for extra strength
    private val secondaryBlurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(200, 40, 40, 40)
        maskFilter = BlurMaskFilter(35f, BlurMaskFilter.Blur.NORMAL)
    }

    // Base solid layer
    private val solidOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 35, 35, 35)
    }

    // Track whether NSFW content is detected
    private var isNsfwDetected = false

    // Warning text paint
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
        // Required for BlurMaskFilter to work
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        Log.d(TAG, "BlurOverlayView initialized with strong Gaussian blur overlay")
    }

    fun updateNsfwStatus(hasNsfwContent: Boolean) {
        if (isNsfwDetected != hasNsfwContent) {
            isNsfwDetected = hasNsfwContent
            Log.d(TAG, "NSFW status changed: $isNsfwDetected")
            invalidate()
        }
    }

    fun updatePredictions(predictions: List<Prediction>) {
        updateNsfwStatus(predictions.isNotEmpty())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Only draw overlay if NSFW content is detected
        if (!isNsfwDetected) return

        // Multi-layer blur for maximum effect

        // Layer 1: Solid base (prevents any content from showing through)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), solidOverlayPaint)

        // Layer 2: First blur layer
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blurPaint)

        // Layer 3: Second blur layer for extra strength
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), secondaryBlurPaint)

        // Layer 4: Another solid layer on top
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), solidOverlayPaint)

        // Draw warning message on top
        val centerX = width / 2f
        val centerY = height / 2f

        // Warning icon
        canvas.drawText("⚠", centerX, centerY - 80f, warningTextPaint)

        // Main warning text
        canvas.drawText("INAPPROPRIATE CONTENT DETECTED", centerX, centerY + 20f, warningTextPaint)

        // Subtitle
        canvas.drawText("Content has been blocked for your safety", centerX, centerY + 70f, warningSubTextPaint)
    }

    fun cleanup() {
        Log.d(TAG, "Cleanup completed")
    }

    /**
     * Customize blur intensity
     * @param radius blur radius (10-100 recommended, default 50)
     */
    fun setBlurIntensity(radius: Float) {
        val clampedRadius = radius.coerceIn(10f, 100f)
        blurPaint.maskFilter = BlurMaskFilter(clampedRadius, BlurMaskFilter.Blur.NORMAL)
        secondaryBlurPaint.maskFilter = BlurMaskFilter(clampedRadius * 0.7f, BlurMaskFilter.Blur.NORMAL)
        if (isNsfwDetected) {
            invalidate()
        }
    }

    /**
     * Customize overlay opacity
     * @param opacity 0-255, where 255 is fully opaque
     */
    fun setOverlayOpacity(opacity: Int) {
        val clampedOpacity = opacity.coerceIn(150, 255)
        solidOverlayPaint.color = Color.argb(clampedOpacity, 35, 35, 35)
        blurPaint.color = Color.argb((clampedOpacity * 0.95f).toInt(), 30, 30, 30)
        secondaryBlurPaint.color = Color.argb((clampedOpacity * 0.8f).toInt(), 40, 40, 40)
        if (isNsfwDetected) {
            invalidate()
        }
    }
}