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
        private const val BLUR_RADIUS = 25f
        // Increased sample factor for better quality and less pixelation
        private const val BLUR_SAMPLE_FACTOR = 0.25f
    }

    // Enable anti-aliasing for smooth Gaussian blur effect
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    // Opaque solid fill paint for full-screen blocking
    private val fullScreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(245, 0, 0, 0) // Almost opaque black for full-screen coverage
    }

    // Scrim paint to darken the blurred overlay and prevent false positives
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(150, 0, 0, 0) // ~60% opacity black
    }

    // Warning text paints
    private val warningTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 52f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val warningSubtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        alpha = 255
    }

    private var predictions: List<Prediction> = emptyList()
    private var blurredRegions = mutableMapOf<String, BlurredRegion>()
    private var fullScreenBlurBitmap: Bitmap? = null
    private var renderScript: RenderScript? = null
    private var previousBitmap: Bitmap? = null

    // View dimensions for proper scaling
    private var viewWidth = 0
    private var viewHeight = 0
    private var imageWidth = 0
    private var imageHeight = 0

    // Blur mode by default (changed from red overlay)
    private var showBoundingBoxes = false

    data class BlurredRegion(
        val bitmap: Bitmap,
        val rect: RectF,
        val confidence: Float
    )

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        try {
            renderScript = RenderScript.create(context)
            Log.d(TAG, "RenderScript initialized")
        } catch (e: Exception) {
            Log.e(TAG, "RenderScript unavailable. Using solid overlay only.", e)
            showBoundingBoxes = true
        }
    }

    fun updatePredictions(newPredictions: List<Prediction>, sourceBitmap: Bitmap?) {
        predictions = newPredictions

        Log.d(TAG, "updatePredictions - predictions: ${predictions.size}, bitmap: ${sourceBitmap != null}")

        // Check if we have a full-screen prediction
        val hasFullScreenPrediction = predictions.any { it.isFullScreen }
        Log.d(TAG, "Full-screen prediction detected: $hasFullScreenPrediction")

        if (sourceBitmap != null) {
            imageWidth = sourceBitmap.width
            imageHeight = sourceBitmap.height
            Log.d(TAG, "Source bitmap size: ${imageWidth}x${imageHeight}")
        }

        previousBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }

        if (sourceBitmap == null || predictions.isEmpty()) {
            clearBlurRegions()
            clearFullScreenBlur()
            previousBitmap = null
            invalidate()
            return
        }

        previousBitmap = sourceBitmap.copy(sourceBitmap.config ?: Bitmap.Config.ARGB_8888, false)

        // If full-screen prediction exists, generate full-screen blur
        if (hasFullScreenPrediction && !showBoundingBoxes) {
            if (fullScreenBlurBitmap == null) {
                generateFullScreenBlur(sourceBitmap)
            }
            clearBlurRegions() // Clear individual regions since we're using full-screen
        } else if (!showBoundingBoxes) {
            generateBlurRegions(sourceBitmap)
            clearFullScreenBlur()
        }

        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        Log.d(TAG, "View size changed: ${viewWidth}x${viewHeight}")
    }

    private fun clearBlurRegions() {
        blurredRegions.values.forEach {
            try {
                if (!it.bitmap.isRecycled) {
                    it.bitmap.recycle()
                }
            } catch (_: Exception) {}
        }
        blurredRegions.clear()
    }

    private fun clearFullScreenBlur() {
        fullScreenBlurBitmap?.let {
            try {
                if (!it.isRecycled) {
                    it.recycle()
                }
            } catch (_: Exception) {}
        }
        fullScreenBlurBitmap = null
    }

    /**
     * Generates a heavily blurred full-screen bitmap
     */
    private fun generateFullScreenBlur(sourceBitmap: Bitmap) {
        clearFullScreenBlur()

        try {
            Log.d(TAG, "Generating full-screen blur for bitmap: ${sourceBitmap.width}x${sourceBitmap.height}")

            // Create heavily downsampled version for intense blur
            val smallWidth = (sourceBitmap.width * BLUR_SAMPLE_FACTOR).toInt().coerceAtLeast(1)
            val smallHeight = (sourceBitmap.height * BLUR_SAMPLE_FACTOR).toInt().coerceAtLeast(1)

            Log.d(TAG, "Downsampling to: ${smallWidth}x${smallHeight}")

            val small = Bitmap.createScaledBitmap(sourceBitmap, smallWidth, smallHeight, true)
            val blurred = blurBitmap(small)

            small.recycle()

            if (blurred != null) {
                fullScreenBlurBitmap = blurred
                Log.d(TAG, "Full-screen blur generated successfully")
            } else {
                Log.w(TAG, "Full-screen blur generation failed, will use solid overlay")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating full-screen blur", e)
        }
    }

    private fun generateBlurRegions(sourceBitmap: Bitmap) {
        clearBlurRegions()
        val newMap = mutableMapOf<String, BlurredRegion>()

        for (prediction in predictions) {
            try {
                val rect = prediction.boundingBox
                val padding = 20f

                val padded = RectF(
                    (rect.left - padding).coerceAtLeast(0f),
                    (rect.top - padding).coerceAtLeast(0f),
                    (rect.right + padding).coerceAtMost(sourceBitmap.width.toFloat()),
                    (rect.bottom + padding).coerceAtMost(sourceBitmap.height.toFloat())
                )

                if (padded.width() > 0 && padded.height() > 0) {
                    val regionBitmap = Bitmap.createBitmap(
                        sourceBitmap,
                        padded.left.toInt(),
                        padded.top.toInt(),
                        padded.width().toInt(),
                        padded.height().toInt()
                    )

                    val smallWidth = (regionBitmap.width * BLUR_SAMPLE_FACTOR).toInt().coerceAtLeast(1)
                    val smallHeight = (regionBitmap.height * BLUR_SAMPLE_FACTOR).toInt().coerceAtLeast(1)
                    val small = Bitmap.createScaledBitmap(regionBitmap, smallWidth, smallHeight, true)

                    val blurred = blurBitmap(small)

                    small.recycle()
                    regionBitmap.recycle()

                    if (blurred != null) {
                        val key = "${padded.left},${padded.top}"
                        newMap[key] = BlurredRegion(blurred, padded, prediction.confidence)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Blur generation failed for prediction", e)
            }
        }

        blurredRegions = newMap
    }

    private fun blurBitmap(bitmap: Bitmap): Bitmap? {
        return try {
            val rs = renderScript ?: return null
            val input = Allocation.createFromBitmap(rs, bitmap)
            val output = Allocation.createTyped(rs, input.type)
            val blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

            blur.setRadius(BLUR_RADIUS)
            blur.setInput(input)
            blur.forEach(output)

            val blurred = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
            output.copyTo(blurred)

            input.destroy()
            output.destroy()
            blur.destroy()
            blurred
        } catch (e: Exception) {
            Log.e(TAG, "Blur failed", e)
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (predictions.isEmpty()) return

        // Check if we have a full-screen prediction
        val hasFullScreenPrediction = predictions.any { it.isFullScreen }

        if (hasFullScreenPrediction) {
            // Draw full-screen blur or solid overlay
            drawFullScreenCoverage(canvas)
        } else if (showBoundingBoxes || blurredRegions.isEmpty()) {
            // Fallback to individual bounding boxes (shouldn't happen with new logic)
            drawBoundingBoxes(canvas)
        } else {
            // Draw individual blur regions
            drawBlur(canvas)
        }
    }

    /**
     * Draws full-screen coverage (blurred or solid) when NSFW is detected
     */
    private fun drawFullScreenCoverage(canvas: Canvas) {
        Log.d(TAG, "Drawing full-screen coverage")

        val fullScreenRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())

        // Draw blurred background if available, otherwise use solid overlay
        fullScreenBlurBitmap?.let { blurBitmap ->
            if (!blurBitmap.isRecycled) {
                // CROP & STRETCH: Crop out the unblurred edges and stretch the center
                // The blur algorithm leaves edges unblurred (clamping).
                // Calculate crop inset based on blur radius.
                // The blur radius applies to the pixels of the bitmap being blurred (the downsampled one).
                // So we must crop the full radius (plus safety margin) from the downsampled bitmap.
                val cropInset = (BLUR_RADIUS * 1.5f).coerceAtLeast(1f)
                
                val srcRect = Rect(
                    cropInset.toInt(),
                    cropInset.toInt(),
                    blurBitmap.width - cropInset.toInt(),
                    blurBitmap.height - cropInset.toInt()
                )
                
                // Draw heavily blurred full-screen image
                canvas.drawBitmap(blurBitmap, srcRect, fullScreenRect, blurPaint)
                
                // Draw dark scrim on top to prevent re-detection (feedback loop)
                canvas.drawRect(fullScreenRect, scrimPaint)
                
                Log.d(TAG, "Drew full-screen blur with cropping: inset=$cropInset")
            }
        } ?: run {
            // Fallback: draw solid semi-transparent overlay
            canvas.drawRect(fullScreenRect, fullScreenPaint)
            Log.d(TAG, "Drew full-screen solid overlay (blur unavailable)")
        }

        // Draw warning message overlay
        drawWarningOverlay(canvas)
    }

    /**
     * Draws warning message on top of full-screen coverage
     */
    private fun drawWarningOverlay(canvas: Canvas) {
        val warningText = "NSFW Content Detected"
        val subtitleText = "Inappropriate content blocked"

        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        // Draw warning icon (red shield with exclamation)
        val iconRadius = 100f

        // Red circle background
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
        }
        canvas.drawCircle(centerX, centerY - 180f, iconRadius, iconPaint)

        // White border around icon
        val iconBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 6f
        }
        canvas.drawCircle(centerX, centerY - 180f, iconRadius, iconBorderPaint)

        // White exclamation mark
        val exclamationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 120f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("!", centerX, centerY - 100f, exclamationPaint)

        // Draw warning text
        canvas.drawText(warningText, centerX, centerY + 60f, warningTitlePaint)
        canvas.drawText(subtitleText, centerX, centerY + 120f, warningSubtitlePaint)

        // Optional: Draw info text at bottom
        val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Content filtering active", centerX, viewHeight - 100f, infoPaint)
    }

    private fun drawBoundingBoxes(canvas: Canvas) {
        Log.d(TAG, "Drawing ${predictions.size} bounding boxes")
        Log.d(TAG, "View size: ${viewWidth}x${viewHeight}, Image size: ${imageWidth}x${imageHeight}")

        // Calculate scale factors if view and image dimensions differ
        val scaleX = if (imageWidth > 0) viewWidth.toFloat() / imageWidth else 1f
        val scaleY = if (imageHeight > 0) viewHeight.toFloat() / imageHeight else 1f

        Log.d(TAG, "Scale factors: scaleX=$scaleX, scaleY=$scaleY")

        val boundsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(255, 255, 0, 0) // Opaque RED
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(255, 255, 255, 0) // Yellow border
            strokeWidth = 3f
        }

        for ((index, p) in predictions.withIndex()) {
            // Get the bounding box in image coordinates
            val imageRect = p.boundingBox

            // Scale to view coordinates
            val viewRect = RectF(
                imageRect.left * scaleX,
                imageRect.top * scaleY,
                imageRect.right * scaleX,
                imageRect.bottom * scaleY
            )

            Log.d(TAG, "Box $index: image=(${imageRect.left}, ${imageRect.top}, ${imageRect.right}, ${imageRect.bottom}) -> view=(${viewRect.left}, ${viewRect.top}, ${viewRect.right}, ${viewRect.bottom})")

            // Draw solid opaque block
            canvas.drawRect(viewRect, boundsPaint)

            // Optional: Draw border for debugging
            canvas.drawRect(viewRect, borderPaint)

            // Draw label
            val label = "NSFW ${(p.confidence * 100).toInt()}%"
            val textWidth = labelPaint.measureText(label)
            val textHeight = labelPaint.textSize

            val labelRect = RectF(
                viewRect.left,
                viewRect.top - textHeight - 12f,
                viewRect.left + textWidth + 20f,
                viewRect.top
            )

            // Ensure label stays within view bounds
            if (labelRect.top < 0) {
                labelRect.offset(0f, -labelRect.top + 5f)
            }

            canvas.drawRect(labelRect, labelBackgroundPaint)
            canvas.drawText(label, labelRect.left + 10f, labelRect.bottom - 5f, labelPaint)
        }
    }

    private fun drawBlur(canvas: Canvas) {
        // Calculate scale factors if view and image dimensions differ
        val scaleX = if (imageWidth > 0) viewWidth.toFloat() / imageWidth else 1f
        val scaleY = if (imageHeight > 0) viewHeight.toFloat() / imageHeight else 1f

        blurredRegions.values.forEach { region ->
            // Scale the region rect to view coordinates
            val viewRect = RectF(
                region.rect.left * scaleX,
                region.rect.top * scaleY,
                region.rect.right * scaleX,
                region.rect.bottom * scaleY
            )
            canvas.drawBitmap(region.bitmap, null, viewRect, blurPaint)
        }
    }

    fun cleanup() {
        clearBlurRegions()
        clearFullScreenBlur()

        previousBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
        previousBitmap = null

        try {
            renderScript?.destroy()
        } catch (_: Exception) {}
        renderScript = null
    }
}