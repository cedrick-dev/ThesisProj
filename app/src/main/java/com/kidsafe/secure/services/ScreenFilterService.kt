package com.kidsafe.secure.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import androidx.core.app.NotificationCompat
import com.mansourappdevelopment.androidapp.kidsafe.R
import com.kidsafe.secure.nsfw.BlurOverlayView
import com.kidsafe.secure.nsfw.Prediction
import com.kidsafe.secure.nsfw.RoboflowContentDetector
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class ScreenFilterService : Service() {

    companion object {
        private const val TAG = "AegistNet-ScreenFilter"
        private const val NOTIFICATION_ID = 1001
        private const val PROCESS_INTERVAL_MS = 50L // 20 FPS
        private const val MAX_INFERENCE_TIME_MS = 300L

        // CRITICAL: Number of consecutive clean frames before hiding overlay
        // This prevents brief flicker when scrolling past NSFW content
        private const val CLEAN_FRAMES_THRESHOLD = 3

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_THRESHOLD = "threshold"
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var detector: RoboflowContentDetector? = null
    private var blurOverlay: BlurOverlayView? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var detectionThreshold = 0.6f

    // Aggressive downscale for faster inference
    private val DETECTION_SCALE_FACTOR = 0.4f

    // Reusable bitmaps
    private var reusableBitmap: Bitmap? = null
    private var scaledBitmap: Bitmap? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastProcessTime = 0L
    private var inferenceStartTime = 0L

    private val isProcessing = AtomicBoolean(false)

    private var isRunning = true
    private var isOverlayShowing = false

    // CRITICAL FIX: Track consecutive clean frames properly
    private var consecutiveCleanFrames = 0

    // Performance tracking
    private var totalFrames = 0
    private var detectedFrames = 0
    private var skippedFrames = 0
    private var lastStatsLog = System.currentTimeMillis()
    private val inferenceTimes = mutableListOf<Long>()

    private var processingThread: HandlerThread? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "Service onCreate - OVERLAY ABOVE CAPTURE")
        Log.d(TAG, "═══════════════════════════════════════")
        startForegroundNotification()

        try {
            detector = RoboflowContentDetector(this)
            Log.d(TAG, "✓ Detector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Detector initialization failed", e)
            stopSelf()
            return
        }

        setupOverlay()
    }

    private fun setupOverlay() {
        try {
            blurOverlay = BlurOverlayView(this)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.addView(blurOverlay, params)

            // Start hidden
            blurOverlay?.visibility = View.GONE

            Log.d(TAG, "✓ Overlay initialized ABOVE screen capture layer")
            Log.d(TAG, "✓ MediaProjection will capture UNDERNEATH overlay")

        } catch (e: Exception) {
            Log.e(TAG, "✗ Overlay setup failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        detectionThreshold = intent?.getFloatExtra(EXTRA_THRESHOLD, 0.6f) ?: 0.6f

        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "🚀 LAYER-BASED DETECTION MODE")
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "→ Model: Bikini_Model(Version2)")
        Log.d(TAG, "→ Threshold: ${(detectionThreshold * 100).toInt()}%")
        Log.d(TAG, "→ Detection: ${(DETECTION_SCALE_FACTOR * 100).toInt()}% resolution")
        Log.d(TAG, "→ Check interval: ${PROCESS_INTERVAL_MS}ms")
        Log.d(TAG, "→ Clean frame threshold: $CLEAN_FRAMES_THRESHOLD")
        Log.d(TAG, "→ Architecture:")
        Log.d(TAG, "   Layer 3: Blur Overlay (user sees)")
        Log.d(TAG, "   Layer 2: Screen capture (model sees)")
        Log.d(TAG, "   Layer 1: Actual screen content")
        Log.d(TAG, "═══════════════════════════════════════")

        if (rCode == Activity.RESULT_OK && data != null) {
            initProjection(rCode, data)
        } else {
            Log.e(TAG, "Invalid result code or data")
        }

        return START_STICKY
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        try {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d(TAG, "MediaProjection stopped")
                    isRunning = false
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))

            val metrics = Resources.getSystem().displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            Log.d(TAG, "Screen: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
            Log.d(TAG, "Detection: ${(screenWidth * DETECTION_SCALE_FACTOR).toInt()}x${(screenHeight * DETECTION_SCALE_FACTOR).toInt()}")

            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                4
            )

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AegistNet-ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            processingThread = HandlerThread("FastDetection", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
                start()
            }
            val handler = Handler(processingThread!!.looper)

            imageReader?.setOnImageAvailableListener(
                { reader -> processFrame(reader) },
                handler
            )

            Log.d(TAG, "✓ MediaProjection active - capturing BELOW overlay layer")

        } catch (e: Exception) {
            Log.e(TAG, "✗ Projection initialization failed", e)
            stopSelf()
        }
    }

    private fun processFrame(reader: ImageReader) {
        if (!isRunning) {
            reader.acquireLatestImage()?.close()
            return
        }

        val now = System.currentTimeMillis()

        // Time throttle
        val timeSinceLastProcess = now - lastProcessTime
        if (timeSinceLastProcess < PROCESS_INTERVAL_MS) {
            reader.acquireLatestImage()?.close()
            return
        }

        // Prevent concurrent processing
        if (!isProcessing.compareAndSet(false, true)) {
            reader.acquireLatestImage()?.close()
            return
        }

        val image = reader.acquireLatestImage()
        if (image == null) {
            isProcessing.set(false)
            return
        }

        inferenceStartTime = now
        lastProcessTime = now

        scope.launch(Dispatchers.Default) {
            var detectionBitmap: Bitmap? = null
            try {
                // Convert screen capture to bitmap
                val fullBitmap = imageToBitmapFast(image)
                image.close()

                if (fullBitmap == null) {
                    Log.w(TAG, "Failed to convert image")
                    return@launch
                }

                totalFrames++

                // Downscale for faster inference
                detectionBitmap = getScaledBitmapForDetection(fullBitmap)

                // Run ML detection
                val detectionStart = System.currentTimeMillis()
                val predictions = detector?.detect(detectionBitmap) ?: emptyList()
                val inferenceTime = System.currentTimeMillis() - detectionStart

                // Track performance
                inferenceTimes.add(inferenceTime)
                if (inferenceTimes.size > 10) inferenceTimes.removeAt(0)

                if (inferenceTime > MAX_INFERENCE_TIME_MS) {
                    skippedFrames++
                    if (skippedFrames % 5 == 0) {
                        Log.w(TAG, "⚠️ Slow inference: ${inferenceTime}ms")
                    }
                }

                // Filter by threshold
                val filteredPredictions = predictions.filter { it.confidence >= detectionThreshold }

                // Log stats every 30s
                if (now - lastStatsLog > 30000) {
                    val avgInference = if (inferenceTimes.isNotEmpty()) inferenceTimes.average() else 0.0
                    val detectionRate = if (totalFrames > 0) (detectedFrames.toFloat() / totalFrames * 100) else 0f
                    Log.d(TAG, "═══════════════════════════════════════")
                    Log.d(TAG, "📊 Stats:")
                    Log.d(TAG, "  Frames: $totalFrames (${skippedFrames} slow)")
                    Log.d(TAG, "  Detections: $detectedFrames (${String.format("%.1f", detectionRate)}%)")
                    Log.d(TAG, "  Avg inference: ${String.format("%.0f", avgInference)}ms")
                    Log.d(TAG, "  Overlay state: ${if (isOverlayShowing) "VISIBLE" else "hidden"}")
                    Log.d(TAG, "  Clean frame counter: $consecutiveCleanFrames")
                    Log.d(TAG, "═══════════════════════════════════════")
                    lastStatsLog = now
                }

                // Update overlay visibility
                withContext(Dispatchers.Main.immediate) {
                    updateOverlayVisibility(filteredPredictions, inferenceTime)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error", e)
            } finally {
                if (detectionBitmap != scaledBitmap) {
                    detectionBitmap?.recycle()
                }
                isProcessing.set(false)
            }
        }
    }

    private fun getScaledBitmapForDetection(fullBitmap: Bitmap): Bitmap {
        val targetWidth = (screenWidth * DETECTION_SCALE_FACTOR).toInt()
        val targetHeight = (screenHeight * DETECTION_SCALE_FACTOR).toInt()

        if (scaledBitmap == null ||
            scaledBitmap?.width != targetWidth ||
            scaledBitmap?.height != targetHeight ||
            scaledBitmap?.isRecycled == true) {

            scaledBitmap?.recycle()
            scaledBitmap = Bitmap.createScaledBitmap(fullBitmap, targetWidth, targetHeight, true)
        } else {
            val canvas = Canvas(scaledBitmap!!)
            val srcRect = Rect(0, 0, fullBitmap.width, fullBitmap.height)
            val dstRect = Rect(0, 0, targetWidth, targetHeight)
            canvas.drawBitmap(fullBitmap, srcRect, dstRect, null)
        }

        return scaledBitmap!!
    }

    /**
     * FIXED OVERLAY LOGIC:
     *
     * The overlay is positioned ABOVE the MediaProjection capture layer, so:
     * - The camera/model always sees the real screen (underneath the overlay)
     * - We can continuously detect even when overlay is shown
     * - Show overlay immediately when NSFW detected
     * - Hide overlay only after N consecutive clean frames
     */
    private fun updateOverlayVisibility(predictions: List<Prediction>, inferenceTime: Long) {
        try {
            val hasNsfwContent = predictions.isNotEmpty()

            if (hasNsfwContent) {
                // 🚨 NSFW DETECTED - Show overlay immediately and reset clean counter
                consecutiveCleanFrames = 0  // CRITICAL FIX: Reset here when NSFW found

                if (!isOverlayShowing) {
                    detectedFrames++
                    isOverlayShowing = true

                    blurOverlay?.updateNsfwStatus(true)
                    blurOverlay?.visibility = View.VISIBLE

                    val responseTime = System.currentTimeMillis() - inferenceStartTime
                    Log.w(TAG, "🚨 NSFW DETECTED! Response: ${responseTime}ms (inference: ${inferenceTime}ms)")
                    predictions.take(2).forEachIndexed { i, p ->
                        Log.d(TAG, "  [$i]: ${p.className} ${(p.confidence * 100).toInt()}%")
                    }
                } else {
                    // Overlay already showing, just log occasionally
                    if (totalFrames % 20 == 0) {
                        Log.d(TAG, "🚨 NSFW still present (${predictions.size} detections)")
                    }
                }
            } else {
                // ✅ NO NSFW - Increment clean counter
                consecutiveCleanFrames++

                if (isOverlayShowing) {
                    // Overlay is showing, check if we should hide it
                    if (consecutiveCleanFrames >= CLEAN_FRAMES_THRESHOLD) {
                        // Enough clean frames, hide the overlay now
                        isOverlayShowing = false

                        blurOverlay?.updateNsfwStatus(false)
                        blurOverlay?.visibility = View.GONE

                        val responseTime = System.currentTimeMillis() - inferenceStartTime
                        Log.d(TAG, "✅ Clean for $consecutiveCleanFrames frames - HIDING overlay (${responseTime}ms)")

                        // Keep counting clean frames in case we need stats
                        // But don't reset - let it accumulate
                    } else {
                        // Still waiting for more clean frames
                        if (consecutiveCleanFrames == 1) {
                            Log.d(TAG, "⏳ NSFW cleared, waiting for ${CLEAN_FRAMES_THRESHOLD - consecutiveCleanFrames} more clean frames...")
                        }
                    }
                } else {
                    // Overlay not showing, and content is clean - all good
                    // Occasionally log that we're monitoring
                    if (totalFrames % 100 == 0) {
                        Log.d(TAG, "👀 Monitoring... ($consecutiveCleanFrames consecutive clean frames)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UI update error", e)
        }
    }

    private fun imageToBitmapFast(image: Image): Bitmap? {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmapWidth = image.width + rowPadding / pixelStride

            if (reusableBitmap == null ||
                reusableBitmap?.width != bitmapWidth ||
                reusableBitmap?.height != image.height ||
                reusableBitmap?.isRecycled == true) {

                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(
                    bitmapWidth,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
            }

            buffer.rewind()
            reusableBitmap?.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                return reusableBitmap
            }

            val croppedBitmap = Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(croppedBitmap)
            val srcRect = Rect(0, 0, image.width, image.height)
            val dstRect = Rect(0, 0, image.width, image.height)
            canvas.drawBitmap(reusableBitmap!!, srcRect, dstRect, null)

            return croppedBitmap

        } catch (e: Exception) {
            Log.e(TAG, "Bitmap conversion failed", e)
            return null
        }
    }

    private fun startForegroundNotification() {
        val channelId = "kidsafe_nsfw_filter"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "NSFW Content Filter",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Layer-based detection"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AegistNet Protection")
            .setContentText("Overlay above capture layer")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service stopping...")

        isRunning = false

        val detectionRate = if (totalFrames > 0) (detectedFrames.toFloat() / totalFrames * 100) else 0f
        val avgInference = if (inferenceTimes.isNotEmpty()) inferenceTimes.average() else 0.0
        Log.d(TAG, "═══════════════════════════════════════")
        Log.d(TAG, "📊 Final Statistics:")
        Log.d(TAG, "  Total frames: $totalFrames")
        Log.d(TAG, "  Detections: $detectedFrames (${String.format("%.1f", detectionRate)}%)")
        Log.d(TAG, "  Avg inference: ${String.format("%.0f", avgInference)}ms")
        Log.d(TAG, "  Slow frames: $skippedFrames")
        Log.d(TAG, "═══════════════════════════════════════")

        scope.cancel()

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        processingThread?.quitSafely()
        processingThread = null

        reusableBitmap?.recycle()
        scaledBitmap?.recycle()
        reusableBitmap = null
        scaledBitmap = null

        try {
            detector?.close()
            detector = null
        } catch (e: Exception) {
            Log.e(TAG, "Detector cleanup error", e)
        }

        blurOverlay?.let { overlay ->
            try {
                overlay.cleanup()
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(overlay)
            } catch (e: Exception) {
                Log.e(TAG, "Overlay removal error", e)
            }
        }
        blurOverlay = null

        Log.d(TAG, "✓ Service stopped")
    }

    override fun onBind(intent: Intent?) = null
}