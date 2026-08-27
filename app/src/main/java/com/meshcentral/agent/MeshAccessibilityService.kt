package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var unsupportedKeyboardNotified = false
    @Volatile private var nextFrameDelayMs = MIN_FRAME_DELAY_MS
    @Volatile private var screenshotErrorNotified = false

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
        nextFrameDelayMs = MIN_FRAME_DELAY_MS
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
        nextFrameDelayMs = MIN_FRAME_DELAY_MS
        encoder.requestFullFrame()
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
                pointerDownX = x
                pointerDownY = y
                true
            }
            flags == 0x04 || flags == 0x10 || flags == 0x40 -> {
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
        return when (cmd) {
            1 -> handleLegacyKey(msg)
            85 -> handleUnicodeKey(msg)
            else -> false
        }
    }

    private fun captureFrame() {
        if (!active || capturing || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        capturing = true
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
            // Throttled or transient error; back off quietly instead of flooding the console.
            nextFrameDelayMs = min(nextFrameDelayMs * 2, MAX_IDLE_FRAME_DELAY_MS)
            if (errorCode != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && !screenshotErrorNotified) {
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

    private fun handleLegacyKey(msg: ByteString): Boolean {
        if (msg.size < 6) return false
        val action = u(msg[4])
        val keyCode = u(msg[5])
        if (action != 0) return true
        when (keyCode) {
            8 -> return editFocusedText { if (it.isNotEmpty()) it.dropLast(1) else it }
            13 -> return editFocusedText { "$it\n" }
            27 -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return true
            }
            36 -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return true
            }
            93 -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                return true
            }
        }
        notifyUnsupportedKeyboard()
        return false
    }

    private fun handleUnicodeKey(msg: ByteString): Boolean {
        if (msg.size < 7) return false
        val action = u(msg[4])
        if (action != 0) return true
        val charCode = readShort(msg, 5)
        val char = charCode.toChar().toString()
        return editFocusedText { it + char }.also {
            if (!it) notifyUnsupportedKeyboard()
        }
    }

    private fun editFocusedText(transform: (String) -> String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable) return false
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            transform(node.text?.toString() ?: "")
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
        dispatchGesture(gesture, null, null)
    }

    private fun dispatchSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        path.lineTo(endX.toFloat(), endY.toFloat())
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
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
        private const val MAX_IDLE_FRAME_DELAY_MS = 10_000L
        // System throttles takeScreenshot() faster than ~3 fps.
        private const val MIN_SCREENSHOT_INTERVAL_MS = 350L
    }
}
