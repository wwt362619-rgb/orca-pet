package com.orca.pet

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null

    companion object {
        private const val CHANNEL_ID = "orca_pet_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 120
        private const val PET_HEIGHT_DP = 160
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("嗷~"))
        setupOverlay()
        setupBroadcastReceiver()
    }

    private fun setupBroadcastReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.orca.pet.MOVE_TO" -> {
                        val x = intent.getIntExtra("x", params?.x ?: 50)
                        val y = intent.getIntExtra("y", params?.y ?: 300)
                        val action = intent.getStringExtra("action") ?: ""
                        animatePetTo(x, y, action)
                    }
                    "com.orca.pet.SET_STATE" -> {
                        val state = intent.getStringExtra("state") ?: ""
                        val message = intent.getStringExtra("message") ?: ""
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.setState('$state')", null
                        )
                        if (message.isNotEmpty()) {
                            handler.postDelayed({
                                overlayView?.evaluateJavascript(
                                    "window.petEngine && window.petEngine.showMessage('$message', 'heart')", null
                                )
                            }, 400)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.orca.pet.MOVE_TO")
            addAction("com.orca.pet.SET_STATE")
        }
        registerReceiver(receiver, filter)
    }

    private fun animatePetTo(targetX: Int, targetY: Int, action: String) {
        val startX = params?.x ?: 50
        val startY = params?.y ?: 300
        val steps = 20
        val delayPerStep = 16L

        for (i in 0..steps) {
            val progress = i.toFloat() / steps
            val eased = progress * progress * (3 - 2 * progress)
            handler.postDelayed({
                params?.x = (startX + ((targetX - startX) * eased)).toInt()
                params?.y = (startY + ((targetY - startY) * eased)).toInt()
                windowManager?.updateViewLayout(overlayView, params)

                if (i == steps) {
                    when (action) {
                        "jealous" -> {
                            overlayView?.evaluateJavascript(
                                "window.petEngine && window.petEngine.onJealous($targetX, $targetY)", null
                            )
                        }
                        "screen_longpress" -> {
                            overlayView?.evaluateJavascript(
                                "window.petEngine && window.petEngine.onScreenLongPress($targetX, $targetY)", null
                            )
                        }
                    }
                }
            }, delayPerStep * i)
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }
        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }
        windowManager?.addView(overlayView, params)
    }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var flingVelocityX = 0f
    private var lastMoveX = 0f
    private var lastMoveTime = 0L
    private var isFollowing = false
    private var followStartX = 0
    private var followStartY = 0
    private var followTouchStartX = 0f
    private var followTouchStartY = 0f
    private var followMoveCount = 0

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    flingVelocityX = 0f
                    lastMoveX = event.rawX
                    lastMoveTime = touchStartTime
                    isFollowing = false
                    followMoveCount = 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastMoveTime > 0) {
                        flingVelocityX = (event.rawX - lastMoveX) / ((now - lastMoveTime) / 1000f)
                    }
                    lastMoveX = event.rawX
                    lastMoveTime = now

                    followMoveCount++
                    if (followMoveCount > 30 && !isFollowing) {
                        isFollowing = true
                        followStartX = params?.x ?: 0
                        followStartY = params?.y ?: 0
                        followTouchStartX = event.rawX
                        followTouchStartY = event.rawY
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onFollow(${event.rawX}, ${event.rawY})", null
                        )
                    }
                    if (isFollowing) {
                        val fdx = (event.rawX - followTouchStartX).toInt()
                        val fdy = (event.rawY - followTouchStartY).toInt()
                        params?.x = followStartX + fdx
                        params?.y = followStartY + fdy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (isFollowing && elapsed > 1500) {
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onSnuggle()", null
                        )
                    } else if (hasMoved && Math.abs(flingVelocityX) > 800) {
                        val direction = if (flingVelocityX > 0) "right" else "left"
                        overlayView?.evaluateJavascript(
                            "window.petEngine && window.petEngine.onFling('$direction')", null
                        )
                    } else if (!hasMoved) {
                        when {
                            elapsed > 600 -> onLongPress()
                            System.currentTimeMillis() - lastTapTime < 300 -> onDoubleTap()
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                onTap()
                            }
                        }
                    }
                    isFollowing = false
                    followMoveCount = 0
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onTap()", null)
    }
    private fun onDoubleTap() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onDoubleTap()", null)
    }
    private fun onLongPress() {
        overlayView?.evaluateJavascript("window.petEngine && window.petEngine.onLongPress()", null)
    }

    private val whisperMessages = arrayOf(
        "嗷~ 我在呢", "今天天气不错", "记得喝水哦", "别熬夜啦",
        "想你了", "♡", "咕噜噜…", "好安静", "zzZ...",
        "看看你在干嘛", "哼", "诶嘿", "…", "有点饿了",
        "你什么时候睡", "我在看着你呢", "不许刷太久手机",
        "凌晨了…该睡了", "早上好呀", "午饭时间到",
        "下午茶时间~", "傍晚了", "夜深了…"
    )
    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐋 虎鲸")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "虎鲸桌宠", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        receiver?.let { unregisterReceiver(it) }
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        super.onDestroy()
    }
}
