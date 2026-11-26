package com.example.voice

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.JarvisApplication
import com.example.MainActivity
import com.example.assistant.JarvisAccessibilityService
import com.example.assistant.MiniOrbStateMapper
import com.example.domain.model.MiniOrbVisualState
import com.example.domain.model.TaskExecutionProgress
import com.example.domain.model.TaskStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Translucent glowing 3D J.A.R.V.I.S Companion Mini Orb floating over Android screens.
 * Purely voice-first oral companion sharing conversation context, voice engine,
 * and central task execution with the full JARVIS application.
 */
class JarvisFloatingOrbService : Service() {

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var rootContainer: LinearLayout? = null
    private var orbView: DimensionalCompanionOrbView? = null
    private var bubbleCard: LinearLayout? = null
    private var statusTitleView: TextView? = null
    private var speakerPillView: TextView? = null
    private var replyBodyView: TextView? = null
    private var micActionBtn: Button? = null
    private var muteActionBtn: Button? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isExpanded = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as? JarvisApplication)?.commandCoordinator?.orbLifecycle?.setExpanded(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val app = application as JarvisApplication
        val expandOnStart = intent?.getBooleanExtra(EXTRA_EXPAND, false) == true
        if (rootContainer == null) {
            createOverlayViews()
        }
        setExpanded(expandOnStart)
        app.commandCoordinator.orbLifecycle.setOnAutoSleep {
            setExpanded(false)
        }
        if (intent?.getBooleanExtra(EXTRA_START_LISTEN, false) == true) {
            if (app.voiceEngine.hasMicPermission() && !app.voiceEngine.isMuted.value) {
                app.voiceEngine.startSingleCommandCapture(keepConversationLoop = true, playChime = true)
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
            x = dp(20)
            y = dp(148)
        }
        layoutParams = params

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        val orbW = dp(68)
        val orbH = dp(76)
        val orb = DimensionalCompanionOrbView(this).apply {
            layoutParams = LinearLayout.LayoutParams(orbW, orbH)
            alpha = 0.98f
            contentDescription = "JARVIS Floating Companion Orb"
        }

        // Drag + oral tap gesture handler
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
                        val nextExpand = !isExpanded
                        setExpanded(nextExpand)
                        app.commandCoordinator.orbLifecycle.touchActivity()
                        if (nextExpand &&
                            !app.voiceEngine.isMuted.value &&
                            app.voiceEngine.phase.value != VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE &&
                            app.voiceEngine.hasMicPermission()
                        ) {
                            app.voiceEngine.startSingleCommandCapture(keepConversationLoop = true, playChime = true)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // Translucent Midnight-Glass Companion Card (100% oral, zero typing UI)
        val cardBg = GradientDrawable().apply {
            setColor(Color.argb(232, 8, 15, 34))
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.argb(140, 108, 200, 255))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(308), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            visibility = View.GONE
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = "J.A.R.V.I.S • Companion"
            setTextColor(Color.rgb(96, 214, 255))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val openAppBtn = createMiniButton("Full App", bgColor = Color.argb(190, 22, 68, 138)) {
            app.commandCoordinator.sessionManager.onExplicitSwitchToFullApp()
            val launch = Intent(this@JarvisFloatingOrbService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(launch)
            app.commandCoordinator.manualOrbPinned = false
            setExpanded(false)
            stopSelf()
        }
        val closeOrbBtn = createMiniButton("Sleep", bgColor = Color.argb(180, 30, 41, 68)) {
            app.commandCoordinator.manualOrbPinned = false
            app.commandCoordinator.orbLifecycle.setStayAwakePinned(false)
            app.commandCoordinator.sessionManager.setStayAwakePinned(false)
            setExpanded(false)
            stopSelf()
        }
        headerRow.addView(titleTv)
        headerRow.addView(openAppBtn)
        headerRow.addView(closeOrbBtn)

        val speakerTv = TextView(this).apply {
            text = "Companion Orb • Ready"
            setTextColor(Color.rgb(45, 212, 191))
            textSize = 11f
            setPadding(dp(2), dp(3), dp(2), dp(2))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(92)
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            }
        }
        val bodyTv = TextView(this).apply {
            text = "Listening for you, Sir — speak naturally anytime."
            setTextColor(Color.rgb(238, 244, 255))
            textSize = 13f
        }
        scroll.addView(bodyTv)

        val actionsRowPrimary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val speakBtn = createMiniButton("Listen") {
            app.commandCoordinator.orbLifecycle.touchActivity()
            when (app.voiceEngine.phase.value) {
                VoicePipelinePhase.SPEAKING_RESPONSE -> {
                    app.voiceEngine.interruptSpeech()
                    if (app.voiceEngine.hasMicPermission()) {
                        app.voiceEngine.startSingleCommandCapture(keepConversationLoop = true)
                    }
                }
                VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE -> {
                    app.voiceEngine.startAlwaysOnWakeWordLoop()
                }
                else -> {
                    if (app.voiceEngine.hasMicPermission()) {
                        app.voiceEngine.startSingleCommandCapture(keepConversationLoop = true, playChime = true)
                    }
                }
            }
        }
        val muteBtn = createMiniButton("Mute", bgColor = Color.argb(190, 68, 56, 128)) {
            app.commandCoordinator.orbLifecycle.touchActivity()
            app.voiceEngine.toggleMute()
        }
        val abortBtn = createMiniButton("Abort", bgColor = Color.argb(205, 190, 38, 56)) {
            app.commandCoordinator.abortAllPendingActions()
        }
        val guideBtn = createMiniButton("Guide") {
            app.commandCoordinator.orbLifecycle.touchActivity()
            app.commandCoordinator.submitQuery("Guide me on this screen", fromVoice = false)
        }
        actionsRowPrimary.addView(speakBtn)
        actionsRowPrimary.addView(muteBtn)
        actionsRowPrimary.addView(abortBtn)
        actionsRowPrimary.addView(guideBtn)

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
        val likeBtn = createMiniButton("Like") {
            app.commandCoordinator.orbLifecycle.touchActivity()
            app.commandCoordinator.submitQuery("Like this post", fromVoice = false)
        }
        val backBtn = createMiniButton("Back") {
            app.commandCoordinator.orbLifecycle.touchActivity()
            JarvisAccessibilityService.activeInstance()?.performGlobalNavigation("back")
                ?: app.commandCoordinator.submitQuery("Go back", fromVoice = false)
        }
        val stayBtn = createMiniButton("Stay") {
            app.commandCoordinator.orbLifecycle.touchActivity()
            app.commandCoordinator.submitQuery("Stay here", fromVoice = false)
        }
        val minBtn = createMiniButton("Hide") {
            setExpanded(false)
        }
        actionsRowSecondary.addView(likeBtn)
        actionsRowSecondary.addView(backBtn)
        actionsRowSecondary.addView(stayBtn)
        actionsRowSecondary.addView(minBtn)

        card.addView(headerRow)
        card.addView(speakerTv)
        card.addView(scroll)
        card.addView(actionsRowPrimary)
        card.addView(actionsRowSecondary)

        container.addView(orb)
        container.addView(card)

        rootContainer = container
        orbView = orb
        bubbleCard = card
        statusTitleView = titleTv
        speakerPillView = speakerTv
        replyBodyView = bodyTv
        micActionBtn = speakBtn
        muteActionBtn = muteBtn

        windowManager.addView(container, params)
        orb.startAnim()

        serviceScope.launch {
            val voiceBundle = combine(
                app.voiceEngine.phase,
                app.voiceEngine.rmsLevel,
                app.voiceEngine.partialTranscript,
                app.voiceEngine.isMuted,
                app.voiceEngine.lastSpeakerState
            ) { phase, rms, partial, muted, speakerState ->
                VoiceSnapshot(phase, rms, partial, muted, speakerState, app.voiceEngine.speakerConfidence.value)
            }

            val coordinatorBundle = combine(
                app.commandCoordinator.isProcessing,
                app.commandCoordinator.streamingReply,
                app.commandCoordinator.lastAssistantReply,
                app.commandCoordinator.taskProgress,
                app.commandCoordinator.pendingConfirmationSummary
            ) { processing, streaming, lastReply, taskProg, pendingConfirm ->
                CoordinatorSnapshot(processing, streaming, lastReply, taskProg, pendingConfirm)
            }

            val lifecycleBundle = combine(
                app.commandCoordinator.orbLifecycle.wakeFlashActive,
                app.commandCoordinator.orbLifecycle.terminalFlash,
                app.commandCoordinator.orbLifecycle.isOrbExpanded
            ) { wakeFlash, termFlash, expanded ->
                LifecycleSnapshot(wakeFlash, termFlash, expanded)
            }

            combine(voiceBundle, coordinatorBundle, lifecycleBundle) { v, c, l ->
                OverlayStateSnapshot(v, c, l)
            }.collectLatest { snap ->
                val v = snap.voice
                val c = snap.coordinator
                val l = snap.lifecycle

                val visualState = MiniOrbStateMapper.resolve(
                    isMuted = v.isMuted,
                    voicePhase = v.phase,
                    isAiProcessing = c.processing,
                    taskProgress = c.taskProgress,
                    wakeFlashActive = l.wakeFlash,
                    terminalFlash = l.terminalFlash
                )

                val busy = c.processing ||
                    v.phase == VoicePipelinePhase.SPEAKING_RESPONSE ||
                    v.phase == VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE ||
                    c.taskProgress.isBusy
                app.commandCoordinator.orbLifecycle.onInteractionBusy(busy)

                orb.updateCompanionVisuals(
                    visualState = visualState,
                    rmsLevel = v.rms,
                    taskFraction = c.taskProgress.fraction
                )

                titleTv.text = when (visualState) {
                    MiniOrbVisualState.MUTED -> "J.A.R.V.I.S • Muted"
                    MiniOrbVisualState.WAKE_DETECTED -> "J.A.R.V.I.S • Awake"
                    MiniOrbVisualState.LISTENING -> "J.A.R.V.I.S • Listening"
                    MiniOrbVisualState.UNDERSTANDING -> "J.A.R.V.I.S • Understanding"
                    MiniOrbVisualState.SPEAKING -> "J.A.R.V.I.S • Speaking"
                    MiniOrbVisualState.EXECUTING -> "J.A.R.V.I.S • Executing"
                    MiniOrbVisualState.SUCCESS -> "J.A.R.V.I.S • Complete"
                    MiniOrbVisualState.ERROR -> "J.A.R.V.I.S • Warning"
                    MiniOrbVisualState.STANDBY -> "J.A.R.V.I.S • Standby"
                }

                val confPct = (v.speakerConfidence * 100f).toInt().coerceIn(0, 100)
                speakerTv.text = when {
                    v.isMuted -> "Microphone muted • Tap Unmute anytime"
                    !c.pendingConfirmationSummary.isNullOrBlank() ->
                        "Confirm: ${c.pendingConfirmationSummary}"
                    c.taskProgress.stage != TaskStage.IDLE && c.taskProgress.detail.isNotBlank() ->
                        "${c.taskProgress.stage.label}: ${c.taskProgress.detail}"
                    v.speakerState == SpeakerMatchState.VERIFIED_OWNER ->
                        "Owner Verified ($confPct%) • Oral Conversation"
                    v.speakerState == SpeakerMatchState.VERIFIED_VOCAL_SHIFT ->
                        "Vocal Shift Adapted ($confPct%) • Verified Sir"
                    v.speakerState == SpeakerMatchState.UNRECOGNIZED_SPEAKER ->
                        "Unrecognized Speaker ($confPct%) • Locked"
                    else ->
                        "Companion Mode • Say \"Open full app\" anytime"
                }
                speakerTv.setTextColor(
                    when {
                        v.isMuted -> Color.rgb(148, 163, 184)
                        visualState == MiniOrbVisualState.ERROR || v.speakerState == SpeakerMatchState.UNRECOGNIZED_SPEAKER ->
                            Color.rgb(248, 113, 113)
                        !c.pendingConfirmationSummary.isNullOrBlank() -> Color.rgb(251, 191, 36)
                        visualState == MiniOrbVisualState.SUCCESS ||
                            v.speakerState == SpeakerMatchState.VERIFIED_OWNER ||
                            v.speakerState == SpeakerMatchState.VERIFIED_VOCAL_SHIFT ->
                            Color.rgb(45, 212, 191)
                        else -> Color.rgb(125, 211, 252)
                    }
                )

                speakBtn.text = when (v.phase) {
                    VoicePipelinePhase.SPEAKING_RESPONSE -> "Interrupt"
                    VoicePipelinePhase.ACTIVE_COMMAND_CAPTURE -> "Pause"
                    else -> "Listen"
                }
                muteBtn.text = if (v.isMuted) "Unmute" else "Mute"

                bodyTv.text = when {
                    v.partial.isNotBlank() -> "\"${v.partial}\""
                    c.processing && c.streaming.isNotBlank() -> c.streaming
                    else -> c.lastReply
                }
            }
        }
    }

    private fun createMiniButton(
        label: String,
        bgColor: Int = Color.argb(195, 22, 86, 178),
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            minHeight = dp(34)
            minimumHeight = dp(34)
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
        (application as? JarvisApplication)?.commandCoordinator?.orbLifecycle?.setExpanded(expand)
        bubbleCard?.visibility = if (expand) View.VISIBLE else View.GONE
    }

    private data class VoiceSnapshot(
        val phase: VoicePipelinePhase,
        val rms: Float,
        val partial: String,
        val isMuted: Boolean,
        val speakerState: SpeakerMatchState?,
        val speakerConfidence: Float
    )

    private data class CoordinatorSnapshot(
        val processing: Boolean,
        val streaming: String,
        val lastReply: String,
        val taskProgress: TaskExecutionProgress,
        val pendingConfirmationSummary: String?
    )

    private data class LifecycleSnapshot(
        val wakeFlash: Boolean,
        val terminalFlash: MiniOrbVisualState?,
        val expanded: Boolean
    )

    private data class OverlayStateSnapshot(
        val voice: VoiceSnapshot,
        val coordinator: CoordinatorSnapshot,
        val lifecycle: LifecycleSnapshot
    )

    /**
     * Smooth 3D porcelain-iridescent capsule-pod companion orb with dark curved glass visor
     * and expressive glowing cyan eyes matching the user's visual reference.
     */
    private class DimensionalCompanionOrbView(context: Context) : View(context) {
        private var state = MiniOrbVisualState.STANDBY
        private var rms = 0f
        private var taskFraction = 0f
        private var animDeg = 0f

        private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val visorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val bodyRect = RectF()
        private val visorRect = RectF()
        private val arcRect = RectF()
        private val wavePath = Path()
        private var animator: ValueAnimator? = null

        fun startAnim() {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 3800L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    animDeg = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnim() {
            animator?.cancel()
            animator = null
        }

        fun updateCompanionVisuals(
            visualState: MiniOrbVisualState,
            rmsLevel: Float,
            taskFraction: Float
        ) {
            this.state = visualState
            this.rms = rmsLevel
            this.taskFraction = taskFraction
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 8f || h <= 8f) return

            val phaseRad = animDeg * PI / 180.0
            val bobY = if (state == MiniOrbVisualState.MUTED) 0f else (sin(phaseRad * 2.0) * h * 0.025f).toFloat()
            val cx = w / 2f
            val cy = h / 2f + bobY

            val podRx = w * 0.39f
            val podRy = h * 0.43f

            val eyeColor = when (state) {
                MiniOrbVisualState.STANDBY -> Color.rgb(42, 232, 255)
                MiniOrbVisualState.WAKE_DETECTED -> Color.rgb(140, 250, 255)
                MiniOrbVisualState.LISTENING -> Color.rgb(0, 240, 255)
                MiniOrbVisualState.UNDERSTANDING -> Color.rgb(165, 180, 252)
                MiniOrbVisualState.SPEAKING -> Color.rgb(94, 242, 224)
                MiniOrbVisualState.EXECUTING -> Color.rgb(56, 220, 255)
                MiniOrbVisualState.SUCCESS -> Color.rgb(52, 211, 153)
                MiniOrbVisualState.ERROR -> Color.rgb(251, 191, 36)
                MiniOrbVisualState.MUTED -> Color.rgb(100, 116, 139)
            }

            // Soft atmospheric outer glow behind the 3D capsule pod
            val haloAlpha = when (state) {
                MiniOrbVisualState.MUTED -> 35
                MiniOrbVisualState.WAKE_DETECTED, MiniOrbVisualState.LISTENING -> (130 + rms * 90).toInt().coerceIn(110, 220)
                else -> 88
            }
            shellPaint.shader = RadialGradient(
                cx, cy, podRy * 1.25f,
                intArrayOf(
                    Color.argb(haloAlpha, Color.red(eyeColor), Color.green(eyeColor), Color.blue(eyeColor)),
                    Color.argb(haloAlpha / 3, 168, 128, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0.50f, 0.78f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, podRy * 1.18f, shellPaint)

            // 3D Iridescent Lavender-White-Blue Capsule Pod Body
            bodyRect.set(cx - podRx, cy - podRy, cx + podRx, cy + podRy)
            shellPaint.shader = LinearGradient(
                bodyRect.left, cy - podRy * 0.35f,
                bodyRect.right, cy + podRy * 0.45f,
                intArrayOf(
                    Color.rgb(225, 152, 236), // soft violet-pink left highlight
                    Color.rgb(236, 242, 255), // smooth porcelain crown center
                    Color.rgb(98, 178, 255),  // cool cyan-cobalt right edge
                    Color.rgb(24, 34, 68)     // deep shadow rim
                ),
                floatArrayOf(0.0f, 0.42f, 0.78f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(bodyRect, shellPaint)

            // Vertical glossy dome sheen + grounded lower shadow for spherical 3D volume
            shellPaint.shader = LinearGradient(
                cx, bodyRect.top,
                cx, bodyRect.bottom,
                intArrayOf(
                    Color.argb(215, 255, 255, 255),
                    Color.argb(25, 255, 255, 255),
                    Color.argb(185, 8, 14, 34)
                ),
                floatArrayOf(0.0f, 0.46f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(bodyRect, shellPaint)

            // Subtle specular crescent at the very top of the pod
            shellPaint.shader = null
            shellPaint.color = Color.argb(135, 255, 255, 255)
            canvas.drawOval(
                cx - podRx * 0.44f,
                bodyRect.top + podRy * 0.08f,
                cx + podRx * 0.44f,
                bodyRect.top + podRy * 0.24f,
                shellPaint
            )

            // Dark recessed curved visor screen across upper face
            val visorW = podRx * 1.48f
            val visorH = podRy * 0.62f
            val visorCy = cy - podRy * 0.14f
            visorRect.set(
                cx - visorW / 2f,
                visorCy - visorH / 2f,
                cx + visorW / 2f,
                visorCy + visorH / 2f
            )
            visorPaint.shader = LinearGradient(
                visorRect.left, visorRect.top,
                visorRect.left, visorRect.bottom,
                intArrayOf(
                    Color.rgb(18, 28, 52),
                    Color.rgb(7, 13, 28),
                    Color.rgb(11, 20, 42)
                ),
                floatArrayOf(0.0f, 0.55f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(visorRect, visorH * 0.46f, visorH * 0.46f, visorPaint)

            // Slim glossy rim around visor
            visorPaint.shader = null
            arcPaint.color = Color.argb(95, 165, 215, 255)
            arcPaint.strokeWidth = w * 0.018f
            canvas.drawRoundRect(visorRect, visorH * 0.46f, visorH * 0.46f, arcPaint)

            // State-specific outer rim cue (only subtle halo for listening/executing)
            if (state == MiniOrbVisualState.EXECUTING) {
                arcRect.set(
                    bodyRect.left - w * 0.05f,
                    bodyRect.top - w * 0.05f,
                    bodyRect.right + w * 0.05f,
                    bodyRect.bottom + w * 0.05f
                )
                arcPaint.color = Color.argb(230, 45, 212, 191)
                arcPaint.strokeWidth = w * 0.045f
                canvas.drawArc(arcRect, -90f + animDeg * 1.4f, taskFraction.coerceIn(0.18f, 1f) * 300f, false, arcPaint)
            } else if (state == MiniOrbVisualState.LISTENING) {
                arcPaint.color = Color.argb((120 + rms * 115).toInt().coerceIn(100, 235), 42, 232, 255)
                arcPaint.strokeWidth = w * 0.035f
                val expand = w * (0.04f + rms * 0.07f)
                arcRect.set(
                    bodyRect.left - expand,
                    bodyRect.top - expand,
                    bodyRect.right + expand,
                    bodyRect.bottom + expand
                )
                canvas.drawOval(arcRect, arcPaint)
            }

            // Glowing Companion Eyes / Audio Expression inside the dark visor
            drawVisorExpression(canvas, visorRect, eyeColor, phaseRad)
        }

        private fun drawVisorExpression(
            canvas: Canvas,
            visor: RectF,
            eyeColor: Int,
            phaseRad: Double
        ) {
            val vCx = visor.centerX()
            val vCy = visor.centerY()
            val eyeSpacing = visor.width() * 0.23f
            val blinkScale = if (state == MiniOrbVisualState.STANDBY && (animDeg % 360f) > 346f) 0.18f else 1f

            when (state) {
                MiniOrbVisualState.SPEAKING -> {
                    // Audio-reactive glowing cyan wave bar inside visor
                    wavePath.reset()
                    val halfW = visor.width() * 0.34f
                    val amp = visor.height() * (0.16f + rms * 0.26f)
                    val steps = 16
                    for (i in 0..steps) {
                        val frac = i / steps.toFloat()
                        val x = vCx - halfW + frac * (halfW * 2f)
                        val env = sin(frac * PI).toFloat()
                        val y = vCy + (sin(frac * PI * 3.0 + phaseRad * 4.5) * amp * env).toFloat()
                        if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                    }
                    arcPaint.color = eyeColor
                    arcPaint.strokeWidth = visor.height() * 0.16f
                    canvas.drawPath(wavePath, arcPaint)
                }

                MiniOrbVisualState.MUTED -> {
                    // Dimmed sleeping visor eyes + subtle slash
                    eyePaint.shader = null
                    eyePaint.color = Color.argb(135, 148, 163, 184)
                    val lineW = visor.width() * 0.11f
                    arcPaint.color = Color.argb(150, 148, 163, 184)
                    arcPaint.strokeWidth = visor.height() * 0.11f
                    canvas.drawLine(vCx - eyeSpacing - lineW, vCy, vCx - eyeSpacing + lineW, vCy, arcPaint)
                    canvas.drawLine(vCx + eyeSpacing - lineW, vCy, vCx + eyeSpacing + lineW, vCy, arcPaint)
                    arcPaint.color = Color.argb(215, 248, 113, 113)
                    canvas.drawLine(
                        visor.left + visor.width() * 0.24f, visor.bottom - visor.height() * 0.18f,
                        visor.right - visor.width() * 0.24f, visor.top + visor.height() * 0.18f,
                        arcPaint
                    )
                }

                else -> {
                    // Twin glowing cyan rounded companion eyes (with subtle vertical bounce on wake/listen)
                    val lookShiftX = if (state == MiniOrbVisualState.UNDERSTANDING) {
                        (sin(phaseRad * 2.0) * visor.width() * 0.05f).toFloat()
                    } else 0f
                    val pulseScale = when (state) {
                        MiniOrbVisualState.LISTENING -> 1f + rms * 0.35f
                        MiniOrbVisualState.WAKE_DETECTED -> 1.18f
                        else -> 1f
                    }
                    val eyeRx = visor.width() * 0.115f
                    val eyeRy = (visor.height() * 0.20f * blinkScale * pulseScale).coerceAtMost(visor.height() * 0.34f)

                    listOf(vCx - eyeSpacing + lookShiftX, vCx + eyeSpacing + lookShiftX).forEach { ex ->
                        // Soft cyan bloom halo around eye
                        eyePaint.shader = RadialGradient(
                            ex, vCy, eyeRx * 2.1f,
                            intArrayOf(
                                Color.argb(165, Color.red(eyeColor), Color.green(eyeColor), Color.blue(eyeColor)),
                                Color.TRANSPARENT
                            ),
                            floatArrayOf(0.25f, 1.0f),
                            Shader.TileMode.CLAMP
                        )
                        canvas.drawCircle(ex, vCy, eyeRx * 2.1f, eyePaint)

                        // Crisp glowing rounded cyan eye
                        eyePaint.shader = null
                        eyePaint.color = eyeColor
                        canvas.drawRoundRect(
                            ex - eyeRx, vCy - eyeRy,
                            ex + eyeRx, vCy + eyeRy,
                            eyeRx * 0.65f, eyeRx * 0.65f,
                            eyePaint
                        )

                        // Bright luminous center core
                        eyePaint.color = Color.argb(220, 224, 253, 255)
                        canvas.drawRoundRect(
                            ex - eyeRx * 0.55f, vCy - eyeRy * 0.52f,
                            ex + eyeRx * 0.55f, vCy + eyeRy * 0.52f,
                            eyeRx * 0.4f, eyeRx * 0.4f,
                            eyePaint
                        )
                    }
                }
            }
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
