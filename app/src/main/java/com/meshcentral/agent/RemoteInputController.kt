package com.meshcentral.agent

import android.content.Context
import android.util.Log
import android.view.WindowManager
import kotlin.math.hypot

/**
 * Translates MeshCentral desktop/KVM input frames into Android AccessibilityService
 * gestures. Byte layouts below are taken verbatim from MeshCentral's own desktop viewer
 * (public/scripts/agent-desktop-0.0.2.js), so they match what the server/viewer actually
 * sends to this agent over the p=2 relay.
 *
 * Called from MeshTunnel.processBinaryDesktopCmd():
 *   cmd 2  -> onMouse(button, x, y)   or onScroll(x, y, wheel) when size==12
 *   cmd 1  -> onKeyLegacy(action, vk)
 *   cmd 85 -> onKeyUnicode(action, code)
 * Remote surface size is fed from MeshTunnel.updateDesktopDisplaySize() via setRemoteSize().
 *
 * Mouse button codes (viewer -> agent): 2=Ldown 4=Lup 8=Rdown 16=Rup 32=Mdown 64=Mup
 *   136=double-click 0=move. Coordinates are big-endian shorts in the advertised screen space.
 */
class RemoteInputController private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "MeshRemoteInput"

        const val BTN_LEFT_DOWN = 2
        const val BTN_LEFT_UP = 4
        const val BTN_RIGHT_DOWN = 8
        const val BTN_RIGHT_UP = 16
        const val BTN_MIDDLE_DOWN = 32
        const val BTN_MIDDLE_UP = 64
        const val BTN_DOUBLECLICK = 136
        const val BTN_MOVE = 0

        // Key actions (viewer sends action-1): 0=down 1=up 3=ex-up 4=ex-down.
        const val KEY_DOWN = 0
        const val KEY_UP = 1

        private const val MOVE_THRESHOLD = 12f       // device px: tap vs drag
        private const val LONG_PRESS_MS = 500L

        @Volatile private var inst: RemoteInputController? = null
        fun get(context: Context): RemoteInputController =
            inst ?: synchronized(this) {
                inst ?: RemoteInputController(context.applicationContext).also { inst = it }
            }
    }

    // Remote (advertised) surface size; 0 until the first screen-size command.
    private var remoteW = 0
    private var remoteH = 0

    // Left-button drag state.
    private var down = false
    private var downTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val points = ArrayList<Pair<Float, Float>>(64)

    fun setRemoteSize(w: Int, h: Int) {
        if (w > 0 && h > 0) { remoteW = w; remoteH = h }
    }

    private fun deviceSize(): Pair<Int, Int> {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION") val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun mapX(x: Int): Float {
        val (dw, _) = deviceSize()
        return if (remoteW > 0) x.toFloat() * dw / remoteW else x.toFloat()
    }
    private fun mapY(y: Int): Float {
        val (_, dh) = deviceSize()
        return if (remoteH > 0) y.toFloat() * dh / remoteH else y.toFloat()
    }

    private fun svc(): MeshInputAccessibilityService? {
        val s = MeshInputAccessibilityService.instance
        if (s == null) Log.w(TAG, "AccessibilityService not enabled; input dropped")
        return s
    }

    /** cmd 2, size 10: a mouse button/move event. */
    fun onMouse(button: Int, x: Int, y: Int) {
        val s = svc() ?: return
        val dx = mapX(x); val dy = mapY(y)
        Log.i(TAG, "onMouse btn=$button remote=($x,$y) device=($dx,$dy) remoteSize=${remoteW}x$remoteH")
        when (button) {
            BTN_LEFT_DOWN -> {
                down = true
                downTime = System.currentTimeMillis()
                startX = dx; startY = dy; lastX = dx; lastY = dy
                points.clear(); points.add(dx to dy)
            }
            BTN_MOVE -> if (down) {
                if (hypot((dx - lastX).toDouble(), (dy - lastY).toDouble()) > 1.5) {
                    points.add(dx to dy); lastX = dx; lastY = dy
                }
            }
            BTN_LEFT_UP -> if (down) {
                down = false
                val dur = System.currentTimeMillis() - downTime
                val moved = hypot((dx - startX).toDouble(), (dy - startY).toDouble()).toFloat()
                when {
                    moved <= MOVE_THRESHOLD && dur >= LONG_PRESS_MS -> s.longPress(startX, startY, dur)
                    moved <= MOVE_THRESHOLD -> s.tap(startX, startY)
                    else -> { points.add(dx to dy); s.dragPath(points.toList(), dur.coerceIn(60, 1500)) }
                }
                points.clear()
            }
            BTN_RIGHT_DOWN -> s.longPress(dx, dy)          // right click -> long press (context)
            BTN_DOUBLECLICK -> s.doubleTap(dx, dy)
            // middle button and stray up-events: ignored
        }
    }

    /** cmd 2, size 12: wheel scroll (button byte is 0; wheel is a signed short at [10..11]). */
    fun onScroll(x: Int, y: Int, wheelRaw: Int) {
        val s = svc() ?: return
        val wheel = if (wheelRaw > 32767) wheelRaw - 65536 else wheelRaw // signed
        val dx = mapX(x); val dy = mapY(y)
        val (_, dh) = deviceSize()
        val amount = dh * 0.25f
        val toY = if (wheel > 0) dy + amount else dy - amount   // wheel up -> content down
        s.swipe(dx, dy.coerceIn(0f, dh.toFloat()), dx, toY.coerceIn(0f, dh.toFloat()), 120)
    }

    /** cmd 1, size 6: legacy key by Windows virtual-key code. Act on key-down only. */
    fun onKeyLegacy(action: Int, vk: Int) {
        if (action != KEY_DOWN && action != 4 /*ex-down*/) return
        val s = svc() ?: return
        when (vk) {
            0x08 -> s.backspace()          // Backspace
            0x0D -> s.pressEnter()         // Enter
            0x1B -> s.back()               // Esc -> Back
            0x09 -> s.typeText("\t")       // Tab
            0x24 -> s.home()               // Home
            0x5B, 0x5C -> s.home()         // Win key -> Home
            0x5D -> s.recents()            // Apps/Menu key -> Recents (overview)
            0x2E -> s.backspace()          // Delete (approx)
            else -> { /* printable handled via unicode path (cmd 85) */ }
        }
    }

    /** cmd 85, size 7: unicode character. Act on key-down only. */
    fun onKeyUnicode(action: Int, code: Int) {
        if (action != KEY_DOWN) return
        val s = svc() ?: return
        if (code == 13 || code == 10) { s.pressEnter(); return }
        if (code in 1..0x10FFFF) {
            try { s.typeText(String(Character.toChars(code))) }
            catch (e: Exception) { Log.w(TAG, "bad unicode $code: ${e.message}") }
        }
    }
}
