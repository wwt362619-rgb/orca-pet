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

    // Long press detection on screen (not on pet)
    private var screenPressStartTime = 0L
    private var screenPressX = 0f
    private var screenPressY = 0f
    private var isScreenPressing = false
    private var longPressTriggered = false

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
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleScreenTap(event)
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                handleScreenLongPress(event)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleScreenInteraction(event)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handleScreenInteraction(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowChange(event)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                notifyPet("curious", "在写什么呀…")
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                // Raw touch start - track for long press
                screenPressStartTime = System.currentTimeMillis()
                isScreenPressing = true
                longPressTriggered = false
                // Check after 800ms if still pressing
                handler.postDelayed({
                    if (isScreenPressing && !longPressTriggered) {
                        longPressTriggered = true
                        // Get last known touch position from event
                        triggerScreenLongPress()
                    }
                }, 800)
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                isScreenPressing = false
            }
        }

        // Track package changes
        val pkg = event.packageName?.toString() ?: ""
        if (pkg != lastAppPackage && pkg.isNotEmpty()) {
            lastAppPackage = pkg
            checkIfChatApp(pkg)
        }
    }

    private fun handleScreenTap(event: AccessibilityEvent) {
        screenTouchCount++
        val now = System.currentTimeMillis()

        // If user is tapping around a lot (not on pet), pet notices
        if (now - lastScreenTouchTime > 3000 && screenTouchCount > 5) {
            notifyPet("curious", "嗯？")
            screenTouchCount = 0
        }
        lastScreenTouchTime = now
    }

    private fun handleScreenLongPress(event: AccessibilityEvent) {
        Log.d(TAG, "Screen long press detected via event")
        triggerScreenLongPress()
    }

    private fun triggerScreenLongPress() {
        // Get current pet position from overlay and make it trot to the press location
        val root = rootInActiveWindow
        if (root != null) {
            val rect = Rect()
            root.getBoundsInScreen(rect)
            val targetX = rect.centerX() - 60
            val targetY = rect.centerY() - 80
            root.recycle()

            val intent = Intent("com.orca.pet.MOVE_TO").apply {
                putExtra("x", targetX)
                putExtra("y", targetY)
                putExtra("action", "screen_longpress")
            }
            sendBroadcast(intent)
        } else {
            // Fallback: just tell pet to trot
            notifyPet("happy", "来啦来啦~♡")
        }
    }

    private fun handleScreenInteraction(event: AccessibilityEvent) {
        screenTouchCount++
        val now = System.currentTimeMillis()
        if (now - lastScreenTouchTime > 3000 && screenTouchCount > 5) {
            notifyPet("curious", "嗯？")
            screenTouchCount = 0
        }
        lastScreenTouchTime = now
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
            Log.d(TAG, "Chat app detected: $pkg")
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
        if (editTexts.isEmpty()) {
            findEditableNodes(root)
        }
        val chatBubbles = findChatBubbles(root)
        if (chatBubbles.isNotEmpty()) {
            val otherPersonText = chatBubbles.joinToString(" ") { it.text?.toString() ?: "" }
            if (otherPersonText.isNotBlank() && otherPersonText != lastChatText) {
                lastChatText = otherPersonText
                Log.d(TAG, "Chat detected: $otherPersonText")
                triggerJealous()
            }
        }
        root.recycle()
    }

    private fun findEditableNodes(node: AccessibilityNodeInfo) {
        if (node.isEditable) {
            Log.d(TAG, "Found editable: ${node.text}")
            notifyPet("curious", "在跟谁聊天呀…")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findEditableNodes(it) }
        }
    }

    private fun findChatBubbles(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val bubbles = mutableListOf<AccessibilityNodeInfo>()
        if (node.text != null && node.text.length > 5 && node.className?.toString()?.contains("TextView") == true) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
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
        val root = rootInActiveWindow ?: return
        val rect = Rect()
        root.getBoundsInScreen(rect)
        val targetX = rect.centerX() - 60
        val targetY = rect.top + 200
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
