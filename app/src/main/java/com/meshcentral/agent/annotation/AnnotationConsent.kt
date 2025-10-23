package com.meshcentral.agent.annotation

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.provider.Settings
import android.widget.Toast

object AnnotationConsent {

    /**
     * Entry-point to enable annotations with client consent:
     * - If auto is ON and permission is granted -> enable immediately.
     * - Else show a dialog with Allow once / Always allow / Deny.
     * - If permission is missing at the end, flow will route to system Settings.
     */
    fun requestEnableWithConsent(activity: Activity) {
        // ALWAYS run on UI thread since we might show dialogs
        activity.runOnUiThread {
            val ctx = activity

            val hasPermission = if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(ctx) else true
            val autoEnabled = AnnotationPrefs.isAutoEnabled(ctx)

            // Fast path: if permission already granted, just start overlay
            if (hasPermission) {
                if (autoEnabled) {
                    // Auto-enabled, start immediately
                    AnnotationController.show(ctx)
                } else {
                    // Ask once, but since permission exists, just confirm and start
                    AlertDialog.Builder(activity)
                        .setTitle("Remote Annotation Request")
                        .setMessage("A remote user is requesting to annotate over your screen to help guide you.")
                        .setNegativeButton("Deny", null)
                        .setNeutralButton("OK") { _, _ ->
                            AnnotationController.show(ctx)
                        }
                        .setPositiveButton("Always allow") { _, _ ->
                            AnnotationPrefs.setAutoEnabled(ctx, true)
                            AnnotationController.show(ctx)
                        }
                        .show()
                }
                return@runOnUiThread
            }

            // Permission NOT granted - need to go to settings
            if (!autoEnabled) {
                AlertDialog.Builder(activity)
                    .setTitle("Remote Annotation Request")
                    .setMessage("A remote user is requesting to annotate over your screen to help guide you.\n\nYou'll need to enable 'Display over other apps' permission.")
                    .setNegativeButton("Deny", null)
                    .setNeutralButton("Allow") { _, _ ->
                        Toast.makeText(ctx, "Please enable 'Display over other apps'", Toast.LENGTH_LONG).show()
                        AnnotationController.ensurePermissionAndShow(activity, showRationale = false)
                    }
                    .setPositiveButton("Always allow") { _, _ ->
                        AnnotationPrefs.setAutoEnabled(ctx, true)
                        Toast.makeText(ctx, "Please enable 'Display over other apps'", Toast.LENGTH_LONG).show()
                        AnnotationController.ensurePermissionAndShow(activity, showRationale = false)
                    }
                    .show()
            } else {
                // Auto is ON but permission missing → go straight to settings
                Toast.makeText(ctx, "Please enable 'Display over other apps'", Toast.LENGTH_LONG).show()
                AnnotationController.ensurePermissionAndShow(activity, showRationale = false)
            }
        }
    }
}