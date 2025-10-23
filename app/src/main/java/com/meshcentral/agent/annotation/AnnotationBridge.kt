package com.meshcentral.agent.annotation

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import org.json.JSONObject

/**
 * Translates server "annotation" JSON into local overlay actions.
 * Supports normalized coords (0..1) when msg["norm"] == true.
 */
object AnnotationBridge {
    private fun ok(op: String)  = JSONObject().put("action","annotationAck").put("op",op).put("ok",true)
    private fun err(op: String, m:String) =
        JSONObject().put("action","annotationAck").put("op",op).put("ok",false).put("error",m)
    private fun hasOverlayPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(ctx) else true
    }
    fun handleFromServer(activity: Activity, msg: JSONObject): JSONObject? {
        val op = msg.optString("op", "")
        val norm = msg.optBoolean("norm", false)
        val (W, H) = screenSize(activity) // overlay matches screen

        android.util.Log.d("AnnotationBridge", "msg=" + msg.toString())


        fun fx(k: String) = msg.getDouble(k).toFloat().let { if (norm) it * W else it }
        fun fy(k: String) = msg.getDouble(k).toFloat().let { if (norm) it * H else it }
        fun fdim(k: String) = msg.getDouble(k).toFloat().let { if (norm) it * (if (k == "w" || k == "x2" || k == "r") W else H) else it }
        fun fttl() = msg.optLong("ttlMs", 0L)



        return try {
            when (op) {
                // lifecycle ---------------------------------------------------
                "start" -> {
                    // Tell the browser whether we *currently* have overlay permission.
                    val perm = if (Build.VERSION.SDK_INT >= 23)
                        Settings.canDrawOverlays(activity)
                    else true

                    return JSONObject().apply {
                        put("action", "annotationAck")
                        put("op", "start")
                        put("ok", true)
                        put("supported", true)
                        put("permission", if (perm) "granted" else "denied")
                        // if you have nodeid available in the incoming msg, echo it:
                        msg.optString("nodeid", null)?.let { put("nodeid", it) }
                        msg.optJSONArray("nodeids")?.optString(0)?.let { put("nodeid", it) }
                    }
                }

                "stop"  -> { AnnotationController.hide(activity); ok(op) }
                "clear" -> { AnnotationServiceBus.clear(); ok(op) }

                // style -------------------------------------------------------
                "style" -> {
                    val colorAny = msg.opt("color")
                    val color = when (colorAny) {
                        is Number -> colorAny.toInt()
                        is String -> parseColor(colorAny)
                        else -> null
                    }
                    val width = if (msg.has("width")) msg.optDouble("width", 6.0).toFloat() else null
                    AnnotationServiceBus.setStyle(
                        DrawStyle(
                            color = color ?: DrawStyle().color,
                            widthPx = width ?: DrawStyle().widthPx
                        )
                    )
                    ok(op)
                }

                // streamed pen -----------------------------------------------
                "strokeStart" -> { AnnotationServiceBus.strokeStart(fx("x"), fy("y")); ok(op) }
                "strokeMove"  -> { AnnotationServiceBus.strokeMove(fx("x"), fy("y")); ok(op) }
                "strokeEnd"   -> { AnnotationServiceBus.strokeEnd(fttl()); ok(op) }

                // batched path -----------------------------------------------
                "path" -> {
                    val pts = msg.getJSONArray("points")
                    val list = ArrayList<Pair<Float, Float>>(pts.length())
                    for (i in 0 until pts.length()) {
                        val p = pts.getJSONArray(i)
                        val x = p.getDouble(0).toFloat()
                        val y = p.getDouble(1).toFloat()
                        list += if (norm) (x * W) to (y * H) else x to y
                    }
                    AnnotationServiceBus.drawPath(list, fttl())
                    ok(op)
                }

                // shapes ------------------------------------------------------
                "rect" -> {
                    AnnotationServiceBus.drawRect(
                        fx("x"), fy("y"),
                        fdim("w"), fdim("h"),
                        fttl()
                    )
                    ok(op)
                }
                "circle" -> {
                    val cx = fx("cx"); val cy = fy("cy")
                    val r  = fdim("r")
                    AnnotationServiceBus.drawCircle(cx, cy, r, fttl())
                    ok(op)
                }
                "arrow" -> {
                    AnnotationServiceBus.drawArrow(
                        fx("x"), fy("y"), fx("x2"), fy("y2"),
                        fttl()
                    )
                    ok(op)
                }

                // housekeeping -----------------------------------------------
                "remove" -> {
                    val id = msg.optString("id", "")
                    if (id.isNotEmpty()) {
                        // send remove as a DrawCmd
                        AnnotationServiceBus.post(DrawCmd("remove", id))
                        ok(op)
                    } else err(op, "Missing id")
                }

                "probe" -> {
                    // Try to preserve whatever ID the server/browser sent down.
                    // Browser code accepts either "nodeid" or we can pick from "nodeids[0]".
                    val nodeIdFromSingle = msg.optString("nodeid", null)
                    val nodeIdFromArray  = msg.optJSONArray("nodeids")?.optString(0)
                    val nodeId = nodeIdFromSingle ?: nodeIdFromArray

                    val perm = if (hasOverlayPermission(activity)) "granted" else "denied"

                    // If this agent includes the Annotation feature, it's supported.
                    val supported = true

                    return JSONObject().apply {
                        put("action", "annotationAck")
                        if (nodeId != null) put("nodeid", nodeId)
                        put("supported", supported)
                        put("permission", perm)  // "granted" | "denied"
                    }
                }

                else -> err(op, "Unknown op")
            }
        } catch (t: Throwable) {
            err(op, t.message ?: "error")
        }
    }

    private fun parseColor(s: String): Int? = try {
        android.graphics.Color.parseColor(s) // #RRGGBB / #AARRGGBB
    } catch (_: Exception) { null }

    // For future Emulator detection.
    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                || "google_sdk" == Build.PRODUCT)
    }

    // Get current screen size in pixels (overlay is MATCH_PARENT)
    private fun screenSize(activity: Activity): Pair<Float, Float> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val wm = activity.windowManager
            val b = wm.currentWindowMetrics.bounds
            b.width().toFloat() to b.height().toFloat()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
        }
    }
}
