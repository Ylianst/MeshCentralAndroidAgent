package com.meshcentral.agent

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Central place for the Device-Owner-only powers. Every call is a no-op (logged) when the
 * app is not actually the Device Owner, so the same build still runs as an ordinary
 * sideloaded agent for testing.
 */
object DeviceOwnerHelper {
    private const val TAG = "MeshDeviceOwner"

    // Runtime permissions we want granted without a user prompt. Extend as the fork needs.
    private val AUTO_GRANT = listOf(
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.READ_MEDIA_IMAGES,
        android.Manifest.permission.READ_MEDIA_VIDEO,
        android.Manifest.permission.READ_MEDIA_AUDIO,
    )

    fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun admin(context: Context): ComponentName = MeshDeviceAdminReceiver.component(context)

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    /**
     * Called after provisioning and on every boot/admin-enable. Idempotent.
     * Grants permissions, blocks uninstall, and re-arms the accessibility service.
     */
    fun applyOwnerPolicies(context: Context) {
        if (!isDeviceOwner(context)) {
            Log.i(TAG, "Not Device Owner; skipping owner policies")
            return
        }
        val dpm = dpm(context)
        val admin = admin(context)

        // 1) Silently grant our runtime permissions.
        for (perm in AUTO_GRANT) {
            try {
                dpm.setPermissionGrantState(
                    admin, context.packageName, perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            } catch (e: Exception) {
                Log.w(TAG, "grant $perm failed: ${e.message}")
            }
        }

        // 2) Prevent casual uninstall / keep the app usable.
        try { dpm.setUninstallBlocked(admin, context.packageName, true) } catch (_: Exception) {}

        // 3) Whitelist the accessibility service (does not enable it, but allows it).
        try {
            dpm.setPermittedAccessibilityServices(
                admin,
                listOf(context.packageName) // null would mean "all allowed"
            )
        } catch (e: Exception) {
            Log.w(TAG, "setPermittedAccessibilityServices failed: ${e.message}")
        }

        // 4) Best-effort: enable our accessibility service without user interaction.
        enableAccessibilityService(context)

        // 5) Keep the app off battery restrictions if the API is available.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // no direct DPM call; the provisioning script handles deviceidle whitelist.
        }
    }

    /**
     * Turns on MeshInputAccessibilityService by writing the secure setting directly.
     * Requires WRITE_SECURE_SETTINGS (granted by the provisioning script via `adb pm grant`).
     * The provisioning script sets this too; this method just re-arms it after reboots.
     */
    fun enableAccessibilityService(context: Context) {
        val svc = ComponentName(context, MeshInputAccessibilityService::class.java).flattenToString()
        try {
            val current = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (!current.split(':').contains(svc)) {
                val updated = if (current.isEmpty()) svc else "$current:$svc"
                Settings.Secure.putString(
                    context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated
                )
            }
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Accessibility service enabled")
        } catch (e: SecurityException) {
            // WRITE_SECURE_SETTINGS not granted -> the provisioning script must do it.
            Log.w(TAG, "Cannot self-enable accessibility (no WRITE_SECURE_SETTINGS): ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "enableAccessibilityService failed: ${e.message}")
        }
    }

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
}
