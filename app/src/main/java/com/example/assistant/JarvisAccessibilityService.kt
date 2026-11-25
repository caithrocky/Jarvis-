package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
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
    val message: String,
    val targetRect: Rect? = null
)

data class ScreenFlowGuidance(
    val stepKind: String,
    val summary: String,
    val spokenGuidance: String
)

data class IndexedUiNode(
    val index: Int,
    val label: String,
    val role: String,
    val resourceId: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean
)

data class ContactCandidateMatch(
    val index: Int,
    val displayName: String,
    val subText: String,
    val bounds: Rect
)

class JarvisAccessibilityService : AccessibilityService() {

    private var overlayManager: JarvisVisualOverlayManager? = null

    @Volatile
    private var lastIndexedNodes: List<IndexedUiNode> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlayManager = JarvisVisualOverlayManager(this).apply {
            showTapEnabled = desiredShowTap
            showHighlightEnabled = desiredShowHighlight
            showScrollEnabled = desiredShowScroll
        }
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
        overlayManager?.clearOverlays()
    }

    override fun onDestroy() {
        overlayManager?.detach()
        overlayManager = null
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        super.onDestroy()
    }

    fun applyVisualFeedbackSettings(showTap: Boolean, showHighlight: Boolean, showScroll: Boolean) {
        desiredShowTap = showTap
        desiredShowHighlight = showHighlight
        desiredShowScroll = showScroll
        overlayManager?.apply {
            showTapEnabled = showTap
            showHighlightEnabled = showHighlight
            showScrollEnabled = showScroll
            if (!showTap && !showHighlight && !showScroll) {
                clearOverlays()
            }
        }
    }

    fun showHighlightsOnScreen(targets: List<HighlightTarget>, durationMs: Long = 4000L) {
        overlayManager?.showHighlightBoxes(targets, durationMs)
    }

    fun clearVisualOverlays() {
        overlayManager?.clearOverlays()
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

    fun activeForegroundPackage(): String? =
        activeAppRootWindow()?.packageName?.toString() ?: _activePackageName.value

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
                    if (node.isClickable && clickableLabels.size < 14 && label.length <= 50) {
                        clickableLabels.add(label)
                    } else if (visibleTexts.size < 18 && label.length in 2..110) {
                        visibleTexts.add(label)
                    }
                }
            }
        }

        val sb = StringBuilder("Active screen: $appLabel ($pkg).")
        if (editableHints.isNotEmpty()) {
            sb.append(" Input fields: ${editableHints.distinct().take(6).joinToString(", ")}.")
        }
        if (clickableLabels.isNotEmpty()) {
            sb.append(" Buttons/links: ${clickableLabels.distinct().take(12).joinToString(", ")}.")
        }
        if (visibleTexts.isNotEmpty()) {
            sb.append(" Visible text: ${visibleTexts.distinct().take(12).joinToString(" | ")}.")
        }
        return sb.toString()
    }

    /**
     * Reads a numbered UI element map with exact coordinates for autonomous reasoning and target highlighting.
     */
    fun readRichScreenMap(maxNodes: Int = 36): Pair<String, List<IndexedUiNode>> {
        val root = activeAppRootWindow() ?: return "No active screen window." to emptyList()
        val pkg = root.packageName?.toString().orEmpty()
        val appLabel = pkg.takeIf { it.isNotBlank() }?.let {
            runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString()
            }.getOrDefault(it)
        } ?: "Current App"

        val nodes = mutableListOf<IndexedUiNode>()
        val seenKeys = mutableSetOf<String>()

        traverseNodes(root) { node ->
            if (nodes.size >= maxNodes || !node.isVisibleToUser) return@traverseNodes
            val interesting = node.isClickable || node.isEditable || node.isScrollable ||
                !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            if (!interesting) return@traverseNodes

            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() <= 6 || rect.height() <= 6) return@traverseNodes

            val rawText = if (node.isPassword) "" else node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty()
            } else ""
            val resId = node.viewIdResourceName?.substringAfterLast("/").orEmpty()
            val label = rawText.ifBlank { desc }.ifBlank { hint }.ifBlank { resId.replace('_', ' ') }.trim()
            if (label.isBlank() && !node.isEditable && !node.isClickable) return@traverseNodes

            val dedupKey = "${label.lowercase(Locale.US)}|${rect.centerX() / 24}|${rect.centerY() / 24}|${node.isEditable}"
            if (!seenKeys.add(dedupKey)) return@traverseNodes

            val role = when {
                node.isEditable -> "input"
                node.isClickable -> "button"
                node.isScrollable -> "list"
                else -> "text"
            }
            nodes.add(
                IndexedUiNode(
                    index = nodes.size + 1,
                    label = label.ifBlank { role },
                    role = role,
                    resourceId = resId,
                    bounds = rect,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable
                )
            )
        }

        lastIndexedNodes = nodes
        val formatted = buildString {
            appendLine("Active App: $appLabel ($pkg)")
            nodes.forEach { n ->
                val flags = buildList {
                    if (n.isEditable) add("editable")
                    if (n.isClickable) add("clickable")
                    if (n.isScrollable) add("scrollable")
                }.joinToString(",")
                appendLine("[#${n.index}] ${n.role}: \"${n.label.take(48)}\" ($flags) id=${n.resourceId}")
            }
        }
        return formatted to nodes
    }

    fun clickIndexedNode(index: Int): ScreenActionResult {
        val node = lastIndexedNodes.firstOrNull { it.index == index }
            ?: return ScreenActionResult(false, "Control #$index is no longer visible, Sir.")
        return tapBoundsCenter(node.bounds, node.label)
    }

    fun tapBoundsCenter(rect: Rect, label: String = ""): ScreenActionResult {
        val cx = rect.centerX().toFloat().coerceAtLeast(1f)
        val cy = rect.centerY().toFloat().coerceAtLeast(1f)

        overlayManager?.showHighlightBoxes(
            listOf(HighlightTarget(rect = rect, label = label, accentColor = Color.rgb(0, 229, 255))),
            durationMs = 1500L
        )
        overlayManager?.showTapRipple(cx, cy, label)

        // First try semantic node click if any node matches these bounds
        val root = activeAppRootWindow()
        if (root != null) {
            var clickedNode = false
            traverseNodes(root) { node ->
                if (!clickedNode && node.isClickable && node.isVisibleToUser) {
                    val nr = Rect()
                    node.getBoundsInScreen(nr)
                    if (absDiff(nr.centerX(), rect.centerX()) <= 24 && absDiff(nr.centerY(), rect.centerY()) <= 24) {
                        clickedNode = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
            }
            if (clickedNode) {
                return ScreenActionResult(true, "Tapped \"${label.ifBlank { "item" }}\", Sir.", rect)
            }
        }

        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return if (ok) {
            ScreenActionResult(true, "Tapped \"${label.ifBlank { "item" }}\", Sir.", rect)
        } else {
            ScreenActionResult(false, "Unable to tap \"${label.ifBlank { "item" }}\" at ($cx, $cy), Sir.")
        }
    }

    /**
     * Locates an in-app Search bar or Search icon button, opens it if needed, and enters the query.
     */
    fun findAndTapSearchAndType(queryText: String): ScreenActionResult {
        val root = activeAppRootWindow()
            ?: return ScreenActionResult(false, "I cannot see the active app window right now, Sir.")

        val editables = mutableListOf<AccessibilityNodeInfo>()
        val searchTriggers = mutableListOf<AccessibilityNodeInfo>()

        traverseNodes(root) { node ->
            if (!node.isVisibleToUser) return@traverseNodes
            val sig = nodeSignature(node)
            if (node.isEditable) {
                editables.add(node)
            } else if (
                node.isClickable &&
                (sig.contains("search") || sig.contains("find") || sig.contains("meta ai") ||
                    sig.contains("menu_search") || sig.contains("search_button") || sig.contains("chat_search"))
            ) {
                searchTriggers.add(node)
            }
        }

        // Prefer an editable box already labeled search
        val activeSearchEdit = editables.firstOrNull { nodeSignature(it).contains("search") }
        if (activeSearchEdit != null) {
            val rect = Rect().also { activeSearchEdit.getBoundsInScreen(it) }
            overlayManager?.showHighlightBoxes(listOf(HighlightTarget(rect, label = "Search")), 2000L)
            overlayManager?.showTapRipple(rect.centerX().toFloat(), rect.centerY().toFloat(), "Search")
            val ok = writeTextIntoNode(activeSearchEdit, queryText)
            return ScreenActionResult(ok, if (ok) "Searching for $queryText, Sir." else "Could not type into search box.", rect)
        }

        // Otherwise tap the app's search icon / bar trigger first
        val trigger = searchTriggers.firstOrNull()
        if (trigger != null) {
            val rect = Rect().also { trigger.getBoundsInScreen(it) }
            tapBoundsCenter(rect, "Search")
            return ScreenActionResult(false, "Opened search bar, waiting to type query.", rect)
        }

        // Last resort: check any visible node containing "Search"
        val fallbackSearch = editables.firstOrNull()
        if (fallbackSearch != null) {
            val rect = Rect().also { fallbackSearch.getBoundsInScreen(it) }
            val ok = writeTextIntoNode(fallbackSearch, queryText)
            return ScreenActionResult(ok, if (ok) "Entered $queryText into field, Sir." else "Failed typing $queryText.", rect)
        }

        return ScreenActionResult(false, "No search bar visible on this screen yet, Sir.")
    }

    /**
     * Scans active screen rows matching the requested contact/user name for cross-platform messaging.
     */
    fun findContactCandidates(contactQuery: String): List<ContactCandidateMatch> {
        val root = activeAppRootWindow() ?: return emptyList()
        val queryLower = contactQuery.trim().lowercase(Locale.US)
        val tokens = queryLower.split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()

        val matches = mutableListOf<ContactCandidateMatch>()
        val seenRows = mutableSetOf<Int>()
        val dm = resources.displayMetrics
        val topHeaderCutoff = (dm.heightPixels * 0.14f).toInt()

        traverseNodes(root) { node ->
            if (!node.isVisibleToUser || node.isEditable) return@traverseNodes
            val rawText = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val candidateLabel = rawText.ifBlank { desc }
            if (candidateLabel.isBlank() || candidateLabel.length > 70) return@traverseNodes

            val lower = candidateLabel.lowercase(Locale.US)
            if (lower == "search" || lower.contains("ask meta ai") || lower == queryLower && node.isFocused) return@traverseNodes

            val hits = tokens.all { lower.contains(it) } || lower.contains(queryLower)
            if (!hits) return@traverseNodes

            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.top < topHeaderCutoff && isInsideSearchArea(node)) return@traverseNodes
            if (rect.width() <= 10 || rect.height() <= 10) return@traverseNodes

            // Expand clickable row ancestor bounds if available for cleaner visual highlighting
            val rowBounds = findClickableAncestorBounds(node) ?: rect
            val rowBucket = rowBounds.centerY() / 48
            if (!seenRows.add(rowBucket)) return@traverseNodes

            matches.add(
                ContactCandidateMatch(
                    index = matches.size + 1,
                    displayName = candidateLabel,
                    subText = "",
                    bounds = rowBounds
                )
            )
        }
        return matches.take(6)
    }

    fun findMessageComposeAndType(messageText: String): ScreenActionResult {
        val root = activeAppRootWindow()
            ?: return ScreenActionResult(false, "No active chat window visible, Sir.")

        val editables = mutableListOf<AccessibilityNodeInfo>()
        traverseNodes(root) { node ->
            if (node.isEditable && node.isVisibleToUser && !isBrowserAddressBar(node)) {
                editables.add(node)
            }
        }

        val composeNode = editables.firstOrNull { node ->
            val sig = nodeSignature(node)
            sig.contains("message") || sig.contains("chat") || sig.contains("reply") ||
                sig.contains("write") || sig.contains("entry") || sig.contains("caption")
        } ?: editables.lastOrNull()
            ?: return ScreenActionResult(false, "No message box is open on this screen yet, Sir.")

        val rect = Rect().also { composeNode.getBoundsInScreen(it) }
        overlayManager?.showHighlightBoxes(listOf(HighlightTarget(rect, label = "Message")), 2500L)
        overlayManager?.showTapRipple(rect.centerX().toFloat(), rect.centerY().toFloat(), "Compose")
        val ok = writeTextIntoNode(composeNode, messageText)
        return if (ok) {
            ScreenActionResult(true, "Typed message into chat box, Sir.", rect)
        } else {
            ScreenActionResult(false, "Couldn't write directly into this chat box, Sir.", rect)
        }
    }

    fun findSendButtonBounds(): Rect? {
        val root = activeAppRootWindow() ?: return null
        var bestRect: Rect? = null
        traverseNodes(root) { node ->
            if (bestRect == null && node.isClickable && node.isVisibleToUser) {
                val sig = nodeSignature(node)
                if (sig.contains("send") && !sig.contains("send a chat")) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.width() > 8 && r.height() > 8) {
                        bestRect = r
                    }
                }
            }
        }
        return bestRect
    }

    fun highlightSendButton(): Rect? {
        val rect = findSendButtonBounds() ?: return null
        overlayManager?.showHighlightBoxes(
            listOf(HighlightTarget(rect, badge = "CONFIRM", label = "Send", accentColor = Color.rgb(16, 185, 129))),
            durationMs = 8000L
        )
        return rect
    }

    fun tapSendButton(): ScreenActionResult {
        val rect = findSendButtonBounds()
            ?: return clickNodeByText("Send")
        return tapBoundsCenter(rect, "Send")
    }

    /**
     * Universal social media navigation & engagement across TikTok, Instagram, X, Reddit, YouTube, Snapchat, Facebook, LinkedIn.
     */
    fun performSocialAction(action: String, targetArg: String = ""): ScreenActionResult {
        val lower = action.lowercase(Locale.US).trim()
        val root = activeAppRootWindow()
            ?: return ScreenActionResult(false, "Open a social app screen first, Sir.")

        return when (lower) {
            "like", "upvote", "love" -> {
                val keywords = listOf("like", "heart", "love", "upvote", "thumbs up")
                val nodeRect = findClickableByKeywords(root, keywords)
                if (nodeRect != null) {
                    tapBoundsCenter(nodeRect, "Like")
                } else {
                    // Double-tap center works universally on TikTok, Instagram Reels, YouTube Shorts, Snapchat Spotlight
                    val dm = resources.displayMetrics
                    val cx = dm.widthPixels * 0.50f
                    val cy = dm.heightPixels * 0.48f
                    overlayManager?.showTapRipple(cx, cy, "Double-Tap Like")
                    val p1 = Path().apply { moveTo(cx, cy) }
                    val p2 = Path().apply { moveTo(cx + 4f, cy - 2f) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(p1, 0L, 45L))
                        .addStroke(GestureDescription.StrokeDescription(p2, 110L, 45L))
                        .build()
                    val ok = dispatchGesture(gesture, null, null)
                    ScreenActionResult(ok, if (ok) "Liked the current post with a double-tap, Sir." else "Unable to like this post, Sir.")
                }
            }
            "repost", "retweet", "boost" -> {
                val rect = findClickableByKeywords(root, listOf("repost", "retweet", "echo", "remix", "reshare"))
                if (rect != null) {
                    tapBoundsCenter(rect, "Repost")
                } else {
                    ScreenActionResult(false, "I couldn't spot a Repost button on this screen, Sir. Ask me to read screen or share instead.")
                }
            }
            "share" -> {
                val rect = findClickableByKeywords(root, listOf("share", "send to", "forward"))
                if (rect != null) tapBoundsCenter(rect, "Share")
                else ScreenActionResult(false, "No Share button is visible right now, Sir.")
            }
            "save", "bookmark" -> {
                val rect = findClickableByKeywords(root, listOf("save", "bookmark", "favorite", "add to favorites"))
                if (rect != null) tapBoundsCenter(rect, "Save")
                else ScreenActionResult(false, "No Save or Bookmark button is visible right now, Sir.")
            }
            "follow", "subscribe" -> {
                val rect = findClickableByKeywords(root, listOf("follow", "subscribe"))
                if (rect != null) tapBoundsCenter(rect, "Follow")
                else ScreenActionResult(false, "No Follow or Subscribe button is visible right now, Sir.")
            }
            "comment", "comments" -> {
                val rect = findClickableByKeywords(root, listOf("comment", "comments", "reply", "add a comment"))
                if (rect != null) tapBoundsCenter(rect, "Comments")
                else ScreenActionResult(false, "I couldn't find the Comments button on this screen, Sir.")
            }
            "next", "scroll_down", "scroll_next" -> scrollScreen(forward = true)
            "previous", "scroll_up" -> scrollScreen(forward = false)
            "swipe_left" -> swipeHorizontal(toLeft = true)
            "swipe_right" -> swipeHorizontal(toLeft = false)
            "dms", "messages", "inbox" -> {
                val rect = findClickableByKeywords(root, listOf("messages", "direct", "chat", "chats", "inbox", "messenger"))
                if (rect != null) tapBoundsCenter(rect, "Messages")
                else ScreenActionResult(false, "I couldn't see a Direct Messages icon on this screen, Sir.")
            }
            "profile" -> {
                val rect = findClickableByKeywords(root, listOf("profile", "account", "me"))
                if (rect != null) tapBoundsCenter(rect, "Profile")
                else ScreenActionResult(false, "No Profile tab found on this screen, Sir.")
            }
            "home_feed", "feed" -> {
                val rect = findClickableByKeywords(root, listOf("home", "for you", "feed"))
                if (rect != null) tapBoundsCenter(rect, "Home")
                else ScreenActionResult(false, "No Home feed tab found on this screen, Sir.")
            }
            else -> {
                if (targetArg.isNotBlank()) clickNodeByText(targetArg) else clickNodeByText(action)
            }
        }
    }

    private fun findClickableByKeywords(root: AccessibilityNodeInfo, keywords: List<String>): Rect? {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        traverseNodes(root) { node ->
            if (!node.isVisibleToUser) return@traverseNodes
            val sig = nodeSignature(node)
            val matchedIndex = keywords.indexOfFirst { kw -> sig.contains(kw) }
            if (matchedIndex >= 0) {
                candidates.add(node to matchedIndex)
            }
        }
        val best = candidates.minByOrNull { it.second }?.first ?: return null
        return findClickableAncestorBounds(best) ?: Rect().also { best.getBoundsInScreen(it) }
    }

    private fun findClickableAncestorBounds(node: AccessibilityNodeInfo): Rect? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur.isClickable) {
                val r = Rect()
                cur.getBoundsInScreen(r)
                if (r.width() > 8 && r.height() > 8) return r
            }
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun isInsideSearchArea(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 4) {
            if (nodeSignature(cur).contains("search")) return true
            cur = cur.parent
            depth++
        }
        return false
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
                    spokenGuidance = "Step guidance, Sir: I see a $modeWord form on this page. Say \"Fill my credentials\" and I will populate the matching fields from your encrypted vault, then pause so you can verify everything before submitting."
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

        val rect = Rect().also { targetNode.getBoundsInScreen(it) }
        overlayManager?.showHighlightBoxes(listOf(HighlightTarget(rect, label = "Input")), 1800L)
        overlayManager?.showTapRipple(rect.centerX().toFloat(), rect.centerY().toFloat(), "Type")

        val setOk = writeTextIntoNode(targetNode, text)
        return if (setOk) {
            ScreenActionResult(true, "Entered the requested text into the active field, Sir.", rect)
        } else {
            ScreenActionResult(
                false,
                "I copied the text to your clipboard, Sir, as this input box restricts direct filling.",
                rect
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
        val highlightedTargets = mutableListOf<HighlightTarget>()

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
                val rect = Rect().also { node.getBoundsInScreen(it) }
                highlightedTargets.add(HighlightTarget(rect, label = pair.first, accentColor = Color.rgb(16, 185, 129)))
            }
        }

        if (highlightedTargets.isNotEmpty()) {
            overlayManager?.showHighlightBoxes(highlightedTargets, 2600L)
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
                if (current.isClickable) {
                    val rect = Rect().also { current.getBoundsInScreen(it) }
                    overlayManager?.showHighlightBoxes(listOf(HighlightTarget(rect, label = clean)), 1500L)
                    overlayManager?.showTapRipple(rect.centerX().toFloat(), rect.centerY().toFloat(), clean)
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return ScreenActionResult(true, "Tapped \"$clean\", Sir.", rect)
                    }
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

        overlayManager?.showScrollChevron(
            if (forward) ScrollOverlayDirection.DOWN else ScrollOverlayDirection.UP
        )

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
        val startY = if (forward) dm.heightPixels * 0.74f else dm.heightPixels * 0.28f
        val endY = if (forward) dm.heightPixels * 0.24f else dm.heightPixels * 0.76f
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

    fun swipeHorizontal(toLeft: Boolean): ScreenActionResult {
        overlayManager?.showScrollChevron(
            if (toLeft) ScrollOverlayDirection.LEFT else ScrollOverlayDirection.RIGHT
        )
        val dm = resources.displayMetrics
        val cy = dm.heightPixels * 0.50f
        val startX = if (toLeft) dm.widthPixels * 0.82f else dm.widthPixels * 0.18f
        val endX = if (toLeft) dm.widthPixels * 0.18f else dm.widthPixels * 0.82f
        val path = Path().apply {
            moveTo(startX, cy)
            lineTo(endX, cy)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 260L)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return ScreenActionResult(ok, if (ok) "Swiped ${if (toLeft) "left" else "right"}, Sir." else "Unable to swipe horizontally, Sir.")
    }

    fun performGlobalNavigation(actionName: String): ScreenActionResult {
        val (globalCode, label) = when (actionName.lowercase(Locale.US)) {
            "back" -> GLOBAL_ACTION_BACK to "Navigating back"
            "home" -> GLOBAL_ACTION_HOME to "Returning to Home screen"
            "recents", "recent_apps" -> GLOBAL_ACTION_RECENTS to "Opening Recent Apps"
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS to "Opening Notifications shade"
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS to "Opening Quick Settings"
            "power_menu" -> GLOBAL_ACTION_POWER_DIALOG to "Opening Power Menu"
            "all_apps", "app_drawer" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS to "Opening App Drawer"
                } else {
                    return ScreenActionResult(false, "App drawer global action requires Android 12 or higher, Sir.")
                }
            }
            "dismiss_notifications" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE to "Closing Notifications shade"
                } else {
                    GLOBAL_ACTION_BACK to "Closing shade"
                }
            }
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

    private fun absDiff(a: Int, b: Int): Int = if (a >= b) a - b else b - a

    companion object {
        @Volatile
        private var instance: JarvisAccessibilityService? = null

        @Volatile
        private var desiredShowTap: Boolean = true
        @Volatile
        private var desiredShowHighlight: Boolean = true
        @Volatile
        private var desiredShowScroll: Boolean = true

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _activePackageName = MutableStateFlow<String?>(null)
        val activePackageName: StateFlow<String?> = _activePackageName.asStateFlow()

        fun activeInstance(): JarvisAccessibilityService? = instance
    }
}
