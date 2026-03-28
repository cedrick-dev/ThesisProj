package com.kidsafe.secure.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.widget.Toast
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

        // ── Speed knobs ──────────────────────────────────────────────────────────
        // 25ms = 40 FPS heartbeat. We will NOT process 40 FPS, we will drop frames 
        // using the isProcessing lock. This means the second the CPU finishes one frame, 
        // it instantly grabs the VERY NEXT frame without any "dead air" wait gaps!
        private const val PROCESS_INTERVAL_MS = 25L
        private const val MAX_INFERENCE_TIME_MS = 300L

        // How many consecutive clean frames before hiding overlay.
        // 1 frame × 300ms = instant dismissal once content is gone.
        private const val CLEAN_FRAMES_THRESHOLD = 1

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

    private var detectionThreshold = 0.3f

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

    // Timestamp when overlay was last shown — used to enforce a minimum display time
    // so a single false-clean frame can't immediately dismiss the overlay.
    // 300ms is enough to absorb a couple of frames if FLAG_SECURE bleeds through.
    private var overlayShownAt = 0L
    private val MIN_OVERLAY_SHOW_MS = 300L  // 300ms minimum — fast dismiss when content is gone

    // Count how many consecutive clean frames we've seen while overlay is showing
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
            Log.d(TAG, "→ Using local TFLite model for detection")
            detector = RoboflowContentDetector(this)
            Log.d(TAG, "✓ YOLO detector initialized")
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
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND or   // ← nuclear opacity: blacks out everything behind
                        WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.OPAQUE
            ).also { lp ->
                lp.screenBrightness = 1.0f
                lp.dimAmount = 1.0f  // 1.0 = completely black behind — compositor has NO choice
            }

            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.addView(blurOverlay, params)

            // Start hidden
            blurOverlay?.visibility = View.GONE

            Log.d(TAG, "✓ Overlay initialized with FLAG_SECURE (should be invisible to screen capture)")

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
        Log.d(TAG, "→ Model: Updated_Current_Model")
        Log.d(TAG, "→ Threshold: ${(detectionThreshold * 100).toInt()}%")
        Log.d(TAG, "→ Detection: ${(DETECTION_SCALE_FACTOR * 100).toInt()}% resolution")
        Log.d(TAG, "→ Check interval: ${PROCESS_INTERVAL_MS}ms")
        Log.d(TAG, "→ Clean frame threshold: $CLEAN_FRAMES_THRESHOLD")
        Log.d(TAG, "→ Architecture:")
        Log.d(TAG, "   Layer 3: Blur Overlay (user sees, FLAG_SECURE)")
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

            // Start the decoupled AI inference loop
            startInferenceLoop()

        } catch (e: Exception) {
            Log.e(TAG, "✗ Projection initialization failed", e)
            stopSelf()
        }
    }

    private val frameLock = Object()
    private var latestAvailableImage: Image? = null

    // This is fired extremely fast by Android whenever pixels visually change.
    // It's critical this finishes in microseconds to never stall the OS buffer.
    private fun processFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        
        synchronized(frameLock) {
            // Free the older un-processed frame so Android doesn't run out of memory
            latestAvailableImage?.close() 
            // Save the absolute newest frame for the AI to eventually pick up
            latestAvailableImage = image
        }
    }

    private fun startInferenceLoop() {
        scope.launch(Dispatchers.Default) {
            while (isRunning) {
                // 1. Grab the freshest frame off the buffer stack
                val imageToProcess = synchronized(frameLock) {
                    val img = latestAvailableImage
                    latestAvailableImage = null
                    img
                }

                // 2. If there's a new frame, convert and analyze it
                if (imageToProcess != null) {
                    try {
                        totalFrames++
                        inferenceStartTime = System.currentTimeMillis()

                        val fullBitmap = imageToBitmapFast(imageToProcess)
                        imageToProcess.close() // Close immediately to avoid leak

                        if (fullBitmap != null) {
                            runSingleInferencePass(fullBitmap)
                        } else {
                            Log.w(TAG, "Failed to convert image")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Frame processing error", e)
                        try { imageToProcess.close() } catch (ignored: Exception) {}
                    }
                }

                // 3. Sleep briefly before checking for extremely fast new frames again
                // This naturally completely prevents any frames from stacking up. 
                // We do not need an `isProcessing` lock anymore!
                kotlinx.coroutines.delay(PROCESS_INTERVAL_MS)
            }
        }
    }

    private suspend fun runSingleInferencePass(fullBitmap: Bitmap) {
        var detectionBitmap: Bitmap? = null
        try {
            // Downscale for faster inference
            detectionBitmap = getScaledBitmapForDetection(fullBitmap)

            // Run YOLO for maximum speed
            val detectionStart = System.currentTimeMillis()
            val predictions = detector?.detect(detectionBitmap) ?: emptyList<Prediction>()
            val inferenceTime = System.currentTimeMillis() - detectionStart
            
            // --- DEBUGGER: Inference Timing ---
            Log.d("PerformanceTracker", "Model Scan Time: ${inferenceTime}ms. Found ${predictions.size} items.")

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

            // Update overlay visibility
            withContext(Dispatchers.Main.immediate) {
                updateOverlayVisibility(filteredPredictions, inferenceTime, fullBitmap)
            }

        } finally {
            if (detectionBitmap != scaledBitmap) {
                detectionBitmap?.recycle()
            }
        }
    }

    private fun getScaledBitmapForDetection(fullBitmap: Bitmap): Bitmap {
        // Scale directly to the TFLite model's expected shape to skip double-scaling overhead
        val targetWidth = RoboflowContentDetector.INPUT_SIZE
        val targetHeight = RoboflowContentDetector.INPUT_SIZE

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

    private fun updateOverlayVisibility(predictions: List<Prediction>, inferenceTime: Long, bitmap: Bitmap?) {
        try {
            val hasNsfwContent = predictions.isNotEmpty()

            if (hasNsfwContent) {
                if (!isOverlayShowing) {
                    isOverlayShowing = true
                    detectedFrames++

                    // Step 1: Show the overlay instantly
                    blurOverlay?.updatePredictions(predictions)
                    blurOverlay?.visibility = View.VISIBLE

                    // Step 2 & 3: Automatically trigger back button to exit user from the bad frame
                    NsfwActionManager.performGoBack()

                    val responseTime = System.currentTimeMillis() - inferenceStartTime
                    
                    // --- DEBUGGER: Overlay Reaction Timing ---
                    val debugMsg = "🚨 Reaction Time: Model took ${inferenceTime}ms | Overlay took ${responseTime - inferenceTime}ms | Total from capture to Block: ${responseTime}ms"
                    Log.w("PerformanceTracker", debugMsg)
                    
                    // Show a Toast message on the screen so you can see it physically!
                    Toast.makeText(applicationContext, debugMsg, Toast.LENGTH_LONG).show()

                    // Step 4: Hide overlay after back button so model can detect again.
                    // We hold the overlay for 1.5 seconds (1500ms) to ensure the Android
                    // screen-closing animation completes peacefully. If we hide it too fast
                    // (e.g. 500ms), the poor POCO C75 might still be animating the explicit image,
                    // causing the model to see it and trigger the whole process a SECOND time!
                    scope.launch(Dispatchers.Main.immediate) {
                        kotlinx.coroutines.delay(1500)
                        blurOverlay?.updatePredictions(emptyList())
                        blurOverlay?.visibility = View.GONE
                        isOverlayShowing = false
                        Log.w(TAG, "✅ Overlay hidden, ready to detect again")
                    }
                }
            }
            // No else block needed because the Coroutine perfectly handles hiding the overlay automatically
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
            .setContentText("Overlay active and protecting child")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        // Samsung S24 Ultra (Android 14+) strict crashing enforcement fix
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        
        synchronized(frameLock) {
            latestAvailableImage?.close()
            latestAvailableImage = null
        }

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
                overlay.destroy() // Use new destroy method
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