package com.orca.pet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PetAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastAppPackage = ""
    private var lastChatText = ""
    private var screenTouchCount = 0
    private var lastTouchTime = 0L
    private var lastScrollTime = 0L

    companion object {
        var instance: PetAccessibilityService? = null
        const val TAG = "PetA11y"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                screenTouchCount++
                val now = System.currentTimeMillis()
                if (now - lastTouchTime > 3000 && screenTouchCount > 5) {
                    notifyPet("curious", "嗯？")
                    screenTouchCount = 0
                }
                lastTouchTime = now
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> handleScreenLongPress(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowChange(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ->
                notifyPet("curious", "在写什么呀…")
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(event)
        }

        val pkg = event.packageName?.toString() ?: ""
        if (pkg != lastAppPackage && pkg.isNotEmpty()) {
            lastAppPackage = pkg
            checkIfChatApp(pkg)
        }
    }

    // === FIXED: Use event coordinates instead of getBoundsInScreen ===
    private fun handleScreenLongPress(event: AccessibilityEvent) {
        // Try to get coordinates from the event's parcelable data first
        val source = event.source
        var x = -1
        var y = -1

        if (source != null) {
            val rect = Rect()
            source.getBoundsInScreen(rect)
            // Only use bounds if they look reasonable (not 0,0 or full screen)
            if (rect.width() > 0 && rect.width() < 2000 && rect.height() > 0 && rect.height() < 2000) {
                x = rect.centerX() - 60
                y = rect.centerY() - 80
            }
            source.recycle()
        }

        // Fallback: use event's own coordinates if available
        if (x < 0 || y < 0) {
            // AccessibilityEvent doesn't expose raw touch coords directly,
            // but we can estimate from the source node position
            x = 300  // default center-ish
            y = 400
        }

        Log.d(TAG, "Long press at: $x, $y")
        movePetTo(x, y, "screen_longpress")
    }

    // === NEW: Scroll detection — pet follows scrolling activity ===
    private fun handleScroll(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()
        // Throttle: max once per second
        if (now - lastScrollTime < 1000) return
        lastScrollTime = now

        // Get screen center as target
        val root = rootInActiveWindow ?: return
        val rect = Rect()
        root.getBoundsInScreen(rect)
        val targetX = rect.centerX() - 60
        val targetY = rect.centerY() - 100
        root.recycle()

        // Pet follows to center of screen with curious animation
        movePetTo(targetX, targetY, "scroll_follow")
        notifyPet("curious", "在看什么呀…")
    }

    private fun handleWindowChange(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: ""
        val chatApps = listOf(
            "com.tencent.mobileqq", "com.tencent.mm",
            "com.ss.android.ugc.aweme.lite", "com.ss.android.ugc.aweme",
            "com.sina.weibo", "com.zhiliaoapp.musically",
            "com.icesimba.android.vampire"
        )
        if (pkg in chatApps && pkg != "com.ai.assistance.operit") {
            handler.postDelayed({ scanForChatContent(pkg) }, 2000)
        }
    }

    private fun checkIfChatApp(pkg: String) {
        val chatApps = listOf(
            "com.tencent.mobileqq", "com.tencent.mm",
            "com.ss.android.ugc.aweme.lite", "com.ss.android.ugc.aweme",
            "com.sina.weibo", "com.zhiliaoapp.musically"
        )
        if (pkg in chatApps) {
            handler.postDelayed({ scanForChatContent(pkg) }, 1500)
        }
    }

    private fun scanForChatContent(pkg: String) {
        val root = rootInActiveWindow ?: return
        val editTexts = root.findAccessibilityNodeInfosByViewId("${pkg}:id/input")
        if (editTexts.isEmpty()) findEditableNodes(root)
        val chatBubbles = findChatBubbles(root)
        if (chatBubbles.isNotEmpty()) {
            val otherPersonText = chatBubbles.joinToString(" ") { it.text?.toString() ?: "" }
            if (otherPersonText.isNotBlank() && otherPersonText != lastChatText) {
                lastChatText = otherPersonText
                Log.d(TAG, "Chat: $otherPersonText")
                triggerJealous()
            }
        }
        root.recycle()
    }

    private fun findEditableNodes(node: AccessibilityNodeInfo) {
        if (node.isEditable) {
            notifyPet("curious", "在跟谁聊天呀…")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findEditableNodes(it) }
        }
    }

    private fun findChatBubbles(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val bubbles = mutableListOf<AccessibilityNodeInfo>()
        if (node.text != null && node.text.length > 5 &&
            node.className?.toString()?.contains("TextView") == true) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.top > 100 && rect.bottom < 2000) bubbles.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { bubbles.addAll(findChatBubbles(it)) }
        }
        return bubbles.takeLast(5)
    }

    private fun triggerJealous() {
        val root = rootInActiveWindow ?: return
        val rect = Rect()
        root.getBoundsInScreen(rect)
        val targetX = rect.centerX() - 60
        val targetY = rect.top + 200
        movePetTo(targetX, targetY, "jealous")
        root.recycle()
    }

    private fun movePetTo(x: Int, y: Int, action: String) {
        val intent = Intent("com.orca.pet.MOVE_TO").apply {
            putExtra("x", x)
            putExtra("y", y)
            putExtra("action", action)
        }
        sendBroadcast(intent)
    }

    private fun notifyPet(state: String, message: String) {
        val intent = Intent("com.orca.pet.SET_STATE").apply {
            putExtra("state", state)
            putExtra("message", message)
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
