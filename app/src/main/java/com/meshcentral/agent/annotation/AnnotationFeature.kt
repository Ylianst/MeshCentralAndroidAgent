package com.meshcentral.agent.annotation

import android.content.Context
import android.os.Build
import android.provider.Settings

object AnnotationFeature {

    fun onScreenShareStarted(ctx: Context) {
        // Do NOT auto-start unless the user opted in AND permission exists.
        if (AnnotationPrefs.isAutoEnabled(ctx) && hasOverlayPermission(ctx)) {
            AnnotationController.show(ctx)
        }
    }

    fun onScreenShareStopped(ctx: Context) {
        // Always hide when sharing ends.
        AnnotationController.hide(ctx)
    }

    fun stopAnnotations(ctx: Context) {
        AnnotationController.hide(ctx)
    }

    fun capabilities(ctx: Context): Map<String, Any> = mapOf(
        "overlay" to "supported",
        "permission" to if (hasOverlayPermission(ctx)) "granted" else "denied",
        "auto" to if (AnnotationPrefs.isAutoEnabled(ctx)) "on" else "off"
    )

    private fun hasOverlayPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(ctx) else true
}
