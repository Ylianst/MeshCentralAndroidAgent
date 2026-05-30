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
import kotlin.math.absoluteValue
import kotlin.math.max

class MeshAccessibilityService : AccessibilityService(), RemoteDesktopProvider {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val encoder = DesktopFrameEncoder()
    private val captureRunnable = Runnable { captureFrame() }
    private var active = false
    private var capturing = false
    private var lastWidth = 0
    private var lastHeight = 0
    private var pointerDownX: Int? = null
    private var pointerDownY: Int? = null
    private var unsupportedKeyboardNotified = false

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
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                try {
                    val wrapped = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    if (wrapped != null) {
                        val bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                        val dimensionsChanged = lastWidth != bitmap.width || lastHeight != bitmap.height
                        lastWidth = bitmap.width
                        lastHeight = bitmap.height
                        if (dimensionsChanged) updateTunnelDisplaySize()
                        val encodedBitmap = if (g_desktop_scalingLevel != 1024 && g_desktop_scalingLevel > 0) {
                            Bitmap.createScaledBitmap(
                                bitmap,
                                max(1, (bitmap.width * g_desktop_scalingLevel) / 1024),
                                max(1, (bitmap.height * g_desktop_scalingLevel) / 1024),
                                false
                            )
                        } else {
                            bitmap
                        }
                        encoder.encode(encodedBitmap) { AgentController.sendDesktopTunnelData(it) }
                        if (encodedBitmap !== bitmap) encodedBitmap.recycle()
                        bitmap.recycle()
                    }
                } catch (ex: Exception) {
                    AgentController.sendDesktopMessage("Unable to capture unattended screenshot: ${ex.message}")
                } finally {
                    screenshot.hardwareBuffer.close()
                    capturing = false
                    scheduleNextCapture()
                }
            }

            override fun onFailure(errorCode: Int) {
                capturing = false
                AgentController.sendDesktopMessage("Unable to capture unattended screenshot, error $errorCode.")
                scheduleNextCapture()
            }
        })
    }

    private fun scheduleNextCapture() {
        if (!active) return
        mainHandler.postDelayed(captureRunnable, max(100L, g_desktop_frameRateLimiter.toLong()))
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
    }
}
