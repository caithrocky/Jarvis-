package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.security.SecureCredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class ScreenActionResult(
    val success: Boolean,
    val message: String
)

data class ScreenFlowGuidance(
    val stepKind: String,
    val summary: String,
    val spokenGuidance: String
)

class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { pkg ->
            if (pkg != packageName) {
                _activePackageName.value = pkg
            }
        }
    }

    override fun onInterrupt() {
        // Service paused by system
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        super.onDestroy()
    }

    private fun activeAppRootWindow(): AccessibilityNodeInfo? {
        val currentRoot = rootInActiveWindow
        if (currentRoot != null && currentRoot.packageName?.toString() != packageName) {
            return currentRoot
        }
        val appWindowRoot = runCatching {
            windows
                ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() != packageName }
                ?.maxByOrNull { it.layer }
                ?.root
        }.getOrNull()
        return appWindowRoot ?: currentRoot
    }

    fun hasFormFieldsOnScreen(): Boolean {
        val root = activeAppRootWindow() ?: return false
        if (root.packageName?.toString() == packageName) return false
        var found = false
        traverseNodes(root) { node ->
            if (!found && node.isEditable && node.isVisibleToUser && !isBrowserAddressBar(node)) {
                found = true
            }
        }
        return found
    }

    fun readScreenSummary(): String {
        val root = activeAppRootWindow() ?: return "No active screen window is currently readable, Sir."
        val pkg = root.packageName?.toString().orEmpty()
        val appLabel = pkg.takeIf { it.isNotBlank() }?.let {
            runCatching {
                val info = packageManager.getApplicationInfo(it, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(it)
        } ?: "Current screen"

        val visibleTexts = mutableListOf<String>()
        val clickableLabels = mutableListOf<String>()
        val editableHints = mutableListOf<String>()

        traverseNodes(root) { node ->
            val rawText = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty()
            } else ""

            if (node.isEditable) {
                val fieldLabel = hint.ifBlank { desc }.ifBlank {
                    node.viewIdResourceName?.substringAfterLast("/")?.replace('_', ' ').orEmpty()
                }.ifBlank { "text input" }
                editableHints.add(fieldLabel)
            } else {
                val label = if (node.isPassword) "" else rawText.ifBlank { desc }
                if (label.isNotBlank()) {
                    if (node.isClickable && clickableLabels.size < 12 && label.length <= 50) {
                        clickableLabels.add(label)
                    } else if (visibleTexts.size < 16 && label.length in 2..110) {
                        visibleTexts.add(label)
                    }
                }
            }
        }

        val sb = StringBuilder("Active screen: $appLabel.")
        if (editableHints.isNotEmpty()) {
            sb.append(" Input fields: ${editableHints.distinct().take(6).joinToString(", ")}.")
        }
        if (clickableLabels.isNotEmpty()) {
            sb.append(" Buttons/links: ${clickableLabels.distinct().take(9).joinToString(", ")}.")
        }
        if (visibleTexts.isNotEmpty()) {
            sb.append(" Visible text: ${visibleTexts.distinct().take(12).joinToString(" | ")}.")
        }
        return sb.toString()
    }

    fun analyzeCurrentFlowStep(): ScreenFlowGuidance {
        val summary = readScreenSummary()
        val lower = summary.lowercase(Locale.US)

        return when {
            lower.contains("passport") || lower.contains("national id") || lower.contains("driver") ||
                lower.contains("front of id") || lower.contains("back of id") || lower.contains("selfie") ||
                lower.contains("verify identity") || lower.contains("kyc") ||
                (lower.contains("upload") && (lower.contains("id") || lower.contains("document") || lower.contains("photo"))) ||
                lower.contains("take photo") || lower.contains("take a photo") -> {
                ScreenFlowGuidance(
                    stepKind = "ID_UPLOAD",
                    summary = summary,
                    spokenGuidance = "Step guidance, Sir: This screen is asking for identity verification or a document photo upload. Place your ID on a flat, well-lit surface with zero glare and keep all four corners inside the frame—or position your face in bright light for a selfie. Tap the Upload or Take Photo button (or tell me \"Open camera\" or \"Tap upload\"), review the preview clearly, and tell me when you are ready for the next step."
                )
            }
            lower.contains("verification code") || lower.contains("otp") || lower.contains("2fa") ||
                lower.contains("authenticator") || lower.contains("enter code") || lower.contains("check your email") -> {
                ScreenFlowGuidance(
                    stepKind = "OTP_VERIFICATION",
                    summary = summary,
                    spokenGuidance = "Step guidance, Sir: This page is asking for a verification code or two-factor confirmation. Check your email or SMS inbox for the latest code, or tell me the code and I can type it into the verification box for you."
                )
            }
            lower.contains("captcha") || lower.contains("not a robot") || lower.contains("human verification") -> {
                ScreenFlowGuidance(
                    stepKind = "CAPTCHA",
                    summary = summary,
                    spokenGuidance = "Step guidance, Sir: There is a human verification or CAPTCHA challenge on screen. Please complete that check manually, then tap Continue or tell me to proceed."
                )
            }
            lower.contains("input fields:") && (
                lower.contains("password") || lower.contains("email") || lower.contains("sign in") ||
                    lower.contains("log in") || lower.contains("sign up") || lower.contains("register") ||
                    lower.contains("create account")
                ) -> {
                val isSignUp = lower.contains("sign up") || lower.contains("register") || lower.contains("create account")
                val modeWord = if (isSignUp) "sign-up" else "sign-in"
                ScreenFlowGuidance(
                    stepKind = if (isSignUp) "SIGNUP_FORM" else "SIGNIN_FORM",
                    summary = summary,
                    spokenGuidance = "Step guidance, Sir: I see a $modeWord form on this page. Tap Fill Login on my floating orb or say \"Fill my credentials\" and I will populate the matching fields from your encrypted vault, then pause so you can verify everything before submitting."
                )
            }
            else -> {
                ScreenFlowGuidance(
                    stepKind = "GENERAL_SCREEN",
                    summary = summary,
                    spokenGuidance = summary
                )
            }
        }
    }

    fun typeIntoActiveOrMatchingField(text: String, fieldHint: String? = null): ScreenActionResult {
        val root = activeAppRootWindow()
            ?: return ScreenActionResult(false, "I cannot read the active screen right now, Sir.")

        val editables = mutableListOf<AccessibilityNodeInfo>()
        traverseNodes(root) { node ->
            if (node.isEditable && node.isVisibleToUser) {
                editables.add(node)
            }
        }

        val hintLower = fieldHint?.trim()?.lowercase(Locale.US).orEmpty()
        val targetNode = when {
            hintLower.isNotBlank() -> {
                editables.firstOrNull { nodeMatchesHint(it, hintLower) }
                    ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                    ?: editables.firstOrNull()
            }
            else -> {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
                    ?: editables.firstOrNull { it.isFocused }
                    ?: editables.firstOrNull()
            }
        } ?: return ScreenActionResult(
            false,
            "No editable text box is currently open on screen, Sir. Tap a text field first or open a search bar."
        )

        val setOk = writeTextIntoNode(targetNode, text)
        return if (setOk) {
            ScreenActionResult(true, "Entered the requested text into the active field, Sir.")
        } else {
            ScreenActionResult(
                false,
                "I copied the text to your clipboard, Sir, as this input box restricts direct filling."
            )
        }
    }

    fun fillFormFromVault(
        profile: SecureCredentialStore.SignupProfile,
        savedCredential: SecureCredentialStore.SavedCredentialEntry?,
        generatedPassword: String?,
        isSignInHint: Boolean
    ): ScreenActionResult {
        val root = activeAppRootWindow()
            ?: return ScreenActionResult(false, "Open the website or app login/signup page first, Sir.")
        if (root.packageName?.toString() == packageName) {
            return ScreenActionResult(false, "Open the website or app login page first, Sir, so I don't fill JARVIS's own screen.")
        }

        val editables = mutableListOf<AccessibilityNodeInfo>()
        traverseNodes(root) { node ->
            if (node.isEditable && node.isVisibleToUser && !isBrowserAddressBar(node)) {
                editables.add(node)
            }
        }
        if (editables.isEmpty()) {
            return ScreenActionResult(
                false,
                "I don't see any login or signup input boxes on this screen yet, Sir. Open a sign-in page (not just a new tab) or tap into the form and ask me again."
            )
        }

        val effectiveEmail = savedCredential?.usernameOrEmail?.takeIf { it.contains("@") }
            ?: profile.email.ifBlank { savedCredential?.usernameOrEmail.orEmpty() }
        val effectiveUser = savedCredential?.usernameOrEmail?.takeIf { !it.contains("@") }
            ?: profile.username.ifBlank { savedCredential?.usernameOrEmail.orEmpty() }
        val effectivePassword = savedCredential?.password?.takeIf { it.isNotEmpty() }
            ?: profile.defaultPassword.takeIf { it.isNotEmpty() }
            ?: if (!isSignInHint) generatedPassword else null

        val nameParts = profile.fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val firstName = nameParts.firstOrNull().orEmpty()
        val lastName = if (nameParts.size > 1) nameParts.drop(1).joinToString(" ") else ""

        val filled = mutableListOf<String>()
        for (node in editables) {
            val sig = nodeSignature(node)
            val adjSig = if (sig.isBlank()) adjacentLabelSignature(node) else "$sig ${adjacentLabelSignature(node)}"
            val inputType = node.inputType
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            val isPasswordByType = node.isPassword ||
                (inputClass == InputType.TYPE_CLASS_TEXT && (
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    ))
            val isEmailByType = inputClass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                )
            val isPhoneByType = inputClass == InputType.TYPE_CLASS_PHONE

            val pair: Pair<String, String>? = when {
                (isEmailByType || adjSig.contains("email") || adjSig.contains("e-mail")) && effectiveEmail.isNotBlank() ->
                    "Email" to effectiveEmail
                isPasswordByType || adjSig.contains("password") || adjSig.contains("passcode") ->
                    if (!effectivePassword.isNullOrBlank()) "Password" to effectivePassword else null
                (adjSig.contains("user") || adjSig.contains("handle") || adjSig.contains("nick") || adjSig.contains("login")) && effectiveUser.isNotBlank() ->
                    "Username/Login" to effectiveUser
                (adjSig.contains("first") && adjSig.contains("name")) && firstName.isNotBlank() ->
                    "First Name" to firstName
                ((adjSig.contains("last") && adjSig.contains("name")) || adjSig.contains("surname")) && lastName.isNotBlank() ->
                    "Last Name" to lastName
                (adjSig.contains("name") && !adjSig.contains("user")) && profile.fullName.isNotBlank() ->
                    "Full Name" to profile.fullName
                (isPhoneByType || adjSig.contains("phone") || adjSig.contains("mobile") || adjSig.contains("tel")) && profile.phoneNumber.isNotBlank() ->
                    "Phone Number" to profile.phoneNumber
                (adjSig.contains("birth") || adjSig.contains("dob")) && profile.dateOfBirth.isNotBlank() ->
                    "Date of Birth" to profile.dateOfBirth
                adjSig.contains("country") && profile.country.isNotBlank() ->
                    "Country" to profile.country
                (adjSig.contains("address") || adjSig.contains("street")) && profile.addressLine.isNotBlank() ->
                    "Address" to profile.addressLine
                adjSig.contains("city") && profile.city.isNotBlank() ->
                    "City" to profile.city
                (adjSig.contains("postal") || adjSig.contains("zip")) && profile.postalCode.isNotBlank() ->
                    "Postal Code" to profile.postalCode
                editables.size <= 2 && !isPasswordByType && (effectiveEmail.isNotBlank() || effectiveUser.isNotBlank()) ->
                    "Login" to (savedCredential?.usernameOrEmail?.ifBlank { effectiveEmail } ?: effectiveEmail.ifBlank { effectiveUser })
                else -> null
            }

            if (pair != null && writeTextIntoNode(node, pair.second)) {
                filled.add(pair.first)
            }
        }

        val flowCheck = analyzeCurrentFlowStep()
        val extraStepTip = if (flowCheck.stepKind == "ID_UPLOAD" || flowCheck.stepKind == "CAPTCHA") {
            " Note: ${flowCheck.spokenGuidance}"
        } else {
            ""
        }

        return if (filled.isNotEmpty()) {
            val credSource = savedCredential?.let { " for ${it.serviceName}" }.orEmpty()
            ScreenActionResult(
                true,
                "With your permission, I filled ${filled.distinct().joinToString(", ")}$credSource on this page, Sir. Please review, complete any CAPTCHA or verification, and tap Submit when ready.$extraStepTip"
            )
        } else {
            ScreenActionResult(
                false,
                "I found ${editables.size} field(s), but none matched your saved details yet, Sir. Add your info under the Credentials tab or tell me specifically what to enter."
            )
        }
    }

    private fun writeTextIntoNode(targetNode: AccessibilityNodeInfo, text: String): Boolean {
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return true
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("JARVIS Input", text))
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    fun clickNodeByText(targetText: String): ScreenActionResult {
        val root = activeAppRootWindow()
            ?: return ScreenActionResult(false, "I cannot read the active screen right now, Sir.")
        val clean = targetText.trim()
        if (clean.isBlank()) return ScreenActionResult(false, "Tell me which button or item to tap, Sir.")

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        candidates.addAll(root.findAccessibilityNodeInfosByText(clean).orEmpty())
        val lower = clean.lowercase(Locale.US)
        traverseNodes(root) { node ->
            val t = node.text?.toString()?.lowercase(Locale.US).orEmpty()
            val d = node.contentDescription?.toString()?.lowercase(Locale.US).orEmpty()
            if ((t.contains(lower) || d.contains(lower)) && node.isVisibleToUser) {
                candidates.add(node)
            }
        }

        for (candidate in candidates) {
            var current: AccessibilityNodeInfo? = candidate
            var depth = 0
            while (current != null && depth < 5) {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return ScreenActionResult(true, "Tapped \"$clean\", Sir.")
                }
                current = current.parent
                depth++
            }
        }
        return ScreenActionResult(false, "I could not find a clickable \"$clean\" control on this screen, Sir.")
    }

    fun scrollScreen(forward: Boolean): ScreenActionResult {
        val root = activeAppRootWindow()
            ?: return ScreenActionResult(false, "No screen is active to scroll, Sir.")

        val scrollables = mutableListOf<AccessibilityNodeInfo>()
        traverseNodes(root) { node ->
            if (node.isScrollable && node.isVisibleToUser) {
                scrollables.add(node)
            }
        }
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        for (node in scrollables) {
            if (node.performAction(action)) {
                return ScreenActionResult(true, "Scrolled ${if (forward) "down" else "up"}, Sir.")
            }
        }

        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val startY = if (forward) dm.heightPixels * 0.72f else dm.heightPixels * 0.30f
        val endY = if (forward) dm.heightPixels * 0.28f else dm.heightPixels * 0.74f
        val path = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 280L)
        val dispatched = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
        return if (dispatched) {
            ScreenActionResult(true, "Swiped ${if (forward) "down" else "up"}, Sir.")
        } else {
            ScreenActionResult(false, "Unable to scroll this screen, Sir.")
        }
    }

    fun performGlobalNavigation(actionName: String): ScreenActionResult {
        val (globalCode, label) = when (actionName.lowercase(Locale.US)) {
            "back" -> GLOBAL_ACTION_BACK to "Navigating back"
            "home" -> GLOBAL_ACTION_HOME to "Returning to Home screen"
            "recents", "recent_apps" -> GLOBAL_ACTION_RECENTS to "Opening Recent Apps"
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS to "Opening Notifications shade"
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS to "Opening Quick Settings"
            "power_menu" -> GLOBAL_ACTION_POWER_DIALOG to "Opening Power Menu"
            "lock", "lock_screen" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    GLOBAL_ACTION_LOCK_SCREEN to "Locking screen"
                } else {
                    return ScreenActionResult(false, "Screen lock requires Android 9 or higher, Sir.")
                }
            }
            "screenshot" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    GLOBAL_ACTION_TAKE_SCREENSHOT to "Taking a screenshot"
                } else {
                    return ScreenActionResult(false, "Screenshot command requires Android 9 or higher, Sir.")
                }
            }
            else -> return ScreenActionResult(false, "Unknown navigation command: $actionName")
        }

        val ok = performGlobalAction(globalCode)
        return if (ok) {
            ScreenActionResult(true, "$label, Sir.")
        } else {
            ScreenActionResult(false, "Android blocked the $actionName action on this screen, Sir.")
        }
    }

    private fun traverseNodes(node: AccessibilityNodeInfo?, visitor: (AccessibilityNodeInfo) -> Unit) {
        if (node == null) return
        visitor(node)
        for (i in 0 until node.childCount) {
            traverseNodes(node.getChild(i), visitor)
        }
    }

    private fun isBrowserAddressBar(node: AccessibilityNodeInfo): Boolean {
        val resId = node.viewIdResourceName?.lowercase(Locale.US).orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.lowercase(Locale.US).orEmpty()
        } else ""
        return resId.contains("url_bar") ||
            resId.contains("omnibox") ||
            resId.contains("location_bar") ||
            resId.contains("search_box_text") ||
            resId.contains("toolbar_search") ||
            hint.contains("search or type web address") ||
            hint.contains("search or type url")
    }

    private fun adjacentLabelSignature(node: AccessibilityNodeInfo): String {
        val parent = node.parent ?: return ""
        val sb = StringBuilder()
        for (i in 0 until minOf(parent.childCount, 8)) {
            val child = parent.getChild(i) ?: continue
            if (!child.isPassword && child != node) {
                child.text?.toString()?.takeIf { it.isNotBlank() && it.length <= 40 }?.let { sb.append(it).append(' ') }
                child.contentDescription?.toString()?.takeIf { it.isNotBlank() && it.length <= 40 }?.let { sb.append(it).append(' ') }
            }
        }
        return sb.toString().lowercase(Locale.US)
    }

    private fun nodeMatchesHint(node: AccessibilityNodeInfo, hintLower: String): Boolean {
        return nodeSignature(node).contains(hintLower)
    }

    private fun nodeSignature(node: AccessibilityNodeInfo): String {
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString().orEmpty()
        } else ""
        val text = if (node.isPassword) "" else node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val resId = node.viewIdResourceName?.substringAfterLast("/").orEmpty()
        return "$hint $text $desc $resId".lowercase(Locale.US)
    }

    companion object {
        @Volatile
        private var instance: JarvisAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _activePackageName = MutableStateFlow<String?>(null)
        val activePackageName: StateFlow<String?> = _activePackageName.asStateFlow()

        fun activeInstance(): JarvisAccessibilityService? = instance
    }
}
