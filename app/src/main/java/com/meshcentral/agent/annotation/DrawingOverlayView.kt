package com.meshcentral.agent.annotation

import android.content.Context
import android.graphics.*
import android.view.View
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin


class DrawingOverlayView(ctx: Context) : View(ctx) {
    private val livePaths = ConcurrentHashMap<String, Path>()                 // in-progress strokes
    private val finalized = ConcurrentHashMap<String, Pair<Path, Paint>>()    // finished shapes keyed by id

    // Current style used for new strokes/shapes
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.RED
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        // draw saved shapes
        for ((_, pair) in finalized) c.drawPath(pair.first, pair.second)
        // draw active stroke(s) with current paint
        for (p in livePaths.values) c.drawPath(p, currentPaint)
    }

    fun setStyle(style: DrawStyle) {
        currentPaint.color = style.color
        currentPaint.strokeWidth = style.widthPx
        invalidate()
    }

    fun clear() {
        livePaths.clear()
        finalized.clear()
        invalidate()
    }

    fun removeById(id: String) {
        livePaths.remove(id)
        finalized.remove(id)
        invalidate()
    }

    fun applyCommand(cmd: DrawCmd) {
        when (cmd.type) {
            // ---------- streaming stroke ----------
            "begin" -> {
                val x = cmd.x ?: return
                val y = cmd.y ?: return
                val p = Path().apply { moveTo(x, y) }
                livePaths[cmd.strokeId] = p
            }
            "move" -> {
                val x = cmd.x ?: return
                val y = cmd.y ?: return
                livePaths[cmd.strokeId]?.lineTo(x, y)
            }
            "end" -> {
                val p = livePaths.remove(cmd.strokeId) ?: return
                finalized[cmd.strokeId] = p to snapshotPaint(cmd)
            }

            // ---------- one-shot shapes ----------
            "rect" -> {
                val x = cmd.x ?: return
                val y = cmd.y ?: return
                val w = cmd.x2 ?: return
                val h = cmd.y2 ?: return
                val path = Path().apply {
                    addRect(RectF(x, y, x + w, y + h), Path.Direction.CW)
                }
                finalized[cmd.strokeId] = path to snapshotPaint(cmd)
            }
            "circle" -> {
                val cx = cmd.x ?: return
                val cy = cmd.y ?: return
                val r  = cmd.r ?: return
                val path = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
                finalized[cmd.strokeId] = path to snapshotPaint(cmd)
            }
            "arrow" -> {
                val x1 = cmd.x  ?: return
                val y1 = cmd.y  ?: return
                val x2 = cmd.x2 ?: return
                val y2 = cmd.y2 ?: return
                finalized[cmd.strokeId] = buildArrowPath(x1, y1, x2, y2) to snapshotPaint(cmd)
            }

            // ---------- housekeeping ----------
            "remove" -> removeById(cmd.strokeId)
            "clear"  -> clear()

            // ---------- style update (unified DrawCmd path) ----------
            "style" -> {
                cmd.color?.let { currentPaint.color = it }
                cmd.width?.let { currentPaint.strokeWidth = it }
            }
        }
        invalidate()
    }

    private fun snapshotPaint(cmd: DrawCmd): Paint {
        return Paint(currentPaint).apply {
            cmd.color?.let { color = it }
            cmd.width?.let { strokeWidth = it }
        }
    }

    private fun buildArrowPath(x1: Float, y1: Float, x2: Float, y2: Float): Path {
        val p = Path()
        // shaft
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)

        // head size scales with stroke width a bit
        val w = currentPaint.strokeWidth
        val headLen = 6f * w.coerceAtLeast(2f)   // length of the head “sides”
        val headWidth = 3f * w.coerceAtLeast(2f)

        val angle = atan2((y2 - y1), (x2 - x1))
        val a1 = angle + Math.toRadians(150.0).toFloat() // 150° from shaft
        val a2 = angle - Math.toRadians(150.0).toFloat()

        val hx1 = x2 + headLen * cos(a1)
        val hy1 = y2 + headLen * sin(a1)
        val hx2 = x2 + headLen * cos(a2)
        val hy2 = y2 + headLen * sin(a2)

        p.moveTo(x2, y2)
        p.lineTo(hx1, hy1)
        p.moveTo(x2, y2)
        p.lineTo(hx2, hy2)

        return p
    }
}
