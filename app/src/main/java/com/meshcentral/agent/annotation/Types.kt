package com.meshcentral.agent.annotation

import android.graphics.Color

data class DrawCmd(
    val type: String,          // "begin","move","end","rect","circle","arrow","style","clear","remove"
    val strokeId: String,      // logical id (e.g. "stroke_42", "circle_5", "all" for clear)
    val x: Float? = null,      // begin/move, rect.x, arrow.x1, circle.cx
    val y: Float? = null,      // begin/move, rect.y, arrow.y1, circle.cy
    val x2: Float? = null,     // rect.w  (or x2 if you prefer), arrow.x2
    val y2: Float? = null,     // rect.h  (or y2),               arrow.y2
    val r: Float? = null,      // circle radius
    val color: Int? = null,    // style.color (ARGB)
    val width: Float? = null,  // style.widthPx
    val ttlMs: Long? = null    // auto-clear per item
)

data class DrawStyle(
    val color: Int = Color.RED,
    val widthPx: Float = 6f
)