package com.meshcentral.agent.annotation

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

object AnnotationServiceBus {
    private var serviceRef: WeakReference<AnnotationOverlayService>? = null

    private val main = Handler(Looper.getMainLooper())
    private val idGen = AtomicLong(1)
    private var activeStrokeId: String? = null

    private var cachedStyle: DrawStyle? = null

    internal fun attach(service: AnnotationOverlayService) {
        serviceRef = WeakReference(service)
        // Apply cached style immediately when service attaches
        cachedStyle?.let { style ->
            main.post {
                serviceRef?.get()?.setStyle(style)
            }
        }
    }

    internal fun detach(service: AnnotationOverlayService) {
        serviceRef?.get()?.takeIf { it == service }?.let { serviceRef = null }
        activeStrokeId = null
        cachedStyle = null  // Clear cache when service stops
    }

    fun isActive(): Boolean = serviceRef?.get() != null

    fun post(cmd: DrawCmd) {
        main.post {
            serviceRef?.get()?.applyCommand(cmd)
        }
    }

    fun clear() {
        post(DrawCmd(type = "clear", strokeId = "all"))
        activeStrokeId = null
    }

    fun remove(id: String) = post(DrawCmd(type = "remove", strokeId = id))

    fun setStyle(style: DrawStyle) {
        // Always cache the style
        cachedStyle = style

        // Apply to service if it exists
        post(
            DrawCmd(
                type = "style",
                strokeId = "style",
                color = style.color,
                width = style.widthPx
            )
        )
    }

    private fun newId(prefix: String = "s"): String = "$prefix${idGen.getAndIncrement()}"

    private fun scheduleTtl(id: String, ttlMs: Long) {
        if (ttlMs <= 0) return
        main.postDelayed({
            post(DrawCmd(type = "remove", strokeId = id))
            if (activeStrokeId == id) activeStrokeId = null
        }, ttlMs)
    }

    // ---------- Streaming pen ----------
    fun strokeStart(x: Float, y: Float) {
        val id = newId("stroke_")
        activeStrokeId = id
        post(DrawCmd(type = "begin", strokeId = id, x = x, y = y))
    }

    fun strokeMove(x: Float, y: Float) {
        val id = activeStrokeId ?: return
        post(DrawCmd(type = "move", strokeId = id, x = x, y = y))
    }

    fun strokeEnd(ttlMs: Long = 0L) {
        val id = activeStrokeId ?: return
        post(DrawCmd(type = "end", strokeId = id, ttlMs = ttlMs))
        scheduleTtl(id, ttlMs)
        activeStrokeId = null
    }

    // ---------- Batched path ----------
    fun drawPath(points: List<Pair<Float, Float>>, ttlMs: Long = 0L) {
        if (points.size < 2) return
        val id = newId("path_")
        post(DrawCmd(type = "begin", strokeId = id, x = points.first().first, y = points.first().second))
        for (i in 1 until points.size) {
            val (px, py) = points[i]
            post(DrawCmd(type = "move", strokeId = id, x = px, y = py))
        }
        post(DrawCmd(type = "end", strokeId = id, ttlMs = ttlMs))
        scheduleTtl(id, ttlMs)
    }

    // ---------- Shapes ----------
    fun drawRect(x: Float, y: Float, w: Float, h: Float, ttlMs: Long = 0L) {
        val id = newId("rect_")
        post(DrawCmd(type = "rect", strokeId = id, x = x, y = y, x2 = w, y2 = h, ttlMs = ttlMs))
        scheduleTtl(id, ttlMs)
    }

    fun drawCircle(cx: Float, cy: Float, r: Float, ttlMs: Long = 0L) {
        val id = newId("circle_")
        post(DrawCmd(type = "circle", strokeId = id, x = cx, y = cy, r = r, ttlMs = ttlMs))
        scheduleTtl(id, ttlMs)
    }

    fun drawArrow(x1: Float, y1: Float, x2: Float, y2: Float, ttlMs: Long = 0L) {
        val id = newId("arrow_")
        post(DrawCmd(type = "arrow", strokeId = id, x = x1, y = y1, x2 = x2, y2 = y2, ttlMs = ttlMs))
        scheduleTtl(id, ttlMs)
    }

    // ---------- Demo ----------
    fun demoCircle(context: Context, cx: Float, cy: Float, r: Float) {
        val id = newId("demo_")
        post(DrawCmd(type = "begin", strokeId = id, x = cx + r, y = cy))
        val segments = 32
        for (i in 1..segments) {
            val theta = (2.0 * Math.PI * i / segments).toFloat()
            post(
                DrawCmd(
                    type = "move",
                    strokeId = id,
                    x = cx + r * kotlin.math.cos(theta),
                    y = cy + r * kotlin.math.sin(theta)
                )
            )
        }
        post(DrawCmd(type = "end", strokeId = id))
    }
}