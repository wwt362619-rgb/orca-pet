package com.orca.pet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PetAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastScreenTouchTime = 0L
    private var lastScreenTouchX = 0f
    private var lastScreenTouchY = 0f
    private var screenTouchCount = 0
    private var lastAppPackage = ""
    private var lastChatText = ""
    private var petX = 50
    private var petY = 300
    private var isPetNearChat = false

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
            flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handleScreenInteraction(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowChange(event)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // User is typing / selecting text - pet gets curious
                notifyPet("curious", "在写什么呀…")
            }
        }

        // Check for chat apps
        val pkg = event.packageName?.toString() ?: ""
        if (pkg != lastAppPackage && pkg.isNotEmpty()) {
            lastAppPackage = pkg
            checkIfChatApp(pkg)
        }
    }

    private fun handleScreenInteraction(event: AccessibilityEvent) {
        screenTouchCount++
        val now = System.currentTimeMillis()

        // If user is actively tapping around (not on pet), pet should notice
        if (now - lastScreenTouchTime > 3000 && screenTouchCount > 5) {
            // User is busy tapping elsewhere
            notifyPet("curious", "嗯？")
            screenTouchCount = 0
        }
        lastScreenTouchTime = now
    }

    private fun handleWindowChange(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        // Detect chat apps
        val chatApps = listOf(
            "com.tencent.mobileqq",
            "com.tencent.mm",
            "com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.aweme",
            "com.sina.weibo",
            "com.zhiliaoapp.musically",
            "com.icesimba.android.vampire"
        )

        if (pkg in chatApps && pkg != "com.ai.assistance.operit") {
            Log.d(TAG, "Chat app detected: $pkg")
            // Scan for chat input fields
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

        // Look for EditText fields (chat input)
        val editTexts = root.findAccessibilityNodeInfosByViewId("${pkg}:id/input")
        if (editTexts.isEmpty()) {
            // Try finding any editable nodes
            findEditableNodes(root)
        }

        // Look for chat message bubbles
        val chatBubbles = findChatBubbles(root)
        if (chatBubbles.isNotEmpty()) {
            val otherPersonText = chatBubbles.joinToString(" ") { it.text?.toString() ?: "" }
            if (otherPersonText.isNotBlank() && otherPersonText != lastChatText) {
                lastChatText = otherPersonText
                Log.d(TAG, "Chat detected: $otherPersonText")
                // Someone else is talking to user → jealous!
                triggerJealous()
            }
        }

        root.recycle()
    }

    private fun findEditableNodes(node: AccessibilityNodeInfo) {
        if (node.isEditable) {
            Log.d(TAG, "Found editable: ${node.text}")
            // User is typing a message → pet gets curious
            notifyPet("curious", "在跟谁聊天呀…")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findEditableNodes(it) }
        }
    }

    private fun findChatBubbles(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val bubbles = mutableListOf<AccessibilityNodeInfo>()
        // Look for text views that look like chat messages
        if (node.text != null && node.text.length > 5 && node.className?.toString()?.contains("TextView") == true) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            // Chat bubbles usually in middle of screen
            if (rect.top > 100 && rect.bottom < 2000) {
                bubbles.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { bubbles.addAll(findChatBubbles(it)) }
        }
        return bubbles.takeLast(5)
    }

    private fun triggerJealous() {
        // Move pet toward the chat area
        val root = rootInActiveWindow ?: return
        val rect = Rect()
        root.getBoundsInScreen(rect)
        val targetX = rect.centerX() - 60
        val targetY = rect.top + 200

        // Send move command via broadcast
        val intent = Intent("com.orca.pet.MOVE_TO").apply {
            putExtra("x", targetX)
            putExtra("y", targetY)
            putExtra("action", "jealous")
        }
        sendBroadcast(intent)
        root.recycle()
    }

    private fun notifyPet(state: String, message: String) {
        val intent = Intent("com.orca.pet.SET_STATE").apply {
            putExtra("state", state)
            putExtra("message", message)
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
