package com.example.voice

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.JarvisApplication
import com.example.MainActivity
import com.example.assistant.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Translucent glowing JARVIS mini-orb overlay that floats over any Android app.
 * Opens automatically when "Hey JARVIS" triggers outside the app (when Overlay permission is granted)
 * or when minimized into orb mode.
 */
class JarvisFloatingOrbService : Service() {

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var rootContainer: LinearLayout? = null
    private var orbView: GlowingMiniOrbView? = null
    private var bubbleCard: LinearLayout? = null
    private var statusTitleView: TextView? = null
    private var replyBodyView: TextView? = null
    private var micActionBtn: Button? = null
    private var quickInput: EditText? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isExpanded = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val expandOnStart = intent?.getBooleanExtra(EXTRA_EXPAND, false) == true
        if (rootContainer == null) {
            createOverlayViews()
        }
        setExpanded(expandOnStart)
        if (intent?.getBooleanExtra(EXTRA_START_LISTEN, false) == true) {
            val app = application as JarvisApplication
            if (app.voiceEngine.hasMicPermission()) {
                app.voiceEngine.startSingleCommandCapture()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        orbView?.stopAnim()
        rootContainer?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootContainer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayViews() {
        val app = application as JarvisApplication
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(150)
        }
        layoutParams = params

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        val orbSize = dp(66)
        val orb = GlowingMiniOrbView(this).apply {
            layoutParams = LinearLayout.LayoutParams(orbSize, orbSize)
            alpha = 0.90f
        }

        // Drag + tap gesture handler on the glowing orb
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false
        orb.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        moved = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { windowManager.updateViewLayout(container, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        setExpanded(!isExpanded)
                        if (isExpanded && app.voiceEngine.phase.value == VoicePipelinePhase.STOPPED && app.voiceEngine.hasMicPermission()) {
                            app.voiceEngine.startSingleCommandCapture()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // Translucent frosted mini HUD panel
        val cardBg = GradientDrawable().apply {
            setColor(Color.argb(226, 10, 17, 34))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(135, 0, 229, 255))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(292), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            visibility = View.GONE
        }

        // Card header row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = "JARVIS • Ready"
            setTextColor(Color.rgb(0, 229, 255))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val openAppBtn = createMiniButton("Full App") {
            val launch = Intent(this@JarvisFloatingOrbService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launch)
            setExpanded(false)
        }
        val closeOrbBtn = createMiniButton("Hide") {
            app.commandCoordinator.manualOrbPinned = false
            stopSelf()
        }
        headerRow.addView(titleTv)
        headerRow.addView(openAppBtn)
        headerRow.addView(closeOrbBtn)

        // Transcript / Response area
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(96)
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }
        val bodyTv = TextView(this).apply {
            text = "Say a command, ask to write in an app, or tap Speak."
            setTextColor(Color.rgb(235, 243, 255))
            textSize = 13f
        }
        scroll.addView(bodyTv)

        // Quick action row (Speak/Stop, Step Guide, Fill Credentials, Back, Mini)
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val speakBtn = createMiniButton("Speak") {
            if (app.voiceEngine.phase.value == VoicePipelinePhase.SPEAKING_RESPONSE) {
                app.voiceEngine.interruptSpeech()
            } else if (app.voiceEngine.hasMicPermission()) {
                app.voiceEngine.startSingleCommandCapture()
            }
        }
        val guideBtn = createMiniButton("Guide") {
            app.commandCoordinator.submitQuery("Guide me on this screen", fromVoice = false)
        }
        val fillBtn = createMiniButton("Fill Login") {
            app.commandCoordinator.submitQuery("Fill my credentials", fromVoice = false)
        }
        val backBtn = createMiniButton("Back") {
            JarvisAccessibilityService.activeInstance()?.performGlobalNavigation("back")
                ?: app.commandCoordinator.submitQuery("Go back", fromVoice = false)
        }
        val minBtn = createMiniButton("Mini") {
            setExpanded(false)
        }
        actionsRow.addView(speakBtn)
        actionsRow.addView(guideBtn)
        actionsRow.addView(fillBtn)
        actionsRow.addView(backBtn)
        actionsRow.addView(minBtn)

        // Text input bar (switches overlay focusability on tap)
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }
        val editText = EditText(this).apply {
            hint = "Ask or tell JARVIS to type..."
            setHintTextColor(Color.argb(150, 175, 198, 224))
            setTextColor(Color.WHITE)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(160, 20, 32, 60))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.argb(90, 56, 189, 248))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnTouchListener { _, _ ->
                enableKeyboardFocus(true)
                false
            }
        }
        val sendBtn = createMiniButton("Send") {
            val txt = editText.text?.toString()?.trim().orEmpty()
            if (txt.isNotBlank()) {
                editText.setText("")
                enableKeyboardFocus(false)
                app.commandCoordinator.submitQuery(txt, fromVoice = false)
            }
        }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendBtn.performClick()
                true
            } else false
        }

        inputRow.addView(editText)
        inputRow.addView(sendBtn)

        card.addView(headerRow)
        card.addView(scroll)
        card.addView(actionsRow)
        card.addView(inputRow)

        container.addView(orb)
        container.addView(card)

        rootContainer = container
        orbView = orb
        bubbleCard = card
        statusTitleView = titleTv
        replyBodyView = bodyTv
        micActionBtn = speakBtn
        quickInput = editText

        windowManager.addView(container, params)
        orb.startAnim()

        // Observe live voice, processing, and assistant reply state
        serviceScope.launch {
            val voiceTriple = combine(
                app.voiceEngine.phase,
                app.voiceEngine.rmsLevel,
                app.voiceEngine.partialTranscript
            ) { phase, rms, partial -> Triple(phase, rms, partial) }

            val replyTriple = combine(
                app.commandCoordinator.isProcessing,
                app.commandCoordinator.streamingReply,
                app.commandCoordinator.lastAssistantReply
            ) { processing, streaming, lastReply -> Triple(processing, streaming, lastReply) }

            combine(voiceTriple, replyTriple) { v, r ->
                OverlayStateSnapshot(
                    phase = v.first,
                    rms = v.second,
                    partial = v.third,
                    processing = r.first,
                    streaming = r.second,
                    lastReply = r.third
                )
            }.collectLatest { snap ->
                orb.updateVoiceVisuals(snap.phase, snap.rms, snap.processing)
                titleTv.text = when {
                    snap.processing -> "JARVIS • Thinking..."
                    snap.phase == VoicePipelinePhase.SPEAKING_RESPONSE -> "JARVIS • Speaking"
                    snap.phase == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE -> "JARVIS • Listening..."
                    snap.phase == VoicePipelinePhase.WAKE_WORD_STANDBY -> "JARVIS • \"Hey JARVIS\""
                    else -> "JARVIS • Mini Orb"
                }
                speakBtn.text = if (snap.phase == VoicePipelinePhase.SPEAKING_RESPONSE) "Stop" else "Speak"
                bodyTv.text = when {
                    snap.partial.isNotBlank() -> "\"${snap.partial}\""
                    snap.processing && snap.streaming.isNotBlank() -> snap.streaming
                    else -> snap.lastReply
                }
            }
        }
    }

    private fun createMiniButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            minHeight = dp(32)
            minimumHeight = dp(32)
            minWidth = dp(44)
            minimumWidth = dp(44)
            background = GradientDrawable().apply {
                setColor(Color.argb(190, 26, 95, 195))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setExpanded(expand: Boolean) {
        isExpanded = expand
        bubbleCard?.visibility = if (expand) View.VISIBLE else View.GONE
        if (!expand) {
            enableKeyboardFocus(false)
        }
    }

    private fun enableKeyboardFocus(focusable: Boolean) {
        val params = layoutParams ?: return
        val container = rootContainer ?: return
        params.flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        runCatching { windowManager.updateViewLayout(container, params) }
    }

    private data class OverlayStateSnapshot(
        val phase: VoicePipelinePhase,
        val rms: Float,
        val partial: String,
        val processing: Boolean,
        val streaming: String,
        val lastReply: String
    )

    private class GlowingMiniOrbView(context: Context) : View(context) {
        private var currentPhase = VoicePipelinePhase.STOPPED
        private var rms = 0f
        private var aiThinking = false
        private var animFraction = 0f

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arcRect = RectF()
        private var animator: ValueAnimator? = null

        fun startAnim() {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 3200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    animFraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnim() {
            animator?.cancel()
            animator = null
        }

        fun updateVoiceVisuals(phase: VoicePipelinePhase, level: Float, thinking: Boolean) {
            currentPhase = phase
            rms = level
            aiThinking = thinking
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val maxR = minOf(cx, cy)
            if (maxR <= 2f) return

            val speedAngle = if (aiThinking) animFraction * 2.5f else animFraction
            val accentColor = when {
                aiThinking -> Color.rgb(59, 130, 246)
                currentPhase == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE ||
                    currentPhase == VoicePipelinePhase.WAKE_WORD_STANDBY -> Color.rgb(0, 229, 255)
                currentPhase == VoicePipelinePhase.SPEAKING_RESPONSE -> Color.rgb(56, 189, 248)
                else -> Color.rgb(37, 99, 235)
            }

            // Translucent glowing halo
            corePaint.shader = RadialGradient(
                cx, cy, maxR,
                intArrayOf(
                    Color.argb(215, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)),
                    Color.argb(95, 0, 229, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0.28f, 0.65f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, maxR, corePaint)

            // Outer energy arc segments
            val outerR = maxR * 0.78f
            arcRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
            ringPaint.color = Color.argb(220, 0, 229, 255)
            ringPaint.strokeWidth = maxR * 0.09f
            for (i in 0 until 4) {
                canvas.drawArc(arcRect, speedAngle + i * 90f, 54f, false, ringPaint)
            }

            // Reactive soundwave pulse ring
            val waveR = maxR * (0.46f + rms * 0.18f + (sin(animFraction * PI / 180.0).toFloat() * 0.04f))
            ringPaint.color = Color.argb(190, 224, 247, 255)
            ringPaint.strokeWidth = maxR * 0.06f
            canvas.drawCircle(cx, cy, waveR, ringPaint)

            // Inner glowing core dot
            corePaint.shader = null
            corePaint.color = Color.argb(235, 225, 248, 255)
            val dotR = maxR * (0.24f + rms * 0.07f)
            canvas.drawCircle(cx, cy, dotR, corePaint)
        }
    }

    companion object {
        const val ACTION_SHOW = "com.example.voice.FLOATING_ORB_SHOW"
        const val ACTION_STOP = "com.example.voice.FLOATING_ORB_STOP"
        const val EXTRA_EXPAND = "extra_expand"
        const val EXTRA_START_LISTEN = "extra_start_listen"

        fun start(context: Context, expandBubble: Boolean = false, startListening: Boolean = false) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, JarvisFloatingOrbService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_EXPAND, expandBubble)
                putExtra(EXTRA_START_LISTEN, startListening)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, JarvisFloatingOrbService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
