package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

@Volatile
var g_RemoteInputService : RemoteInputService? = null

// Injects remote desktop mouse and keyboard input using the accessibility service APIs.
// Mouse clicks and drags become touch gestures, keys become global actions, focus moves and text edits.
class RemoteInputService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var touchPath : Path? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchLastX = 0f
    private var touchLastY = 0f
    private var touchStartTime = 0L
    private var shiftDown = false
    private var highSurrogate : Char? = null

    companion object {
        const val MOUSE_MOVE = 0x00
        const val MOUSE_LEFT_DOWN = 0x02
        const val MOUSE_LEFT_UP = 0x04
        const val MOUSE_RIGHT_DOWN = 0x08
        const val MOUSE_RIGHT_UP = 0x10
        const val MOUSE_MIDDLE_DOWN = 0x20
        const val MOUSE_MIDDLE_UP = 0x40
        const val MOUSE_DOUBLE_CLICK = 0x88
        private const val TAP_SLOP = 12f

        fun isEnabled(context: Context) : Boolean {
            val expected = ComponentName(context, RemoteInputService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        g_RemoteInputService = this
    }

    override fun onDestroy() {
        if (g_RemoteInputService === this) g_RemoteInputService = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    override fun onInterrupt() { }

    // Map a position in the captured image to a real screen position. The capture is the
    // display mirrored into a virtual display of captureWidth x captureHeight, letterboxed.
    private fun toScreen(x: Int, y: Int, captureWidth: Int, captureHeight: Int) : Pair<Float, Float> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val realWidth = metrics.widthPixels.toFloat()
        val realHeight = metrics.heightPixels.toFloat()
        if (captureWidth <= 0 || captureHeight <= 0) return Pair(x.toFloat(), y.toFloat())
        val scale = min(captureWidth / realWidth, captureHeight / realHeight)
        val offsetX = (captureWidth - realWidth * scale) / 2
        val offsetY = (captureHeight - realHeight * scale) / 2
        val sx = ((x - offsetX) / scale).coerceIn(0f, realWidth - 1)
        val sy = ((y - offsetY) / scale).coerceIn(0f, realHeight - 1)
        return Pair(sx, sy)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun injectMouse(button: Int, x: Int, y: Int, captureWidth: Int, captureHeight: Int) {
        val (sx, sy) = toScreen(x, y, captureWidth, captureHeight)
        handler.post {
            when (button) {
                MOUSE_MOVE -> {
                    if (touchPath != null && hypot(sx - touchLastX, sy - touchLastY) >= 2f) {
                        touchPath!!.lineTo(sx, sy)
                        touchLastX = sx; touchLastY = sy
                    }
                }
                MOUSE_LEFT_DOWN -> {
                    touchPath = Path().apply { moveTo(sx, sy) }
                    touchStartX = sx; touchStartY = sy
                    touchLastX = sx; touchLastY = sy
                    touchStartTime = SystemClock.uptimeMillis()
                }
                MOUSE_LEFT_UP -> {
                    val path = touchPath ?: return@post
                    touchPath = null
                    val duration = (SystemClock.uptimeMillis() - touchStartTime).coerceIn(1L, GestureDescription.getMaxGestureDuration())
                    if (hypot(sx - touchStartX, sy - touchStartY) < TAP_SLOP && hypot(touchLastX - touchStartX, touchLastY - touchStartY) < TAP_SLOP) {
                        dispatchStroke(Path().apply { moveTo(touchStartX, touchStartY) }, duration)
                    } else {
                        if (sx != touchLastX || sy != touchLastY) path.lineTo(sx, sy)
                        dispatchStroke(path, duration)
                    }
                }
                MOUSE_RIGHT_UP -> performGlobalAction(GLOBAL_ACTION_BACK)
                MOUSE_MIDDLE_UP -> performGlobalAction(GLOBAL_ACTION_HOME)
                // Double clicks also arrive as two down/up pairs, so 0x88 needs no extra taps.
                else -> { }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun injectWheel(x: Int, y: Int, delta: Int, captureWidth: Int, captureHeight: Int) {
        if (delta == 0) return
        val (sx, sy) = toScreen(x, y, captureWidth, captureHeight)
        val metrics = resources.displayMetrics
        val notches = min(abs(delta), 1200) / 120f
        val distance = (metrics.heightPixels / 8f) * notches.coerceAtLeast(1f)
        // Wheel up (positive delta) scrolls the content up, which is a downward swipe.
        val endY = (if (delta > 0) sy + distance else sy - distance).coerceIn(1f, metrics.heightPixels - 1f)
        handler.post {
            dispatchStroke(Path().apply { moveTo(sx, sy); lineTo(sx, endY) }, 250)
        }
    }

    // Windows virtual key codes as sent by the MeshCentral web viewer
    fun injectKey(vk: Int, down: Boolean) {
        if (vk == 16 || vk == 160 || vk == 161) { shiftDown = down; return }
        if (!down) return
        handler.post {
            when (vk) {
                8 -> { if (!editText { t, s, e -> if (s != e) Triple(t.removeRange(s, e), s, s) else if (s > 0) Triple(t.removeRange(s - 1, s), s - 1, s - 1) else null }) performGlobalAction(GLOBAL_ACTION_BACK) } // Backspace
                9 -> moveFocus(if (shiftDown) View.FOCUS_BACKWARD else View.FOCUS_FORWARD) // Tab
                13 -> pressEnter()
                27 -> performGlobalAction(GLOBAL_ACTION_BACK) // Escape
                32 -> { if (!insertText(" ")) clickFocused() } // Space
                33 -> scrollFocused(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) // Page up
                34 -> scrollFocused(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) // Page down
                36, 91, 92 -> performGlobalAction(GLOBAL_ACTION_HOME) // Home, Windows keys
                37 -> moveFocus(View.FOCUS_LEFT)
                38 -> moveFocus(View.FOCUS_UP)
                39 -> moveFocus(View.FOCUS_RIGHT)
                40 -> moveFocus(View.FOCUS_DOWN)
                46 -> editText { t, s, e -> if (s != e) Triple(t.removeRange(s, e), s, s) else if (e < t.length) Triple(t.removeRange(s, s + 1), s, s) else null } // Delete
                93 -> performGlobalAction(GLOBAL_ACTION_RECENTS) // Context menu key
                in 48..57 -> insertText(vk.toChar().toString())
                in 65..90 -> insertText((if (shiftDown) vk.toChar() else vk.toChar().lowercaseChar()).toString())
                173 -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
                174 -> adjustVolume(AudioManager.ADJUST_LOWER)
                175 -> adjustVolume(AudioManager.ADJUST_RAISE)
                176 -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                177 -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                178 -> mediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
                179 -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                112 -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) // F1
                113 -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) // F2
                else -> { }
            }
        }
    }

    fun injectUnicode(code: Int, down: Boolean) {
        if (!down) return
        val c = code.toChar()
        handler.post {
            if (Character.isHighSurrogate(c)) { highSurrogate = c; return@post }
            val text = if (highSurrogate != null && Character.isLowSurrogate(c)) "${highSurrogate}$c" else c.toString()
            highSurrogate = null
            when (c) {
                '\r', '\n' -> pressEnter()
                '\b' -> injectKey(8, true)
                '\t' -> moveFocus(View.FOCUS_FORWARD)
                else -> insertText(text)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun dispatchStroke(path: Path, duration: Long) {
        try {
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            dispatchGesture(gesture, null, null)
        } catch (ex: Exception) {
            println("RemoteInput gesture failed: $ex")
        }
    }

    private fun focusedNode() : AccessibilityNodeInfo? {
        return findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    }

    // The text field to type into. TV apps often draw their own on-screen keyboard which keeps
    // the focus, so fall back to the focused or the only visible text field of the active window.
    private fun editableNode() : AccessibilityNodeInfo? {
        val focused = focusedNode()
        if (focused != null && focused.isEditable) return focused
        val root = rootInActiveWindow ?: return null
        firstMatching(root) { it.isEditable && it.isFocused }?.let { return it }
        val visible = ArrayList<AccessibilityNodeInfo>()
        collectMatching(root, visible) { it.isEditable && it.isVisibleToUser }
        return if (visible.size == 1) visible[0] else null
    }

    private fun collectMatching(node: AccessibilityNodeInfo?, out: ArrayList<AccessibilityNodeInfo>, depth: Int = 0, test: (AccessibilityNodeInfo) -> Boolean) {
        if (node == null || depth > 40 || out.size > 1) return
        if (test(node)) out.add(node)
        for (i in 0 until node.childCount) collectMatching(node.getChild(i), out, depth + 1, test)
    }

    private fun firstMatching(node: AccessibilityNodeInfo?, depth: Int = 0, test: (AccessibilityNodeInfo) -> Boolean) : AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (test(node)) return node
        for (i in 0 until node.childCount) {
            val found = firstMatching(node.getChild(i), depth + 1, test)
            if (found != null) return found
        }
        return null
    }

    // D-pad style navigation, the same focus search a remote control key press would use.
    private fun moveFocus(direction: Int) {
        var current = focusedNode()
        if (current == null) {
            firstMatching(rootInActiveWindow) { it.isFocusable && it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return
        }
        // Lists such as Leanback rows keep the focus on the list itself, start from its first item
        if (current.childCount > 0) {
            val item = firstMatching(current) { it != current && it.isFocusable && it.isVisibleToUser }
            if (item != null && item.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) current = item
        }
        val next = current.focusSearch(direction) ?: nearestInDirection(current, direction) ?: return
        if (!next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    }

    // Fallback when the app does not answer the focus search: the closest focusable node in that direction.
    private fun nearestInDirection(from: AccessibilityNodeInfo, direction: Int) : AccessibilityNodeInfo? {
        val origin = Rect().also { from.getBoundsInScreen(it) }
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectAll(rootInActiveWindow, candidates) { it.isFocusable && it.isVisibleToUser && it != from }
        var best : AccessibilityNodeInfo? = null
        var bestScore = Long.MAX_VALUE
        val r = Rect()
        for (node in candidates) {
            node.getBoundsInScreen(r)
            if (r.contains(origin) || origin.contains(r)) continue
            val (main, cross) = when (direction) {
                View.FOCUS_LEFT -> Pair(origin.left - r.right, abs(r.centerY() - origin.centerY()))
                View.FOCUS_RIGHT -> Pair(r.left - origin.right, abs(r.centerY() - origin.centerY()))
                View.FOCUS_UP -> Pair(origin.top - r.bottom, abs(r.centerX() - origin.centerX()))
                View.FOCUS_DOWN -> Pair(r.top - origin.bottom, abs(r.centerX() - origin.centerX()))
                else -> return null
            }
            if (main < 0) continue
            val score = main.toLong() * main + 4L * cross * cross
            if (score < bestScore) { bestScore = score; best = node }
        }
        return best
    }

    private fun collectAll(node: AccessibilityNodeInfo?, out: ArrayList<AccessibilityNodeInfo>, depth: Int = 0, test: (AccessibilityNodeInfo) -> Boolean) {
        if (node == null || depth > 40) return
        if (test(node)) out.add(node)
        for (i in 0 until node.childCount) collectAll(node.getChild(i), out, depth + 1, test)
    }

    private fun clickFocused() {
        var node = focusedNode()
        while (node != null && !node.isClickable) node = node.parent
        node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun pressEnter() {
        val node = editableNode()
        if (node != null && (node.isFocused || focusedNode() == null)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else if (node.isMultiLine) {
                insertText("\n")
            }
            return
        }
        clickFocused()
    }

    private fun scrollFocused(action: Int) {
        var node = focusedNode()
        while (node != null && !node.isScrollable) node = node.parent
        (node ?: firstMatching(rootInActiveWindow) { it.isScrollable })?.performAction(action)
    }

    private fun insertText(insert: String) : Boolean {
        return editText { t, s, e -> Triple(t.replaceRange(s, e, insert), s + insert.length, s + insert.length) }
    }

    // Change the text of the focused editable field. The lambda gets text and selection and
    // returns the new text and selection, or null to leave the field as it is.
    private fun editText(change: (String, Int, Int) -> Triple<String, Int, Int>?) : Boolean {
        val node = editableNode() ?: return false
        // Hint text is reported as text while the field is empty.
        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        val text = if (showingHint) "" else (node.text?.toString() ?: "")
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > text.length || end > text.length) { start = text.length; end = text.length }
        if (start > end) { val t = start; start = end; end = t }
        val (newText, newStart, newEnd) = change(text, start, end) ?: return true
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        val sel = Bundle()
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newStart)
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newEnd)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return true
    }

    private fun adjustVolume(direction: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun mediaKey(keyCode: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
