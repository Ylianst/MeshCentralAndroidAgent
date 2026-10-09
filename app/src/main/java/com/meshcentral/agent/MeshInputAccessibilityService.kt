package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The only non-root way to inject input on Android. Exposes tap / double-tap / long-press /
 * swipe / drag / text / global-action primitives that RemoteInputController drives from the
 * incoming KVM frames. The running service registers itself in [instance].
 */
class MeshInputAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MeshInput"
        @Volatile var instance: MeshInputAccessibilityService? = null
            private set
        fun isConnected(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- Gesture primitives ---------------------------------------------------------

    // A tap must last long enough for the system to register it; 1ms strokes get dropped.
    fun tap(x: Float, y: Float) = dispatch(Path().apply { moveTo(x, y) }, 0, 60)

    fun doubleTap(x: Float, y: Float) {
        tap(x, y)
        // Second tap shortly after; 150ms is inside the system double-tap window.
        instance?.let {
            android.os.Handler(mainLooper).postDelayed({ tap(x, y) }, 150)
        }
    }

    fun longPress(x: Float, y: Float, durationMs: Long = 600) =
        dispatch(Path().apply { moveTo(x, y) }, 0, durationMs.coerceAtLeast(1))

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 200) =
        dispatch(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, durationMs.coerceAtLeast(1))

    fun dragPath(pts: List<Pair<Float, Float>>, durationMs: Long) {
        if (pts.isEmpty()) return
        val path = Path().apply {
            moveTo(pts.first().first, pts.first().second)
            for (p in pts.drop(1)) lineTo(p.first, p.second)
        }
        dispatch(path, 0, durationMs.coerceAtLeast(1))
    }

    private fun dispatch(path: Path, startTime: Long, durationMs: Long) {
        try {
            val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { Log.i(TAG, "gesture completed") }
                override fun onCancelled(g: GestureDescription?) { Log.w(TAG, "gesture CANCELLED") }
            }, null)
            Log.i(TAG, "dispatchGesture returned=$ok dur=${durationMs}ms")
        } catch (e: Exception) {
            Log.w(TAG, "dispatchGesture failed: ${e.message}")
        }
    }

    // ---- Global actions (special keys) ----------------------------------------------

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun lockScreen(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false

    // ---- Text entry ------------------------------------------------------------------

    fun typeText(text: String) {
        val node = findFocusedEditable() ?: run { Log.w(TAG, "typeText: no focused field"); return }
        val existing = node.text?.toString() ?: ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun backspace() {
        val node = findFocusedEditable() ?: return
        val existing = node.text?.toString() ?: ""
        if (existing.isEmpty()) return
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing.dropLast(1))
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressEnter() {
        val node = findFocusedEditable() ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else typeText("\n")
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        return searchEditable(rootInActiveWindow)
    }

    private fun searchEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            searchEditable(node.getChild(i))?.let { return it }
        }
        return null
    }
}
