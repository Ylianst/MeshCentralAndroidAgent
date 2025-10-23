package com.meshcentral.agent.annotation

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.meshcentral.agent.meshAgent

object AnnotationController {
    private const val REQ_OVERLAY = 8801

    /**
     * Ensures overlay permission and starts the overlay.
     *
     * @param activity host activity (used to launch Settings)
     * @param showRationale whether to show a friendly dialog before jumping to Settings (default: true)
     * @param title optional custom title for the rationale dialog
     * @param message optional custom message for the rationale dialog
     * @param positiveLabel positive button label (default: "Open Settings")
     * @param negativeLabel negative button label (default: "Cancel")
     */
    @JvmStatic
    fun ensurePermissionAndShow(
        activity: Activity,
        showRationale: Boolean = true,
        title: String = "Allow on-screen annotations",
        message: String = "To show guidance draw during remote support, allow 'Draw over other apps'. You can turn it off anytime.",
    positiveLabel: String = "Open Settings",
    negativeLabel: String = "Cancel"
    ) {
        // Pre-M: nothing to ask
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || hasOverlayPermission(activity)) {
            show(activity)
            return
        }

        if (!showRationale) {
            openOverlaySettings(activity)
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveLabel) { _, _ -> openOverlaySettings(activity) }
            .setNegativeButton(negativeLabel, null)
            .show()
    }

    @JvmStatic
    fun onActivityResult(activity: Activity, requestCode: Int) {
        if (requestCode == REQ_OVERLAY) {
            // Give Android a moment to process the permission change
            Handler(Looper.getMainLooper()).postDelayed({
                try { meshAgent?.sendAnnotationCaps() } catch (_: Exception) {}
                if (hasOverlayPermission(activity)) {
                    show(activity)
                } else {
                    // Permission still not granted
                    android.widget.Toast.makeText(
                        activity,
                        "Overlay permission not granted",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }, 200) // Small delay to ensure permission is registered
        }
    }

    @JvmStatic
    fun show(ctx: Context) {
        if (!hasOverlayPermission(ctx)) {
            android.util.Log.w("AnnotationController", "show() called but permission not granted")
            return
        }

        if (!AnnotationServiceBus.isActive()) {
            android.util.Log.d("AnnotationController", "Starting AnnotationOverlayService")
            val i = Intent(ctx, AnnotationOverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }

                // Send started event to server
                meshAgent?.sendJson(org.json.JSONObject().apply {
                    put("action", "annotationAck")
                    put("op", "event")
                    put("event", "started")
                })

                if (ctx is Activity) {
                    ctx.invalidateOptionsMenu()
                }
            } catch (e: Exception) {
                android.util.Log.e("AnnotationController", "Failed to start service: ${e.message}")
            }
        } else {
            android.util.Log.d("AnnotationController", "Service already active")
        }
    }

    @JvmStatic
    fun hide(ctx: Context) {
        android.util.Log.d("AnnotationController", "Stopping AnnotationOverlayService")
        ctx.stopService(Intent(ctx, AnnotationOverlayService::class.java))
        meshAgent?.sendJson(org.json.JSONObject().apply {
            put("action", "annotationAck")
            put("op", "event")
            put("event", "stopped")
        })

        if (ctx is Activity) {
            ctx.invalidateOptionsMenu()
        }
    }

    private fun openOverlaySettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivityForResult(intent, REQ_OVERLAY)
    }

    private fun hasOverlayPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(ctx)
        } else true
    }
}