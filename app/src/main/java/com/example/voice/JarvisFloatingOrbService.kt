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
    private var speakerPillView: TextView? = null
    private var replyBodyView: TextView? = null
    private var micActionBtn: Button? = null
    private var inputRowView: LinearLayout? = null
    private var typeToggleBtn: Button? = null
    private var quickInput: EditText? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isExpanded = false
    private var isTypingVisible = false

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
                app.voiceEngine.startSingleCommandCapture(keepConversationLoop = true)
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
            y = dp(140)
        }
        layoutParams = params

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        val orbSize = dp(66)
        val orb = GlowingMiniOrbView(this).apply {
            layoutParams = LinearLayout.LayoutParams(orbSize, orbSize)
            alpha = 0.92f
            contentDescription = "JARVIS Floating Voice Orb"
        }

        // Drag + voice-first tap gesture handler on the glowing orb
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
                        if (isExpanded &&
                            app.voiceEngine.phase.value != VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE &&
                            app.voiceEngine.hasMicPermission()
                        ) {
                            app.voiceEngine.startSingleCommandCapture(keepConversationLoop = true)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // Translucent frosted voice-first mini HUD panel
        val cardBg = GradientDrawable().apply {
            setColor(Color.argb(230, 8, 15, 32))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(150, 0, 229, 255))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(304), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
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
            text = "JARVIS • Listening"
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

        // Speaker biometric & autonomous telemetry pill
        val speakerTv = TextView(this).apply {
            text = "Voice-First • Hands-Free"
            setTextColor(Color.rgb(125, 211, 252))
            textSize = 11f
            setPadding(dp(2), dp(3), dp(2), dp(2))
        }

        // Transcript / Spoken response area
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(94)
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            }
        }
        val bodyTv = TextView(this).apply {
            text = "Listening for you, Sir — speak naturally anytime."
            setTextColor(Color.rgb(235, 243, 255))
            textSize = 13f
        }
        scroll.addView(bodyTv)

        // Row 1: Voice control + emergency Abort + universal social Like + screen Guide
        val actionsRowPrimary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val speakBtn = createMiniButton("Listen") {
            when (app.voiceEngine.phase.value) {
                VoicePipelinePhase.SPEAKING_RESPONSE -> app.voiceEngine.interruptSpeech()
                VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE -> {
                    app.voiceEngine.startAlwaysOnWakeWordLoop()
                }
                else -> {
                    if (app.voiceEngine.hasMicPermission()) {
                        app.voiceEngine.startSingleCommandCapture(keepConversationLoop = true)
                    }
                }
            }
        }
        val abortBtn = createMiniButton("Abort", bgColor = Color.argb(205, 190, 38, 56)) {
            app.commandCoordinator.abortAllPendingActions()
        }
        val likeBtn = createMiniButton("Like") {
            app.commandCoordinator.submitQuery("Like this post", fromVoice = false)
        }
        val guideBtn = createMiniButton("Guide") {
            app.commandCoordinator.submitQuery("Guide me on this screen", fromVoice = false)
        }
        actionsRowPrimary.addView(speakBtn)
        actionsRowPrimary.addView(abortBtn)
        actionsRowPrimary.addView(likeBtn)
        actionsRowPrimary.addView(guideBtn)

        // Row 2: Back + tucked-away Type toggle + Minimize
        val actionsRowSecondary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(5)
            }
        }
        val backBtn = createMiniButton("Back") {
            JarvisAccessibilityService.activeInstance()?.performGlobalNavigation("back")
                ?: app.commandCoordinator.submitQuery("Go back", fromVoice = false)
        }
        val typeToggle = createMiniButton("Type") {
            setTypingRowVisible(!isTypingVisible)
        }
        val minBtn = createMiniButton("Mini") {
            setExpanded(false)
        }
        actionsRowSecondary.addView(backBtn)
        actionsRowSecondary.addView(typeToggle)
        actionsRowSecondary.addView(minBtn)

        // Optional tucked-away text input bar (hidden by default so Orb stays voice-first)
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
        }
        val editText = EditText(this).apply {
            hint = "Optional text command..."
            setHintTextColor(Color.argb(150, 175, 198, 224))
            setTextColor(Color.WHITE)
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(165, 20, 32, 60))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.argb(95, 56, 189, 248))
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
                setTypingRowVisible(false)
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
        card.addView(speakerTv)
        card.addView(scroll)
        card.addView(actionsRowPrimary)
        card.addView(actionsRowSecondary)
        card.addView(inputRow)

        container.addView(orb)
        container.addView(card)

        rootContainer = container
        orbView = orb
        bubbleCard = card
        statusTitleView = titleTv
        speakerPillView = speakerTv
        replyBodyView = bodyTv
        micActionBtn = speakBtn
        inputRowView = inputRow
        typeToggleBtn = typeToggle
        quickInput = editText

        windowManager.addView(container, params)
        orb.startAnim()

        // Observe live voice, speaker verification, autonomous progress, and assistant reply state
        serviceScope.launch {
            val voiceBundle = combine(
                app.voiceEngine.phase,
                app.voiceEngine.rmsLevel,
                app.voiceEngine.partialTranscript,
                app.voiceEngine.lastSpeakerState,
                app.voiceEngine.speakerConfidence
            ) { phase, rms, partial, speakerState, speakerConf ->
                VoiceSnapshot(phase, rms, partial, speakerState, speakerConf)
            }

            val coordinatorBundle = combine(
                app.commandCoordinator.isProcessing,
                app.commandCoordinator.streamingReply,
                app.commandCoordinator.lastAssistantReply,
                app.commandCoordinator.autonomousStepStatus,
                app.commandCoordinator.pendingConfirmationSummary
            ) { processing, streaming, lastReply, stepStatus, pendingConfirm ->
                CoordinatorSnapshot(processing, streaming, lastReply, stepStatus, pendingConfirm)
            }

            combine(voiceBundle, coordinatorBundle) { v, c ->
                OverlayStateSnapshot(v, c)
            }.collectLatest { snap ->
                val v = snap.voice
                val c = snap.coordinator
                orb.updateVoiceVisuals(v.phase, v.rms, c.processing)
                titleTv.text = when {
                    c.autonomousStepStatus.isNotBlank() -> "JARVIS • Acting On Screen"
                    c.processing -> "JARVIS • Thinking..."
                    v.phase == VoicePipelinePhase.SPEAKING_RESPONSE -> "JARVIS • Speaking"
                    v.phase == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE -> "JARVIS • Listening..."
                    v.phase == VoicePipelinePhase.WAKE_WORD_STANDBY -> "JARVIS • \"Hey JARVIS\""
                    else -> "JARVIS • Voice Orb"
                }

                val confPct = (v.speakerConfidence * 100f).toInt().coerceIn(0, 100)
                speakerTv.text = when {
                    !c.pendingConfirmationSummary.isNullOrBlank() ->
                        "Confirm required: ${c.pendingConfirmationSummary}"
                    c.autonomousStepStatus.isNotBlank() ->
                        c.autonomousStepStatus
                    v.speakerState == SpeakerMatchState.VERIFIED_OWNER ->
                        "Owner Verified ($confPct%) • Voice-First"
                    v.speakerState == SpeakerMatchState.VERIFIED_VOCAL_SHIFT ->
                        "Vocal Shift Adapted ($confPct%) • Verified Sir"
                    v.speakerState == SpeakerMatchState.UNRECOGNIZED_SPEAKER ->
                        "Unrecognized Speaker ($confPct%) • Locked"
                    else ->
                        "Voice-First • Say a command anytime"
                }
                speakerTv.setTextColor(
                    when {
                        !c.pendingConfirmationSummary.isNullOrBlank() -> Color.rgb(251, 191, 36)
                        v.speakerState == SpeakerMatchState.UNRECOGNIZED_SPEAKER -> Color.rgb(248, 113, 113)
                        v.speakerState == SpeakerMatchState.VERIFIED_OWNER ||
                            v.speakerState == SpeakerMatchState.VERIFIED_VOCAL_SHIFT -> Color.rgb(52, 211, 153)
                        else -> Color.rgb(125, 211, 252)
                    }
                )

                speakBtn.text = when (v.phase) {
                    VoicePipelinePhase.SPEAKING_RESPONSE -> "Mute"
                    VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE -> "Pause"
                    else -> "Listen"
                }

                bodyTv.text = when {
                    v.partial.isNotBlank() -> "\"${v.partial}\""
                    c.processing && c.streaming.isNotBlank() -> c.streaming
                    else -> c.lastReply
                }
            }
        }
    }

    private fun setTypingRowVisible(visible: Boolean) {
        isTypingVisible = visible
        inputRowView?.visibility = if (visible) View.VISIBLE else View.GONE
        typeToggleBtn?.text = if (visible) "Voice" else "Type"
        if (visible) {
            enableKeyboardFocus(true)
            quickInput?.requestFocus()
        } else {
            enableKeyboardFocus(false)
        }
    }

    private fun createMiniButton(
        label: String,
        bgColor: Int = Color.argb(190, 26, 95, 195),
        onClick: () -> Unit
    ): Button {
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
                setColor(bgColor)
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
            setTypingRowVisible(false)
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

    private data class VoiceSnapshot(
        val phase: VoicePipelinePhase,
        val rms: Float,
        val partial: String,
        val speakerState: SpeakerMatchState?,
        val speakerConfidence: Float
    )

    private data class CoordinatorSnapshot(
        val processing: Boolean,
        val streaming: String,
        val lastReply: String,
        val autonomousStepStatus: String,
        val pendingConfirmationSummary: String?
    )

    private data class OverlayStateSnapshot(
        val voice: VoiceSnapshot,
        val coordinator: CoordinatorSnapshot
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
