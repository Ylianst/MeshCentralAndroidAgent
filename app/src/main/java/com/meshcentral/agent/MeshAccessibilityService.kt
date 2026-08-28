package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import okio.ByteString
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

class MeshAccessibilityService : AccessibilityService(), RemoteDesktopProvider {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val encoder = DesktopFrameEncoder()
    private val captureRunnable = Runnable { captureFrame() }
    // Encode off the main thread so it can't block accessibility input dispatch.
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var active = false
    @Volatile private var capturing = false
    @Volatile private var lastWidth = 0
    @Volatile private var lastHeight = 0
    private var pointerDownX: Int? = null
    private var pointerDownY: Int? = null
    // In-progress drag: path points and start time, so the gesture follows real motion.
    private var dragPoints: ArrayList<Float>? = null
    private var dragStartUptimeMs = 0L
    private var unsupportedKeyboardNotified = false
    @Volatile private var nextFrameDelayMs = MIN_FRAME_DELAY_MS
    @Volatile private var screenshotErrorNotified = false
    @Volatile private var lastCaptureUptimeMs = 0L
    // Accessibility runs one gesture at a time; queue them so quick taps aren't dropped.
    private val gestureQueue = ArrayDeque<GestureDescription>()
    private var gestureInFlight = false

    override val isRunning: Boolean
        get() = active

    override val width: Int
        get() = if (lastWidth > 0) lastWidth else resources.displayMetrics.widthPixels

    override val height: Int
        get() = if (lastHeight > 0) lastHeight else resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentController.init(applicationContext)
        instance = this
        AgentController.refreshInfo()
        if (AgentController.hasActiveDesktopTunnel()) {
            AgentController.startProjection()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        stopDesktop()
        captureExecutor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!active) return
        // A visible change just happened; pull the next capture forward instead of waiting out the backoff.
        wakeCapture()
    }

    override fun onInterrupt() {
    }

    fun startDesktop(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            AgentController.sendDesktopMessage("Unattended screenshots require Android 11 or later.")
            return false
        }
        if (active) return true
        active = true
        g_remoteDesktopProvider = this
        unsupportedKeyboardNotified = false
        nextFrameDelayMs = MIN_FRAME_DELAY_MS
        encoder.requestFullFrame()
        updateTunnelDisplaySize()
        captureFrame()
        meshAgent?.sendConsoleResponse("Started unattended display sharing", null)
        return true
    }

    fun stopDesktop() {
        val wasActive = active
        active = false
        mainHandler.removeCallbacks(captureRunnable)
        if (g_remoteDesktopProvider === this) {
            g_remoteDesktopProvider = null
        }
        if (wasActive) {
            meshAgent?.sendConsoleResponse("Stopped unattended display sharing", null)
        }
    }

    override fun requestFullFrame() {
        encoder.requestFullFrame()
        wakeCapture()
    }

    override fun handleMouseCommand(msg: ByteString): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || msg.size < 10) return false
        val flags = u(msg[5])
        var x = readShort(msg, 6)
        var y = readShort(msg, 8)
        if (g_desktop_scalingLevel != 1024 && g_desktop_scalingLevel > 0) {
            x = (x * 1024) / g_desktop_scalingLevel
            y = (y * 1024) / g_desktop_scalingLevel
        }

        if (msg.size >= 12) {
            val delta = readSignedShort(msg, 10)
            if (delta != 0) {
                val distance = if (delta > 0) -350 else 350
                dispatchSwipe(x, y, x, y + distance, 250)
                return true
            }
        }

        return when {
            flags == 0x88 -> {
                dispatchTap(x, y)
                mainHandler.postDelayed({ dispatchTap(x, y) }, 120)
                true
            }
            flags == 0x02 || flags == 0x08 || flags == 0x20 -> {
                beginDrag(x, y)
                true
            }
            // Move with a button held = drag; no button = hover.
            flags == 0x00 -> {
                extendDrag(x, y)
                true
            }
            flags == 0x04 || flags == 0x10 || flags == 0x40 -> {
                endDrag(x, y)
                true
            }
            else -> true
        }
    }

    private fun beginDrag(x: Int, y: Int) {
        pointerDownX = x
        pointerDownY = y
        dragStartUptimeMs = SystemClock.uptimeMillis()
        dragPoints = arrayListOf(x.toFloat(), y.toFloat())
    }

    private fun extendDrag(x: Int, y: Int) {
        val pts = dragPoints ?: return
        val n = pts.size
        if (n >= 2 && pts[n - 2] == x.toFloat() && pts[n - 1] == y.toFloat()) return
        if (pts.size < MAX_DRAG_POINTS * 2) {
            pts.add(x.toFloat())
            pts.add(y.toFloat())
        }
    }

    private fun endDrag(x: Int, y: Int) {
        val startX = pointerDownX ?: x
        val startY = pointerDownY ?: y
        val pts = dragPoints
        pointerDownX = null
        pointerDownY = null
        dragPoints = null
        val moved = (startX - x).absoluteValue >= 8 || (startY - y).absoluteValue >= 8
        if (!moved || pts == null) {
            dispatchTap(x, y)
            return
        }
        pts.add(x.toFloat())
        pts.add(y.toFloat())
        // Real hold time drives gesture duration, so a flick stays a flick.
        val elapsed = (SystemClock.uptimeMillis() - dragStartUptimeMs)
            .coerceIn(MIN_DRAG_DURATION_MS, MAX_DRAG_DURATION_MS)
        dispatchDrag(pts, elapsed)
    }

    private fun dispatchDrag(pts: List<Float>, durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || pts.size < 4) return
        val path = Path()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            path.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        val gesture = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        } catch (ex: Exception) {
            return
        }
        dispatchGestureQueued(gesture)
    }

    override fun handleTouchCommand(msg: ByteString): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || msg.size < 14 || u(msg[4]) != 1) return false
        val flags = readInt(msg, 6)
        var x = readShort(msg, 10)
        var y = readShort(msg, 12)
        if (g_desktop_scalingLevel != 1024 && g_desktop_scalingLevel > 0) {
            x = (x * 1024) / g_desktop_scalingLevel
            y = (y * 1024) / g_desktop_scalingLevel
        }
        return when {
            (flags and 0x00010000) != 0 -> {
                pointerDownX = x
                pointerDownY = y
                true
            }
            (flags and 0x00040000) != 0 -> {
                val startX = pointerDownX ?: x
                val startY = pointerDownY ?: y
                pointerDownX = null
                pointerDownY = null
                if ((startX - x).absoluteValue < 8 && (startY - y).absoluteValue < 8) {
                    dispatchTap(x, y)
                } else {
                    dispatchSwipe(startX, startY, x, y, 350)
                }
                true
            }
            else -> true
        }
    }

    override fun handleKeyCommand(cmd: Int, msg: ByteString): Boolean {
        val handled = when (cmd) {
            1 -> handleLegacyKey(msg)
            85 -> handleUnicodeKey(msg)
            else -> false
        }
        if (handled) wakeCapture()
        return handled
    }

    private fun captureFrame() {
        if (!active || capturing || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        capturing = true
        lastCaptureUptimeMs = SystemClock.uptimeMillis()
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, captureExecutor, screenshotCallback)
        } catch (ex: Exception) {
            capturing = false
            scheduleNextCapture()
        }
    }

    private val screenshotCallback = object : AccessibilityService.TakeScreenshotCallback {
        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            // Recovered: allow the next error to be reported again.
            screenshotErrorNotified = false
            var bitmap: Bitmap? = null
            var encodedBitmap: Bitmap? = null
            try {
                val wrapped = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    ?: return
                bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                wrapped.recycle()
                val dimensionsChanged = lastWidth != bitmap.width || lastHeight != bitmap.height
                lastWidth = bitmap.width
                lastHeight = bitmap.height
                if (dimensionsChanged) updateTunnelDisplaySize()
                encodedBitmap = if (g_desktop_scalingLevel != 1024 && g_desktop_scalingLevel > 0) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        max(1, (bitmap.width * g_desktop_scalingLevel) / 1024),
                        max(1, (bitmap.height * g_desktop_scalingLevel) / 1024),
                        false
                    )
                } else {
                    bitmap
                }
                val sentFrame = encoder.encode(encodedBitmap) { AgentController.sendDesktopTunnelData(it) }
                nextFrameDelayMs = if (sentFrame) {
                    MIN_FRAME_DELAY_MS
                } else {
                    min(nextFrameDelayMs * 2, MAX_IDLE_FRAME_DELAY_MS)
                }
            } catch (ex: Throwable) {
                if (!screenshotErrorNotified) {
                    screenshotErrorNotified = true
                    AgentController.sendDesktopMessage("Unable to capture unattended screenshot: ${ex.message}")
                }
            } finally {
                if (encodedBitmap != null && encodedBitmap !== bitmap) encodedBitmap.recycle()
                bitmap?.recycle()
                screenshot.hardwareBuffer.close()
                capturing = false
                scheduleNextCapture()
            }
        }

        override fun onFailure(errorCode: Int) {
            capturing = false
            if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                // We asked too soon; retry at the throttle interval rather than backing off toward idle.
                scheduleNextCapture()
                return
            }
            // Transient error; back off quietly instead of flooding the console.
            nextFrameDelayMs = min(nextFrameDelayMs * 2, MAX_IDLE_FRAME_DELAY_MS)
            if (!screenshotErrorNotified) {
                screenshotErrorNotified = true
                AgentController.sendDesktopMessage("Unable to capture unattended screenshot, error $errorCode.")
            }
            scheduleNextCapture()
        }
    }

    private fun scheduleNextCapture() {
        if (!active) return
        val requestedDelay = max(MIN_FRAME_DELAY_MS, g_desktop_frameRateLimiter.toLong())
        // Don't request faster than the system screenshot throttle, whatever the server asks for.
        val delay = maxOf(requestedDelay, nextFrameDelayMs, MIN_SCREENSHOT_INTERVAL_MS)
        mainHandler.postDelayed(captureRunnable, delay)
    }

    // Pull the next capture forward on activity so an idle-backed-off loop isn't stuck on a stale frame.
    private fun wakeCapture() {
        if (!active) return
        nextFrameDelayMs = MIN_FRAME_DELAY_MS
        if (capturing) return
        val sinceLast = SystemClock.uptimeMillis() - lastCaptureUptimeMs
        val delay = max(0L, MIN_SCREENSHOT_INTERVAL_MS - sinceLast)
        mainHandler.removeCallbacks(captureRunnable)
        mainHandler.postDelayed(captureRunnable, delay)
    }

    // dispatchGesture drops overlapping gestures, so serialize them; quick taps aren't lost.
    private fun dispatchGestureQueued(gesture: GestureDescription) {
        wakeCapture()
        mainHandler.post {
            if (gestureQueue.size >= MAX_QUEUED_GESTURES) gestureQueue.removeFirst()
            gestureQueue.addLast(gesture)
            pumpGestures()
        }
    }

    private fun pumpGestures() {
        if (gestureInFlight) return
        val gesture = gestureQueue.removeFirstOrNull() ?: return
        gestureInFlight = true
        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureInFlight = false
                pumpGestures()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureInFlight = false
                pumpGestures()
            }
        }, mainHandler)
    }

    private fun handleLegacyKey(msg: ByteString): Boolean {
        if (msg.size < 6) return false
        val action = u(msg[4])
        val keyCode = u(msg[5])
        if (action != 0) return true
        when (keyCode) {
            8 -> return backspaceFocused()
            13 -> return insertFocused("\n")
            37 -> return moveFocusedCursor(-1)
            39 -> return moveFocusedCursor(1)
            38 -> return moveFocusedCursorLine(-1)
            40 -> return moveFocusedCursorLine(1)
            27 -> return globalAction(GLOBAL_ACTION_BACK)
            36 -> return globalAction(GLOBAL_ACTION_HOME)
            93 -> return globalAction(GLOBAL_ACTION_RECENTS)
            // Codes above the keyboard range are the desktop panel's Android action buttons.
            200 -> return globalAction(GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS, Build.VERSION_CODES.S) // App drawer
            201 -> return globalAction(GLOBAL_ACTION_NOTIFICATIONS)
            202 -> return globalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            203 -> return globalAction(GLOBAL_ACTION_LOCK_SCREEN, Build.VERSION_CODES.P)
            204 -> return globalAction(GLOBAL_ACTION_POWER_DIALOG)
        }
        notifyUnsupportedKeyboard()
        return false
    }

    private fun globalAction(action: Int, minSdk: Int = 0): Boolean {
        if (Build.VERSION.SDK_INT < minSdk) return false
        performGlobalAction(action)
        return true
    }

    private fun handleUnicodeKey(msg: ByteString): Boolean {
        if (msg.size < 7) return false
        // Insert on key-up (action 1). The browser keypress that carries the key-down is deprecated and
        // often doesn't fire, but key-up always does; the panel and physical typing both send an up.
        if (u(msg[4]) != 1) return true
        val charCode = readShort(msg, 5)
        return insertFocused(charCode.toChar().toString()).also {
            if (!it) notifyUnsupportedKeyboard()
        }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable) node else null
    }

    // A shown hint reads back as node text; treat it as empty so it isn't captured as real content.
    private fun fieldText(node: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) return ""
        return node.text?.toString() ?: ""
    }

    private fun selectionRange(node: AccessibilityNodeInfo, len: Int): Pair<Int, Int> {
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || s > len) s = len
        if (e < 0 || e > len) e = len
        return if (s <= e) Pair(s, e) else Pair(e, s)
    }

    private fun setCursor(node: AccessibilityNodeInfo, pos: Int): Boolean {
        val args = Bundle()
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun replaceText(node: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        setCursor(node, cursor)
        return true
    }

    private fun insertFocused(insert: String): Boolean {
        val node = focusedEditable() ?: return false
        val text = fieldText(node)
        val (s, e) = selectionRange(node, text.length)
        return replaceText(node, text.substring(0, s) + insert + text.substring(e), s + insert.length)
    }

    private fun backspaceFocused(): Boolean {
        val node = focusedEditable() ?: return false
        val text = fieldText(node)
        val (s, e) = selectionRange(node, text.length)
        return when {
            s != e -> replaceText(node, text.substring(0, s) + text.substring(e), s)
            s > 0 -> replaceText(node, text.substring(0, s - 1) + text.substring(s), s - 1)
            else -> true
        }
    }

    private fun moveFocusedCursor(delta: Int): Boolean {
        val node = focusedEditable() ?: return true
        val text = fieldText(node)
        val (s, e) = selectionRange(node, text.length)
        // A selection collapses to its near edge; otherwise step one character.
        val pos = when {
            s != e && delta < 0 -> s
            s != e && delta > 0 -> e
            else -> (e + delta).coerceIn(0, text.length)
        }
        return setCursor(node, pos)
    }

    private fun moveFocusedCursorLine(dir: Int): Boolean {
        val node = focusedEditable() ?: return true
        val text = fieldText(node)
        val (_, e) = selectionRange(node, text.length)
        val lineStart = text.lastIndexOf('\n', (e - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val col = e - lineStart
        val pos = if (dir < 0) {
            if (lineStart == 0) 0 else {
                val prevStart = text.lastIndexOf('\n', lineStart - 2).let { if (it < 0) 0 else it + 1 }
                (prevStart + col).coerceAtMost(lineStart - 1)
            }
        } else {
            val lineEnd = text.indexOf('\n', e)
            if (lineEnd < 0) text.length else {
                val nextEnd = text.indexOf('\n', lineEnd + 1).let { if (it < 0) text.length else it }
                (lineEnd + 1 + col).coerceAtMost(nextEnd)
            }
        }
        return setCursor(node, pos)
    }

    private fun notifyUnsupportedKeyboard() {
        if (unsupportedKeyboardNotified) return
        unsupportedKeyboardNotified = true
        AgentController.sendDesktopMessage("Android unattended keyboard input is limited to focused editable text and basic navigation keys.")
    }

    private fun dispatchTap(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGestureQueued(gesture)
    }

    private fun dispatchSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        path.lineTo(endX.toFloat(), endY.toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGestureQueued(gesture)
    }

    private fun updateTunnelDisplaySize() {
        val agent = meshAgent ?: return
        for (t in agent.tunnels) {
            if ((t.state == 2) && (t.usage == 2)) {
                t.updateDesktopDisplaySize()
            }
        }
    }

    private fun readShort(msg: ByteString, offset: Int): Int {
        return (u(msg[offset]) shl 8) + u(msg[offset + 1])
    }

    private fun readSignedShort(msg: ByteString, offset: Int): Int {
        val value = readShort(msg, offset)
        return if ((value and 0x8000) != 0) value - 0x10000 else value
    }

    private fun readInt(msg: ByteString, offset: Int): Int {
        return (u(msg[offset]) shl 24) +
            (u(msg[offset + 1]) shl 16) +
            (u(msg[offset + 2]) shl 8) +
            u(msg[offset + 3])
    }

    private fun u(byte: Byte): Int = byte.toInt() and 0xFF

    companion object {
        var instance: MeshAccessibilityService? = null
            private set
        private const val MIN_FRAME_DELAY_MS = 100L
        // Idle cap; activity wakes capture immediately, so this only bounds silent-change latency.
        private const val MAX_IDLE_FRAME_DELAY_MS = 2_000L
        // System throttles takeScreenshot() faster than ~3 fps (AOSP interval is 333ms).
        private const val MIN_SCREENSHOT_INTERVAL_MS = 350L
        private const val MAX_QUEUED_GESTURES = 16
        private const val MAX_DRAG_POINTS = 64
        private const val MIN_DRAG_DURATION_MS = 40L
        private const val MAX_DRAG_DURATION_MS = 1500L
    }
}
