package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
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
    private var unsupportedKeyboardNotified = false
    @Volatile private var nextFrameDelayMs = MIN_FRAME_DELAY_MS
    @Volatile private var screenshotErrorNotified = false
    @Volatile private var lastCaptureUptimeMs = 0L
    // Each screenshot request gets a sequence number; a late or duplicate answer for an older
    // request is dropped so it can't paint over a newer frame.
    @Volatile private var captureSequence = 0

    // Pointer input is streamed as continued strokes: button down puts a finger on the screen,
    // each move drags it and button up lifts it. Drags happen live, holding the button is a long
    // press and long-press-then-drag works. Android runs one injected gesture at a time, so steps
    // queue here and the completion callback pumps the next one. Main thread only.
    private sealed class InputStep {
        class Down(val x: Int, val y: Int) : InputStep()
        class Move(val x: Int, val y: Int) : InputStep()
        class Up(val x: Int, val y: Int) : InputStep()
        class DoubleTap(val x: Int, val y: Int) : InputStep()
        class Gesture(val gesture: GestureDescription) : InputStep()
    }
    private val inputSteps = ArrayDeque<InputStep>()
    private var gestureInFlight = false
    // The last dispatched stroke whose finger is still down, and where it left it.
    private var heldStroke: GestureDescription.StrokeDescription? = null
    private var heldX = 0f
    private var heldY = 0f
    private var fingerMoved = false
    private var lastSegmentUptimeMs = 0L
    private val recentTapUptimes = ArrayDeque<Long>()
    private var keyguardNoticeSent = false
    // What the operator typed into the focused password field; Android masks the field's own text.
    private val passwordBuffer = StringBuilder()
    private var passwordNodeKey: String? = null
    // Modifier keys arrive as their own key messages; remembered for shortcuts and shift-selection.
    private var shiftHeld = false
    private var ctrlHeld = false
    private var altHeld = false
    // The last text written to a field with SET_TEXT, used while the app is still applying it.
    private var shadowNodeKey: String? = null
    private var shadowText: String? = null
    private var shadowCursor = 0
    private var shadowUptimeMs = 0L
    // Mouse-style text selection in progress (see beginDragSelect).
    private var dragSelectNode: AccessibilityNodeInfo? = null
    private var dragSelectRects: List<RectF?>? = null
    private var dragSelectAnchor = -1
    private var dragSelectLastFocus = -1
    private var dragSelectActive = false
    private var dragDownX = 0
    private var dragDownY = 0

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
        if (!AgentController.hasAuthorizedDesktopTunnel()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            AgentController.sendDesktopMessage("Unattended screenshots require Android 11 or later.", timeoutSeconds = null)
            return false
        }
        if (active) return true
        active = true
        g_remoteDesktopProvider = this
        unsupportedKeyboardNotified = false
        nextFrameDelayMs = MIN_FRAME_DELAY_MS
        encoder.requestFullFrame()
        updateTunnelDisplaySize()
        AgentController.desktopProviderStarted()
        noteKeyguard()
        captureFrame()
        meshAgent?.sendConsoleResponse("Started unattended display sharing", null)
        return true
    }

    fun stopDesktop() {
        val wasActive = active
        active = false
        captureSequence++
        capturing = false
        mainHandler.removeCallbacks(captureRunnable)
        mainHandler.removeCallbacks(captureWatchdog)
        releaseHeldPointer()
        shiftHeld = false
        ctrlHeld = false
        altHeld = false
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
        val (x, y) = toScreen(readShort(msg, 6), readShort(msg, 8))

        if (msg.size >= 12) {
            val delta = readSignedShort(msg, 10)
            if (delta != 0) {
                // Wheel: swipe the content under the cursor; a positive delta scrolls up.
                val distance = if (delta > 0) -SCROLL_STEP_PX else SCROLL_STEP_PX
                val endY = (y + distance).coerceIn(0, max(0, height - 1))
                swipeGesture(x, y, x, endY, SCROLL_SWIPE_MS)?.let { enqueue(InputStep.Gesture(it)) }
                return true
            }
        }

        when (val input = decodeMouseFlags(u(msg[5]))) {
            is MouseInput.Down -> if (input.button == MouseButton.LEFT) enqueue(InputStep.Down(x, y))
            is MouseInput.Up -> when (input.button) {
                MouseButton.LEFT -> enqueue(InputStep.Up(x, y))
                // Android has no secondary buttons; follow the scrcpy and Vysor convention instead.
                MouseButton.RIGHT -> globalAction(GLOBAL_ACTION_BACK)
                MouseButton.MIDDLE -> globalAction(GLOBAL_ACTION_HOME)
            }
            MouseInput.Move -> enqueue(InputStep.Move(x, y))
            MouseInput.DoubleClick -> enqueue(InputStep.DoubleTap(x, y))
            MouseInput.Unknown -> {}
        }
        return true
    }

    override fun handleTouchCommand(msg: ByteString): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || msg.size < 14 || u(msg[4]) != 1) return false
        val flags = readInt(msg, 6)
        val (x, y) = toScreen(readShort(msg, 10), readShort(msg, 12))
        when {
            (flags and TOUCH_FLAG_DOWN) != 0 -> enqueue(InputStep.Down(x, y))
            (flags and TOUCH_FLAG_UP) != 0 -> enqueue(InputStep.Up(x, y))
            (flags and TOUCH_FLAG_UPDATE) != 0 -> enqueue(InputStep.Move(x, y))
        }
        return true
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

    // Undo viewer scaling and keep the point on screen: a drag released past the viewer's edge
    // arrives with out-of-range coordinates that Android would refuse.
    private fun toScreen(rawX: Int, rawY: Int): Pair<Int, Int> {
        var x = rawX
        var y = rawY
        if (g_desktop_scalingLevel != 1024 && g_desktop_scalingLevel > 0) {
            x = (x * 1024) / g_desktop_scalingLevel
            y = (y * 1024) / g_desktop_scalingLevel
        }
        return Pair(x.coerceIn(0, max(0, width - 1)), y.coerceIn(0, max(0, height - 1)))
    }

    private fun enqueue(step: InputStep) {
        mainHandler.post {
            // Moves are coalesced anyway; drop a surplus one rather than a press or release.
            if (inputSteps.size >= MAX_QUEUED_STEPS && step is InputStep.Move) return@post
            inputSteps.addLast(step)
            pumpInput()
        }
    }

    private fun pumpInput() {
        while (!gestureInFlight) {
            val step = inputSteps.removeFirstOrNull() ?: return
            when (step) {
                is InputStep.Down -> {
                    noteKeyguard()
                    if (heldStroke != null) {
                        // A second down without an up means the release was lost: lift, then redo it.
                        inputSteps.addFirst(step)
                        liftPointer(heldX.toInt(), heldY.toInt())
                    } else if (beginDragSelect(step.x, step.y)) {
                        // Nothing touches the screen yet: a move makes this a selection, a release
                        // becomes a tap, and a hold becomes a real press once the deferral expires.
                        mainHandler.postDelayed(deferredPress, DRAG_SELECT_DEFER_MS)
                    } else {
                        pressPointer(step.x, step.y)
                    }
                }
                is InputStep.Move -> {
                    var move = step
                    // Only the newest position matters once a segment is already in flight.
                    while (inputSteps.firstOrNull() is InputStep.Move) {
                        move = inputSteps.removeFirst() as InputStep.Move
                    }
                    if (dragSelectActive) {
                        updateDragSelect(move.x, move.y)
                    } else if (dragSelectNode != null) {
                        if ((move.x - dragDownX).absoluteValue > DRAG_SELECT_SLOP || (move.y - dragDownY).absoluteValue > DRAG_SELECT_SLOP) {
                            mainHandler.removeCallbacks(deferredPress)
                            dragSelectActive = true
                            println("dragSelect: selecting from offset $dragSelectAnchor")
                            updateDragSelect(move.x, move.y)
                        }
                    } else if (heldStroke != null && (move.x.toFloat() != heldX || move.y.toFloat() != heldY)) {
                        movePointer(move.x, move.y)
                    }
                }
                is InputStep.Up -> {
                    if (dragSelectNode != null && !dragSelectActive) {
                        // Released without moving: deliver the click as a tap now.
                        val x = dragDownX
                        val y = dragDownY
                        endDragSelect()
                        recentTapUptimes.addLast(SystemClock.uptimeMillis())
                        while (recentTapUptimes.size > 4) recentTapUptimes.removeFirst()
                        tapGesture(x, y)?.let { dispatchQueued(it) }
                    } else {
                        endDragSelect()
                        if (heldStroke != null) liftPointer(step.x, step.y)
                    }
                }
                is InputStep.DoubleTap -> {
                    // The viewer sends both clicks as down/up pairs before this flag, so only
                    // synthesize the taps when they didn't come through.
                    val cutoff = SystemClock.uptimeMillis() - DOUBLE_TAP_WINDOW_MS
                    if (recentTapUptimes.count { it >= cutoff } < 2) {
                        inputSteps.addFirst(InputStep.Up(step.x, step.y))
                        inputSteps.addFirst(InputStep.Down(step.x, step.y))
                        inputSteps.addFirst(InputStep.Up(step.x, step.y))
                        inputSteps.addFirst(InputStep.Down(step.x, step.y))
                    }
                }
                is InputStep.Gesture -> {
                    if (heldStroke != null) {
                        inputSteps.addFirst(step)
                        liftPointer(heldX.toInt(), heldY.toInt())
                    } else {
                        dispatchQueued(step.gesture)
                    }
                }
            }
        }
    }

    private fun pressPointer(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // No continued strokes before Android 8: a press is a plain tap and moves are dropped.
            tapGesture(x, y)?.let { dispatchQueued(it) }
            return
        }
        val fx = x.toFloat()
        val fy = y.toFloat()
        val stroke = try {
            GestureDescription.StrokeDescription(pointPath(fx, fy), 0, PRESS_MS, true)
        } catch (ex: Exception) {
            return
        }
        fingerMoved = false
        dispatchStroke(stroke, fx, fy, keepsFinger = true)
    }

    private fun movePointer(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val held = heldStroke ?: return
        val fx = x.toFloat()
        val fy = y.toFloat()
        val stroke = try {
            held.continueStroke(linePath(heldX, heldY, fx, fy), 0, segmentDurationMs(), true)
        } catch (ex: Exception) {
            heldStroke = null
            return
        }
        fingerMoved = true
        dispatchStroke(stroke, fx, fy, keepsFinger = true)
    }

    private fun liftPointer(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val held = heldStroke ?: return
        val fx = x.toFloat()
        val fy = y.toFloat()
        val stationary = fx == heldX && fy == heldY
        val stroke = try {
            if (stationary) {
                held.continueStroke(pointPath(fx, fy), 0, LIFT_MS, false)
            } else {
                held.continueStroke(linePath(heldX, heldY, fx, fy), 0, segmentDurationMs(), false)
            }
        } catch (ex: Exception) {
            heldStroke = null
            return
        }
        if (stationary && !fingerMoved) {
            recentTapUptimes.addLast(SystemClock.uptimeMillis())
            while (recentTapUptimes.size > 4) recentTapUptimes.removeFirst()
        }
        dispatchStroke(stroke, fx, fy, keepsFinger = false)
    }

    private fun dispatchStroke(stroke: GestureDescription.StrokeDescription, endX: Float, endY: Float, keepsFinger: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val gesture = try {
            GestureDescription.Builder().addStroke(stroke).build()
        } catch (ex: Exception) {
            heldStroke = null
            return
        }
        heldStroke = if (keepsFinger) stroke else null
        heldX = endX
        heldY = endY
        lastSegmentUptimeMs = SystemClock.uptimeMillis()
        if (!dispatchQueued(gesture)) heldStroke = null
    }

    // Hands one gesture to Android; the callback resumes the queue. False when it was refused.
    private fun dispatchQueued(gesture: GestureDescription): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        wakeCapture()
        gestureInFlight = true
        val callback = gestureCallback ?: GestureCallback().also { gestureCallback = it }
        val accepted = try {
            dispatchGesture(gesture, callback, mainHandler)
        } catch (ex: Exception) {
            false
        }
        if (!accepted) gestureInFlight = false
        return accepted
    }

    // Created on first use: instantiating it in a field initializer would reference a class older
    // Android releases lack and crash the service as it binds.
    private var gestureCallback: AccessibilityService.GestureResultCallback? = null

    @RequiresApi(Build.VERSION_CODES.N)
    private inner class GestureCallback : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            gestureInFlight = false
            pumpInput()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            // Android dropped the finger, usually because the device user touched the screen.
            gestureInFlight = false
            heldStroke = null
            pumpInput()
        }
    }

    // Segment length follows the real time between moves, so a slow drag stays slow and a flick
    // stays a flick; the cap keeps a move after a pause from crawling.
    private fun segmentDurationMs(): Long {
        return (SystemClock.uptimeMillis() - lastSegmentUptimeMs).coerceIn(MIN_SEGMENT_MS, MAX_SEGMENT_MS)
    }

    // Lift a finger left on screen when the session ends so it doesn't stay pressed.
    private fun releaseHeldPointer() {
        mainHandler.post {
            inputSteps.clear()
            endDragSelect()
            if (heldStroke != null) {
                inputSteps.addLast(InputStep.Up(heldX.toInt(), heldY.toInt()))
                pumpInput()
            }
        }
    }

    private fun pointPath(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

    private fun linePath(x1: Float, y1: Float, x2: Float, y2: Float): Path = Path().apply {
        moveTo(x1, y1)
        lineTo(x2, y2)
    }

    private fun strokeGesture(path: Path, durationMs: Long): GestureDescription? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        } catch (ex: Exception) {
            null
        }
    }

    private fun tapGesture(x: Int, y: Int): GestureDescription? {
        return strokeGesture(pointPath(x.toFloat(), y.toFloat()), TAP_MS)
    }

    private fun swipeGesture(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): GestureDescription? {
        return strokeGesture(linePath(startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat()), durationMs)
    }

    private fun captureFrame() {
        if (!active || capturing || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        capturing = true
        lastCaptureUptimeMs = SystemClock.uptimeMillis()
        val sequence = ++captureSequence
        mainHandler.removeCallbacks(captureWatchdog)
        mainHandler.postDelayed(captureWatchdog, CAPTURE_WATCHDOG_MS)
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, captureExecutor, ScreenshotCallback(sequence))
        } catch (ex: Exception) {
            captureFinished(sequence)
            scheduleNextCapture()
        }
    }

    // Android occasionally never answers a screenshot request; without this the loop would stop
    // for the rest of the session.
    private val captureWatchdog = Runnable {
        if (!capturing) return@Runnable
        println("takeScreenshot did not answer, retrying")
        captureSequence++
        capturing = false
        scheduleNextCapture()
    }

    private fun captureFinished(sequence: Int) {
        if (sequence != captureSequence) return
        mainHandler.removeCallbacks(captureWatchdog)
        capturing = false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private inner class ScreenshotCallback(private val sequence: Int) : AccessibilityService.TakeScreenshotCallback {
        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            if (!active || sequence != captureSequence) {
                screenshot.hardwareBuffer.close()
                return
            }
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
                if (!active || sequence != captureSequence) return
                val sentFrame = encoder.encode(encodedBitmap) {
                    if (active && sequence == captureSequence) AgentController.sendDesktopTunnelData(it)
                }
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
                if (sequence == captureSequence) {
                    captureFinished(sequence)
                    scheduleNextCapture()
                } else {
                    encoder.requestFullFrame()
                }
            }
        }

        override fun onFailure(errorCode: Int) {
            if (sequence != captureSequence) return
            captureFinished(sequence)
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

    private fun handleLegacyKey(msg: ByteString): Boolean {
        if (msg.size < 6) return false
        val down = u(msg[4]) == 0
        val keyCode = u(msg[5])
        when (keyCode) {
            16 -> { shiftHeld = down; return true }
            17 -> { ctrlHeld = down; return true }
            18 -> { altHeld = down; return true }
        }
        if (!down) return true
        if (ctrlHeld) return handleShortcut(keyCode)
        when (keyCode) {
            8 -> return (keyguardLocked() && keyguardKey("delete_button", null)) || editorKey(KeyEvent.KEYCODE_DEL) || backspaceFocused()
            9 -> if (editorKey(KeyEvent.KEYCODE_TAB)) return true
            13 -> return (keyguardLocked() && keyguardKey("key_enter", null)) || editorKey(KeyEvent.KEYCODE_ENTER) || enterFocused()
            46 -> return editorKey(KeyEvent.KEYCODE_FORWARD_DEL) || deleteForwardFocused()
            37 -> return editorKey(KeyEvent.KEYCODE_DPAD_LEFT) || moveFocusedCursor(-1)
            39 -> return editorKey(KeyEvent.KEYCODE_DPAD_RIGHT) || moveFocusedCursor(1)
            38 -> return editorKey(KeyEvent.KEYCODE_DPAD_UP) || moveFocusedCursorLine(-1)
            40 -> return editorKey(KeyEvent.KEYCODE_DPAD_DOWN) || moveFocusedCursorLine(1)
            27 -> return globalAction(GLOBAL_ACTION_BACK)
            36 -> return globalAction(GLOBAL_ACTION_HOME)
            93 -> return globalAction(GLOBAL_ACTION_RECENTS)
            // Codes above the keyboard range are the desktop panel's Android action buttons.
            200 -> return openAppDrawer()
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

    // GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS exists since Android 12, but only Android 14 SystemUI
    // wires it to the launcher; on 12 and 13 it injects a key the stock launcher ignores. So use it
    // only where it can work, check that the launcher actually came up, and otherwise do what a
    // user does: go home and swipe up.
    private fun openAppDrawer(): Boolean {
        val launcher = defaultLauncherPackage()
        val front = rootInActiveWindow?.packageName?.toString()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && launcher != null && front != launcher) {
            performGlobalAction(GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)
            mainHandler.postDelayed({
                if (rootInActiveWindow?.packageName?.toString() != launcher) swipeUpFromHome()
            }, APP_DRAWER_VERIFY_MS)
        } else {
            swipeUpFromHome()
        }
        return true
    }

    private fun swipeUpFromHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        mainHandler.postDelayed({
            val x = width / 2
            val gesture = swipeGesture(x, height * 4 / 5, x, height * 3 / 10, APP_DRAWER_SWIPE_MS) ?: return@postDelayed
            enqueue(InputStep.Gesture(gesture))
        }, HOME_SETTLE_MS)
    }

    private fun defaultLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return try {
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        } catch (ex: Exception) {
            null
        }
    }

    private fun handleUnicodeKey(msg: ByteString): Boolean {
        if (msg.size < 7) return false
        // Insert on key-up (action 1). The browser keypress that carries the key-down is deprecated and
        // often doesn't fire, but key-up always does; the panel and physical typing both send an up.
        if (u(msg[4]) != 1) return true
        val ch = readShort(msg, 5).toChar()
        // Lock screen PIN pad: no editable field, so press its buttons by hand.
        if (ch.isDigit() && keyguardLocked() && focusedEditable() == null && keyguardKey("key$ch", ch.toString())) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val connection = editorConnection()
            if (connection != null) {
                connection.commitText(ch.toString(), 1, null)
                return true
            }
        }
        return insertFocused(ch.toString()).also {
            if (!it) notifyUnsupportedKeyboard()
        }
    }

    // Android 13+ lets an accessibility service act as an input method: an InputConnection keeps
    // keystrokes in order and offers the editor's own key handling and shortcuts, so nothing is
    // dropped when the operator types fast. Null without an active text field or on older releases.
    private fun editorConnection(): InputMethod.AccessibilityInputConnection? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        // The connection can outlive the field it belonged to (the lock screen's PIN pad is one
        // case), so only trust it while a text field actually has input focus.
        if (focusedEditable() == null) return null
        return try {
            inputMethod?.currentInputConnection
        } catch (ex: Exception) {
            null
        }
    }

    // A hardware-style key press with the held modifiers, through the input connection.
    private fun editorKey(keyCode: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val connection = editorConnection() ?: return false
        val meta = currentMeta()
        val now = SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        return true
    }

    private fun currentMeta(): Int {
        var meta = 0
        if (shiftHeld) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (ctrlHeld) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (altHeld) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        return meta
    }

    // Ctrl shortcuts: the editor's own select-all, copy, cut and paste where an input connection
    // exists, node actions otherwise; any other combination goes through as a key with Ctrl held.
    private fun handleShortcut(keyCode: Int): Boolean {
        val menuId = when (keyCode) {
            65 -> android.R.id.selectAll
            67 -> android.R.id.copy
            86 -> android.R.id.paste
            88 -> android.R.id.cut
            else -> 0
        }
        if (menuId != 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val connection = editorConnection()
                if (connection != null) {
                    connection.performContextMenuAction(menuId)
                    return true
                }
            }
            val node = focusedEditable() ?: return true
            return when (keyCode) {
                65 -> setSelectionRange(node, 0, fieldText(node).length)
                67 -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
                86 -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                else -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            }
        }
        val androidKey = when (keyCode) {
            in 65..90 -> KeyEvent.KEYCODE_A + (keyCode - 65)
            37 -> KeyEvent.KEYCODE_DPAD_LEFT
            39 -> KeyEvent.KEYCODE_DPAD_RIGHT
            38 -> KeyEvent.KEYCODE_DPAD_UP
            40 -> KeyEvent.KEYCODE_DPAD_DOWN
            8 -> KeyEvent.KEYCODE_DEL
            46 -> KeyEvent.KEYCODE_FORWARD_DEL
            else -> 0
        }
        if (androidKey != 0) editorKey(androidKey)
        return true
    }

    // A drag that starts on the focused text field selects text, as a mouse does on a desktop,
    // instead of the touch behaviour of moving the caret. The field's character bounds are
    // fetched once per drag and matched against the pointer; fields that don't report them (some
    // web views) keep the touch behaviour.
    private fun beginDragSelect(x: Int, y: Int): Boolean {
        endDragSelect()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val node = focusedEditable() ?: return false
        if (node.isPassword) return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return false
        val rects = characterBounds(node)
        if (rects == null) {
            println("dragSelect: field reports no character bounds")
            return false
        }
        val anchor = offsetAt(rects, x, y)
        if (anchor == null) {
            println("dragSelect: no text offset at $x,$y")
            return false
        }
        dragSelectNode = node
        dragSelectRects = rects
        dragSelectAnchor = anchor
        dragSelectLastFocus = anchor
        dragDownX = x
        dragDownY = y
        return true
    }

    private fun updateDragSelect(x: Int, y: Int) {
        val node = dragSelectNode ?: return
        val rects = dragSelectRects ?: return
        val focus = offsetAt(rects, x, y) ?: return
        if (focus == dragSelectLastFocus) return
        dragSelectLastFocus = focus
        if (!setSelectionRange(node, dragSelectAnchor, focus)) println("dragSelect: selection $dragSelectAnchor..$focus refused")
    }

    private fun endDragSelect() {
        mainHandler.removeCallbacks(deferredPress)
        dragSelectNode = null
        dragSelectRects = null
        dragSelectActive = false
    }

    // The pointer is still held on the text field with no movement: it was a press after all, so
    // put the finger down now (a long press follows if it stays).
    private val deferredPress = object : Runnable {
        override fun run() {
            if (dragSelectNode == null || dragSelectActive) return
            if (gestureInFlight) {
                mainHandler.postDelayed(this, 20)
                return
            }
            val x = dragDownX
            val y = dragDownY
            endDragSelect()
            pressPointer(x, y)
        }
    }

    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun characterBounds(node: AccessibilityNodeInfo): List<RectF?>? {
        val length = min(fieldText(node).length, AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_MAX_LENGTH)
        if (length == 0) return null
        val args = Bundle()
        args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
        args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
        val fetched = try {
            node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
        } catch (ex: Exception) {
            false
        }
        if (!fetched) return null
        val array = node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY) ?: return null
        val rects = array.map { it as? RectF }
        return if (rects.any { it != null }) rects else null
    }

    // Text offset for a screen point: the nearest line by vertical distance, then the character
    // whose bounds hold x, with the caret after it when x is past its middle.
    private fun offsetAt(rects: List<RectF?>, x: Int, y: Int): Int? {
        var lineTop = 0f
        var lineBottom = 0f
        var best = Float.MAX_VALUE
        for (r in rects) {
            if (r == null) continue
            val distance = when {
                y < r.top -> r.top - y
                y > r.bottom -> y - r.bottom
                else -> 0f
            }
            if (distance < best) {
                best = distance
                lineTop = r.top
                lineBottom = r.bottom
            }
        }
        if (best == Float.MAX_VALUE) return null
        var firstIndex = -1
        var lastIndex = -1
        var firstLeft = 0f
        for ((i, r) in rects.withIndex()) {
            if (r == null || (r.top - lineTop).absoluteValue > 0.5f || (r.bottom - lineBottom).absoluteValue > 0.5f) continue
            if (firstIndex < 0) {
                firstIndex = i
                firstLeft = r.left
            }
            lastIndex = i
            if (x >= r.left && x <= r.right) return if (x < r.centerX()) i else i + 1
        }
        if (firstIndex < 0) return null
        return if (x < firstLeft) firstIndex else lastIndex + 1
    }

    private fun keyguardLocked(): Boolean {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return keyguard.isKeyguardLocked
    }

    // The lock screen captures normally but Android blanks the PIN and password entry, so tell the
    // operator how to unlock blind. Repeats after the notice expires while the device stays locked.
    private fun noteKeyguard() {
        if (!keyguardLocked()) {
            keyguardNoticeSent = false
            return
        }
        if (keyguardNoticeSent) return
        keyguardNoticeSent = true
        AgentController.sendDesktopMessage(
            "The device is locked. Android hides the PIN pad from screen capture: swipe up, then type the PIN or password on your keyboard and press Enter.",
            timeoutSeconds = KEYGUARD_NOTICE_S
        )
        mainHandler.postDelayed({ keyguardNoticeSent = false }, KEYGUARD_NOTICE_S * 1000L)
    }

    // Presses one of the keyguard's own buttons (key0..key9, delete_button, key_enter on AOSP), by
    // resource id first and by exact label as a fallback for vendor lock screens.
    private fun keyguardKey(idSuffix: String, label: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        val byId = try {
            root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$idSuffix")
        } catch (ex: Exception) {
            null
        }
        var node = byId?.firstOrNull { it.isEnabled }
        if (node == null && label != null) {
            val byText = try { root.findAccessibilityNodeInfosByText(label) } catch (ex: Exception) { null }
            node = byText?.firstOrNull {
                it.isClickable && (it.text?.toString() == label || it.contentDescription?.toString() == label)
            }
        }
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun enterFocused(): Boolean {
        val node = focusedEditable()
        if (node == null) {
            // No text field: on the lock screen this confirms the PIN.
            return keyguardLocked() && keyguardKey("key_enter", null)
        }
        // A single-line field treats Enter as its keyboard action (sign in, search, unlock); only
        // multi-line text takes a newline.
        if (!node.isMultiLine) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
            if (keyguardLocked() && keyguardKey("key_enter", null)) return true
        }
        return insertFocused("\n")
    }

    // Android masks a password field's text to accessibility, so rebuilding it from the node would
    // insert into dots; keep what the operator typed since the field got focus and rewrite it whole.
    private fun typeIntoPassword(node: AccessibilityNodeInfo, insert: String?): Boolean {
        val key = "${node.windowId}:${node.hashCode()}"
        if (key != passwordNodeKey) {
            passwordNodeKey = key
            passwordBuffer.setLength(0)
        }
        if (insert == null) {
            if (passwordBuffer.isNotEmpty()) passwordBuffer.setLength(passwordBuffer.length - 1)
        } else {
            passwordBuffer.append(insert)
        }
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, passwordBuffer.toString())
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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

    private fun setCursor(node: AccessibilityNodeInfo, pos: Int): Boolean = setSelectionRange(node, pos, pos)

    // TextView rejects a selection whose start is past its end, so order the two.
    private fun setSelectionRange(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean {
        val args = Bundle()
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, min(start, end))
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, max(start, end))
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun nodeKey(node: AccessibilityNodeInfo): String = "${node.windowId}:${node.hashCode()}"

    private fun replaceText(node: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        setCursor(node, cursor)
        shadowNodeKey = nodeKey(node)
        shadowText = text
        shadowCursor = cursor
        shadowUptimeMs = SystemClock.uptimeMillis()
        return true
    }

    // The field's text and selection, or the text of a write the app hasn't applied yet: a
    // keystroke that read the stale text would rebuild from it and drop the previous character.
    private fun fieldState(node: AccessibilityNodeInfo): Pair<String, Pair<Int, Int>> {
        val text = fieldText(node)
        val shadow = shadowText
        if (shadow != null && shadowNodeKey == nodeKey(node) && text != shadow &&
            SystemClock.uptimeMillis() - shadowUptimeMs < SHADOW_TEXT_MS) {
            return Pair(shadow, Pair(shadowCursor, shadowCursor))
        }
        return Pair(text, selectionRange(node, text.length))
    }

    private fun insertFocused(insert: String): Boolean {
        val node = focusedEditable() ?: return false
        if (node.isPassword) return typeIntoPassword(node, insert)
        val (text, selection) = fieldState(node)
        val (s, e) = selection
        return replaceText(node, text.substring(0, s) + insert + text.substring(e), s + insert.length)
    }

    private fun backspaceFocused(): Boolean {
        val node = focusedEditable() ?: return false
        if (node.isPassword) return typeIntoPassword(node, null)
        val (text, selection) = fieldState(node)
        val (s, e) = selection
        return when {
            s != e -> replaceText(node, text.substring(0, s) + text.substring(e), s)
            s > 0 -> replaceText(node, text.substring(0, s - 1) + text.substring(s), s - 1)
            else -> true
        }
    }

    private fun deleteForwardFocused(): Boolean {
        val node = focusedEditable() ?: return false
        if (node.isPassword) return false
        val (text, selection) = fieldState(node)
        val (s, e) = selection
        return when {
            s != e -> replaceText(node, text.substring(0, s) + text.substring(e), s)
            e < text.length -> replaceText(node, text.substring(0, e) + text.substring(e + 1), e)
            else -> true
        }
    }

    private fun moveFocusedCursor(delta: Int): Boolean {
        val node = focusedEditable() ?: return true
        val text = fieldText(node)
        val (s, e) = selectionRange(node, text.length)
        if (shiftHeld) {
            // Extend from the anchor, which Android keeps as the selection start.
            val anchor = node.textSelectionStart.coerceIn(0, text.length)
            val focus = (node.textSelectionEnd.coerceIn(0, text.length) + delta).coerceIn(0, text.length)
            return setSelectionRange(node, anchor, focus)
        }
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
        if (shiftHeld) return setSelectionRange(node, node.textSelectionStart.coerceIn(0, text.length), pos)
        return setCursor(node, pos)
    }

    private fun notifyUnsupportedKeyboard() {
        if (unsupportedKeyboardNotified) return
        unsupportedKeyboardNotified = true
        AgentController.sendDesktopMessage("Android unattended keyboard input is limited to focused editable text and basic navigation keys.")
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
        private const val MAX_QUEUED_STEPS = 64
        // Finger timing for streamed strokes.
        private const val PRESS_MS = 40L
        private const val LIFT_MS = 10L
        private const val MIN_SEGMENT_MS = 16L
        private const val MAX_SEGMENT_MS = 200L
        private const val TAP_MS = 80L
        private const val DOUBLE_TAP_WINDOW_MS = 700L
        private const val SCROLL_STEP_PX = 350
        private const val SCROLL_SWIPE_MS = 250L
        private const val APP_DRAWER_VERIFY_MS = 700L
        private const val HOME_SETTLE_MS = 450L
        private const val APP_DRAWER_SWIPE_MS = 300L
        private const val KEYGUARD_NOTICE_S = 45
        private const val CAPTURE_WATCHDOG_MS = 4_000L
        private const val DRAG_SELECT_SLOP = 8
        private const val DRAG_SELECT_DEFER_MS = 300L
        private const val SHADOW_TEXT_MS = 800L
    }
}
