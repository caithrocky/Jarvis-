package com.example.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

data class HighlightTarget(
    val rect: Rect,
    val badge: String = "",
    val label: String = "",
    val accentColor: Int = Color.rgb(0, 229, 255)
)

enum class ScrollOverlayDirection {
    UP, DOWN, LEFT, RIGHT
}

/**
 * Click-through translucent HUD overlay that renders real-time tap ripples,
 * bounding-box node highlights (including numbered #1/#2/#3 contact candidates),
 * and directional scroll chevrons over any screen.
 */
class JarvisVisualOverlayManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var hudView: FeedbackCanvasView? = null
    private var isAttached = false

    @Volatile
    var showTapEnabled: Boolean = true
    @Volatile
    var showHighlightEnabled: Boolean = true
    @Volatile
    var showScrollEnabled: Boolean = true

    private var clearRunnable: Runnable? = null

    fun showTapRipple(x: Float, y: Float, label: String = "") {
        if (!showTapEnabled) return
        mainHandler.post {
            val view = ensureAttachedView() ?: return@post
            view.triggerTap(x, y, label)
            scheduleAutoClear(1100L)
        }
    }

    fun showHighlightBoxes(targets: List<HighlightTarget>, durationMs: Long = 3500L) {
        if (!showHighlightEnabled || targets.isEmpty()) return
        mainHandler.post {
            val view = ensureAttachedView() ?: return@post
            view.setHighlights(targets)
            scheduleAutoClear(durationMs)
        }
    }

    fun showScrollChevron(direction: ScrollOverlayDirection) {
        if (!showScrollEnabled) return
        mainHandler.post {
            val view = ensureAttachedView() ?: return@post
            view.triggerScroll(direction)
            scheduleAutoClear(1000L)
        }
    }

    fun clearOverlays() {
        mainHandler.post {
            clearRunnable?.let { mainHandler.removeCallbacks(it) }
            clearRunnable = null
            hudView?.clearAll()
        }
    }

    fun detach() {
        mainHandler.post {
            clearRunnable?.let { mainHandler.removeCallbacks(it) }
            clearRunnable = null
            hudView?.stopAnimations()
            if (isAttached && hudView != null) {
                runCatching { windowManager.removeView(hudView) }
            }
            isAttached = false
            hudView = null
        }
    }

    private fun scheduleAutoClear(delayMs: Long) {
        clearRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            hudView?.clearAll()
        }
        clearRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun ensureAttachedView(): FeedbackCanvasView? {
        if (isAttached && hudView != null) return hudView

        val overlayType = when {
            context is android.accessibilityservice.AccessibilityService ->
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> return null
        }

        val view = FeedbackCanvasView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        return runCatching {
            windowManager.addView(view, params)
            hudView = view
            isAttached = true
            view
        }.getOrNull()
    }

    private class FeedbackCanvasView(context: Context) : View(context) {
        private var tapX = -1f
        private var tapY = -1f
        private var tapLabel = ""
        private var tapProgress = 0f
        private var tapAnimator: ValueAnimator? = null

        private var highlights: List<HighlightTarget> = emptyList()

        private var scrollDir: ScrollOverlayDirection? = null
        private var scrollProgress = 0f
        private var scrollAnimator: ValueAnimator? = null

        private val tapRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val tapFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val boxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(235, 0, 180, 230)
        }
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            isFakeBoldText = true
        }
        private val scrollPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        fun triggerTap(x: Float, y: Float, label: String) {
            tapX = x
            tapY = y
            tapLabel = label
            tapAnimator?.cancel()
            tapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    tapProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun setHighlights(targets: List<HighlightTarget>) {
            highlights = targets
            invalidate()
        }

        fun triggerScroll(direction: ScrollOverlayDirection) {
            scrollDir = direction
            scrollAnimator?.cancel()
            scrollAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 650L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    scrollProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun clearAll() {
            tapX = -1f
            tapY = -1f
            tapLabel = ""
            highlights = emptyList()
            scrollDir = null
            invalidate()
        }

        fun stopAnimations() {
            tapAnimator?.cancel()
            scrollAnimator?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // 1. Highlight bounding boxes + numbered #1/#2 badges
            highlights.forEach { target ->
                val r = RectF(target.rect)
                boxFillPaint.color = Color.argb(
                    42,
                    Color.red(target.accentColor),
                    Color.green(target.accentColor),
                    Color.blue(target.accentColor)
                )
                boxStrokePaint.color = target.accentColor
                canvas.drawRoundRect(r, 18f, 18f, boxFillPaint)
                canvas.drawRoundRect(r, 18f, 18f, boxStrokePaint)

                val tagText = buildString {
                    if (target.badge.isNotBlank()) append(target.badge)
                    if (target.label.isNotBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(target.label.take(22))
                    }
                }
                if (tagText.isNotBlank()) {
                    val tw = badgeTextPaint.measureText(tagText)
                    val bx = r.left.coerceAtLeast(16f)
                    val by = (r.top - 52f).coerceAtLeast(48f)
                    val pill = RectF(bx, by, bx + tw + 28f, by + 48f)
                    badgeBgPaint.color = Color.argb(235, 12, 25, 48)
                    canvas.drawRoundRect(pill, 12f, 12f, badgeBgPaint)
                    canvas.drawRoundRect(pill, 12f, 12f, boxStrokePaint)
                    canvas.drawText(tagText, bx + 14f, by + 36f, badgeTextPaint)
                }
            }

            // 2. Tap ripple animation
            if (tapX >= 0f && tapY >= 0f && tapProgress < 1f) {
                val alpha = ((1f - tapProgress) * 230).toInt().coerceIn(0, 255)
                val radius = 26f + tapProgress * 84f
                tapFillPaint.color = Color.argb(alpha / 3, 0, 229, 255)
                tapRingPaint.color = Color.argb(alpha, 0, 229, 255)
                canvas.drawCircle(tapX, tapY, radius * 0.55f, tapFillPaint)
                canvas.drawCircle(tapX, tapY, radius, tapRingPaint)
                if (tapLabel.isNotBlank()) {
                    badgeTextPaint.alpha = alpha
                    canvas.drawText(tapLabel.take(24), tapX + 24f, (tapY - 24f).coerceAtLeast(48f), badgeTextPaint)
                    badgeTextPaint.alpha = 255
                }
            }

            // 3. Directional Scroll chevrons
            val dir = scrollDir
            if (dir != null && scrollProgress < 1f) {
                val alpha = ((1f - scrollProgress) * 230).toInt().coerceIn(0, 255)
                scrollPaint.color = Color.argb(alpha, 16, 185, 129)
                val cx = width / 2f
                val cy = height / 2f
                val shift = (scrollProgress - 0.5f) * 140f
                val chevron = Path()
                when (dir) {
                    ScrollOverlayDirection.DOWN -> {
                        val y = cy - shift
                        chevron.moveTo(cx - 56f, y + 36f)
                        chevron.lineTo(cx, y - 20f)
                        chevron.lineTo(cx + 56f, y + 36f)
                    }
                    ScrollOverlayDirection.UP -> {
                        val y = cy + shift
                        chevron.moveTo(cx - 56f, y - 36f)
                        chevron.lineTo(cx, y + 20f)
                        chevron.lineTo(cx + 56f, y - 36f)
                    }
                    ScrollOverlayDirection.RIGHT -> {
                        val x = cx - shift
                        chevron.moveTo(x + 36f, cy - 56f)
                        chevron.lineTo(x - 20f, cy)
                        chevron.lineTo(x + 36f, cy + 56f)
                    }
                    ScrollOverlayDirection.LEFT -> {
                        val x = cx + shift
                        chevron.moveTo(x - 36f, cy - 56f)
                        chevron.lineTo(x + 20f, cy)
                        chevron.lineTo(x - 36f, cy + 56f)
                    }
                }
                canvas.drawPath(chevron, scrollPaint)
            }
        }
    }
}
