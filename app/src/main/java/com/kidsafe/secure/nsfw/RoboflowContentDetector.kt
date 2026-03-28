package com.kidsafe.secure.nsfw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import kotlin.math.max
import kotlin.math.min

/**
 * Data class representing a detection prediction
 */
data class Prediction(
    val x: Float,      // Center X coordinate
    val y: Float,      // Center Y coordinate
    val w: Float,      // Width
    val h: Float,      // Height
    val confidence: Float,
    val className: String = "bikini",
    val classId: Int = 0,
    val isFullScreen: Boolean = false  // Flag to indicate full-screen coverage
) {
    val boundingBox: RectF by lazy {
        RectF(
            x - w / 2f,  // left
            y - h / 2f,  // top
            x + w / 2f,  // right
            y + h / 2f   // bottom
        )
    }

    override fun toString(): String {
        return "Prediction(center=(${x.toInt()}, ${y.toInt()}), size=(${w.toInt()}x${h.toInt()}), conf=${"%.3f".format(confidence)}, class=$className, fullScreen=$isFullScreen)"
    }
}

/**
 * Content detector for NSFW images using TensorFlow Lite model
 * Supports YOLOv8 format with shape [1, num_classes+4, num_anchors]
 * OPTIMIZED FOR SINGLE-CLASS MODEL: Updated_ModelTesting#4.tflite
 */
class RoboflowContentDetector(private val context: Context) {

    companion object {
        private const val TAG = "RoboflowDetector"
        private const val MODEL_FILE = "Updated_Current_Model.tflite"
        const val INPUT_SIZE = 512

        // Single class model
        private const val CLASS_NAME = "explicit"

        // Enable full-screen mode: when true, any detection triggers full-screen coverage
        private const val USE_FULLSCREEN_MODE = true
    }

    // Lower threshold for debugging and motion-blur tolerance
    // A moving screen creates motion blur which causes the AI's confidence to drop below 0.35.
    // 0.25 strikes a perfect balance: ignores pure false positives, but catches blurry scrolling explicit images.
    private val CONF_THRESH = 0.25f
    private val IOU_THRESH = 0.25f

    private var interpreter: Interpreter? = null
    private var imageProcessor: ImageProcessor? = null

    private var outputBuffer: Array<FloatArray>? = null
    private var outputWrapper: Array<Array<FloatArray>>? = null
    private var numClasses = 0
    private var numAnchors = 0

    // Frame counter for periodic detailed logging
    private var frameCount = 0

    init {
        try {
            val model = FileUtil.loadMappedFile(context, MODEL_FILE)

            val options = Interpreter.Options().apply {
                // Use exactly 2 threads. Opening 4 threads successfully spiked your inference 
                // to 800ms because the POCO C75 CPU couldn't handle the multi-threading overhead!
                setNumThreads(2)
                
                // NNAPI securely hooks into the Mediatek dedicated AI Processing Unit (APU) if it exists.
                // If it fails or your phone doesn't have an APU, it safely falls back to CPU without a crash!
                setUseNNAPI(true)
                setUseXNNPACK(true)
            }

            interpreter = Interpreter(model, options)

            val inputShape = interpreter?.getInputTensor(0)?.shape()
            val outputTensor = interpreter?.getOutputTensor(0)
            val outputShape = outputTensor?.shape()
            Log.d(TAG, "Model loaded - Input: ${inputShape?.contentToString()}, Output: ${outputShape?.contentToString()}")

            if (outputShape != null) {
                // YOLOv8 format: [1, num_features, num_anchors]
                // where num_features = 4 (bbox) + num_classes
                val numFeatures = outputShape[1]
                numAnchors = outputShape[2]
                numClasses = numFeatures - 4

                Log.d(TAG, "✓ Model - Features: $numFeatures, Anchors: $numAnchors, Classes: $numClasses")

                // Allocate buffer ONCE
                outputBuffer = Array(numFeatures) { FloatArray(numAnchors) }
                outputWrapper = arrayOf(outputBuffer!!)
            }

            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
                .add(CastOp(DataType.FLOAT32))
                .add(NormalizeOp(0f, 255f))
                .build()

            Log.d(TAG, "✓ Detector initialized with threshold: $CONF_THRESH")

        } catch (e: Exception) {
            Log.e(TAG, "✗ Model load failed", e)
        }
    }

    suspend fun detect(bitmap: Bitmap): List<Prediction> {
        return withContext(Dispatchers.Default) {
            val tfl = interpreter ?: return@withContext emptyList()
            val processor = imageProcessor ?: return@withContext emptyList()
            val outWrapper = outputWrapper ?: return@withContext emptyList()
            val outBuffer = outputBuffer ?: return@withContext emptyList()

            frameCount++
            val shouldLogDetails = frameCount % 30 == 0 // Log details every 30 frames

            val originalWidth = bitmap.width
            val originalHeight = bitmap.height

            if (shouldLogDetails) {
                Log.d(TAG, "=== FRAME $frameCount - Image: ${originalWidth}x${originalHeight} ===")
            }

            // CRITICAL: Create a mutable copy to avoid recycling issues
            val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)

            try {
                // Fetch the gender LIVE right before inference so if the parent changes it, it instantly updates!
                val childGender = com.mansourappdevelopment.androidapp.kidsafe.utils.SharedPrefsUtils.getStringPreference(context, "child_gender", null)
                val isBoy = childGender?.trim()?.equals("boy", ignoreCase = true) == true || childGender?.trim()?.equals("male", ignoreCase = true) == true
                val isGirl = childGender?.trim()?.equals("girl", ignoreCase = true) == true || childGender?.trim()?.equals("female", ignoreCase = true) == true

                // 1. Load Bitmap into TensorImage
                var tensorImage = TensorImage(DataType.UINT8)
                tensorImage.load(bitmapCopy)
                tensorImage = processor.process(tensorImage)

                // 2. Run Inference
                tfl.run(tensorImage.buffer, outWrapper)

                // 3. Parse predictions from YOLOv8 format
                val raw = parseYoloV8Predictions(outBuffer, originalWidth, originalHeight, shouldLogDetails, isBoy, isGirl)

                // 4. NMS
                val nms = nonMaxSuppression(raw)

                if (shouldLogDetails || nms.isNotEmpty()) {
                    Log.d(TAG, "Frame $frameCount: Raw=${raw.size}, After NMS=${nms.size}")
                    if (nms.isNotEmpty()) {
                        nms.take(3).forEachIndexed { index, pred ->
                            Log.d(TAG, "  [$index]: ${pred}")
                        }
                    }
                }

                if (USE_FULLSCREEN_MODE && nms.isNotEmpty()) {
                    val maxPred = nms.maxByOrNull { it.confidence }
                    val maxConfidence = maxPred?.confidence ?: 0.5f
                    val fullScreenPrediction = Prediction(
                        x = originalWidth / 2f,
                        y = originalHeight / 2f,
                        w = originalWidth.toFloat(),
                        h = originalHeight.toFloat(),
                        confidence = maxConfidence,
                        className = maxPred?.className ?: "unknown",
                        classId = maxPred?.classId ?: 0,
                        isFullScreen = true
                    )

                    if (shouldLogDetails) {
                        Log.d(TAG, "FULL-SCREEN MODE: Converting ${nms.size} detections to single full-screen prediction")
                    }

                    return@withContext listOf(fullScreenPrediction)
                }

                return@withContext nms
            } finally {
                bitmapCopy.recycle()
            }
        }
    }

    /**
     * Parse YOLOv8 format predictions for SINGLE-CLASS model
     * Format: [num_features, num_anchors] where features = [x, y, w, h, class_score]
     */
    private fun parseYoloV8Predictions(
        output: Array<FloatArray>,
        originalWidth: Int,
        originalHeight: Int,
        logDetails: Boolean,
        isBoy: Boolean,
        isGirl: Boolean
    ): List<Prediction> {
        val list = ArrayList<Prediction>(100)

        // Extract feature arrays
        val xs = output[0]      // Center X
        val ys = output[1]      // Center Y
        val ws = output[2]      // Width
        val hs = output[3]      // Height

        if (logDetails) {
            Log.d(TAG, "Parsing ${numAnchors} anchors, classes=$numClasses, threshold=$CONF_THRESH")
        }

        var detectedCount = 0

        for (i in 0 until numAnchors) {
            // Support multi-class by finding the max confidence across all classes
            var confidence = 0f
            var classId = 0
            for (c in 0 until numClasses) {
                // GENDER FILTERING RULE:
                // boy (male): flag class 1 (bikinis), class 2 (nudity)
                // girl (female): flag class 0 (mens underwear), class 2 (nudity)
                val isBlockedClass = if (isBoy) {
                    c == 1 || c == 2
                } else if (isGirl) {
                    c == 0 || c == 2
                } else {
                    if (logDetails && c == 0) {
                        Log.e(TAG, "GENDER UNRECOGNIZED: '$childGender'. Falling back to NUDITY-ONLY blocking.")
                    }
                    c == 2 // Fallback: only block nudity if gender is missing
                }

                if (!isBlockedClass) continue

                val score = output[4 + c][i]
                if (score > confidence) {
                    confidence = score
                    classId = c
                }
            }

            if (confidence < CONF_THRESH) continue

            detectedCount++

            val rawX = xs[i]
            val rawY = ys[i]
            val rawW = ws[i]
            val rawH = hs[i]
            
            // Auto-detect normalized vs pixel coordinates. 
            // If they are larger than 2.0f, they are likely in [0, INPUT_SIZE] range instead of [0, 1] range.
            val isNormalized = (rawX <= 2.0f && rawY <= 2.0f && rawW <= 2.0f && rawH <= 2.0f)
            
            val normX = if (isNormalized) rawX else rawX / INPUT_SIZE.toFloat()
            val normY = if (isNormalized) rawY else rawY / INPUT_SIZE.toFloat()
            val normW = if (isNormalized) rawW else rawW / INPUT_SIZE.toFloat()
            val normH = if (isNormalized) rawH else rawH / INPUT_SIZE.toFloat()

            val centerX = normX * originalWidth
            val centerY = normY * originalHeight
            val width = normW * originalWidth
            val height = normH * originalHeight

            // Validate box dimensions
            if (width < 10f || height < 10f) {
                if (logDetails && detectedCount <= 3) {
                    Log.d(TAG, "Skipped (too small): ${width.toInt()}x${height.toInt()}")
                }
                continue
            }

            if (width > originalWidth * 0.95f || height > originalHeight * 0.95f) {
                if (logDetails && detectedCount <= 3) {
                    Log.d(TAG, "Skipped (too large): ${width.toInt()}x${height.toInt()}")
                }
                continue
            }

            val left = centerX - width / 2f
            val top = centerY - height / 2f
            val right = centerX + width / 2f
            val bottom = centerY + height / 2f

            // Bounds checking with some tolerance
            if (left < -50 || top < -50 || right > originalWidth + 50 || bottom > originalHeight + 50) {
                if (logDetails && detectedCount <= 3) {
                    Log.d(TAG, "Skipped (out of bounds): box=(${left.toInt()}, ${top.toInt()}, ${right.toInt()}, ${bottom.toInt()})")
                }
                continue
            }

            if (logDetails && detectedCount <= 5) {
                Log.d(TAG, "Detection #$detectedCount: raw=(${rawX}, ${rawY}, ${rawW}, ${rawH}) -> pixel=(${centerX.toInt()}, ${centerY.toInt()}, ${width.toInt()}x${height.toInt()}), conf=${"%.3f".format(confidence)}")
            }

            list.add(
                Prediction(
                    x = centerX,
                    y = centerY,
                    w = width,
                    h = height,
                    confidence = confidence,
                    className = "class_$classId",
                    classId = classId
                )
            )
        }

        if (logDetails) {
            Log.d(TAG, "Total valid detections: ${list.size}")
        }

        return list
    }

    private fun nonMaxSuppression(preds: List<Prediction>): List<Prediction> {
        if (preds.isEmpty()) return emptyList()

        val sorted = preds.sortedByDescending { it.confidence }.toMutableList()
        val keep = ArrayList<Prediction>(preds.size)

        while (sorted.isNotEmpty()) {
            val top = sorted.removeAt(0)
            keep.add(top)

            val topBox = top.boundingBox

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (iou(topBox, next.boundingBox) > IOU_THRESH) {
                    iterator.remove()
                }
            }
        }

        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)

        val w = right - left
        val h = bottom - top

        if (w <= 0f || h <= 0f) return 0f

        val inter = w * h
        val union = a.width() * a.height() + b.width() * b.height() - inter

        return if (union <= 0f) 0f else inter / union
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        outputBuffer = null
        outputWrapper = null
        Log.d(TAG, "✓ Detector closed after $frameCount frames")
    }
}