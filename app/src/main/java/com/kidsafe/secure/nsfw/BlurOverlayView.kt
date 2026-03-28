package com.kidsafe.secure.nsfw

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.View

/**
 * BlurOverlayView — full-screen NSFW content blocker overlay.
 *
 * Design goals:
 *  • Works on ALL Android versions (API 21 – 34+), including newer MIUI/OneUI devices.
 *  • Overlay is ALWAYS 100% opaque — never see-through on ANY device or Android version.
 *  • Triple-layer defence against transparency on new phones:
 *      1) setBackgroundColor() → View's own background (survives GPU compositing)
 *      2) canvas.drawColor(BLACK) → fills every pixel with an opaque black base
 *      3) canvas.drawColor(BRAND_COLOR) → brand color on top of black
 *  • Software rendering (LAYER_TYPE_SOFTWARE) — prevents alpha bleed-through
 *    that happens with hardware layers on Android 12+ / MIUI 14 / OneUI 6+.
 */
class BlurOverlayView(context: Context) : View(context) {

    companion object {
        private const val TAG = "BlurOverlayView"

        // Fully opaque brand color — deep dark red/purple
        private const val BRAND_COLOR = 0xFF1A0005.toInt()   // #1A0005, alpha=FF
        private const val BLACK_OPAQUE = 0xFF000000.toInt()  // pure black, alpha=FF
    }

    // ── Paints ────────────────────────────────────────────────────────────────

    /** Solid opaque fill — no alpha, no blending. */
    private val solidPaint = Paint().apply {
        style    = Paint.Style.FILL
        color    = BRAND_COLOR
        alpha    = 255      // force fully opaque
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)  // SRC replaces dst, no blending
        isAntiAlias = false // no AA needed for solid fills
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = 56f
        typeface  = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = 36f
        textAlign = Paint.Align.CENTER
        alpha     = 230
    }

    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = 28f
        textAlign = Paint.Align.CENTER
        alpha     = 200
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var predictions: List<Prediction> = emptyList()
    private var isDestroyed = false
    private var viewWidth  = 0
    private var viewHeight = 0

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        // ① Software rendering — prevents GPU compositing transparency on Android 12+/MIUI/OneUI
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        // ② View-level background — survives any window compositing pass
        //    This is the single most reliable guarantee of opacity
        setBackgroundColor(BLACK_OPAQUE)

        // ③ Disable over-scroll glow & fading edge which can introduce alpha
        isVerticalFadingEdgeEnabled  = false
        isHorizontalFadingEdgeEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun updatePredictions(newPredictions: List<Prediction>) {
        if (isDestroyed) return
        predictions = newPredictions
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth  = w
        viewHeight = h
    }

    override fun onDraw(canvas: Canvas) {
        // Do NOT call super.onDraw() — it could draw the background with alpha

        if (isDestroyed || predictions.isEmpty()) return

        // ── Layer 1: Opaque black base — covers everything, zero pixels leak through ──
        canvas.drawColor(BLACK_OPAQUE, PorterDuff.Mode.SRC)

        // ── Layer 2: Brand colour on top — SRC mode = no alpha blending ──
        canvas.drawColor(BRAND_COLOR, PorterDuff.Mode.SRC)

        // ── Layer 3: Warning UI ───────────────────────────────────────────────
        drawWarning(canvas)
    }

    // ── Warning overlay ───────────────────────────────────────────────────────

    private fun drawWarning(canvas: Canvas) {
        val cx = viewWidth  / 2f
        val cy = viewHeight / 2f

        // Shield/circle icon
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style       = Paint.Style.STROKE
            color       = Color.WHITE
            strokeWidth = 7f
            alpha       = 255
        }
        canvas.drawCircle(cx, cy - 190f, 110f, circlePaint)

        // Exclamation mark
        val excPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = Color.WHITE
            textSize  = 130f
            typeface  = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            alpha     = 255
        }
        canvas.drawText("!", cx, cy - 104f, excPaint)

        // Separator line
        val linePaint = Paint().apply {
            color       = Color.WHITE
            strokeWidth = 2f
            alpha       = 100
        }
        canvas.drawLine(cx - 160f, cy - 30f, cx + 160f, cy - 30f, linePaint)

        // Title
        canvas.drawText("NSFW Content Detected", cx, cy + 40f, titlePaint)

        // Subtitle
        canvas.drawText("Inappropriate content blocked", cx, cy + 100f, subtitlePaint)

        // Bottom info
        canvas.drawText("KidSafe • Content filtering active", cx, viewHeight - 120f, infoPaint)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun destroy() {
        Log.d(TAG, "Destroying BlurOverlayView")
        isDestroyed = true
        predictions = emptyList()
        viewWidth   = 0
        viewHeight  = 0
        Log.d(TAG, "✓ BlurOverlayView destroyed")
    }
}