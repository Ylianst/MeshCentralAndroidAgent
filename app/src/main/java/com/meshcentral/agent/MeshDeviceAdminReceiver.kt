package com.meshcentral.agent

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin / Device Owner receiver.
 *
 * Provisioned once, on a factory-reset device with no accounts:
 *   adb shell dpm set-device-owner \
 *     com.meshcentral.agent2/com.meshcentral.agent.MeshDeviceAdminReceiver
 *
 * Once this app is Device Owner it can silently grant its own runtime permissions,
 * block its own uninstall, keep itself running, and re-arm on boot.
 */
class MeshDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "MeshDeviceAdmin"
        fun component(context: Context) =
            ComponentName(context.applicationContext, MeshDeviceAdminReceiver::class.java)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
        // If we are the Device Owner, lock down and auto-grant everything we need.
        DeviceOwnerHelper.applyOwnerPolicies(context)
        // Make sure the persistent relay service is running.
        MeshRelayForegroundService.start(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device admin disabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.i(TAG, "Provisioning complete")
        DeviceOwnerHelper.applyOwnerPolicies(context)
    }
}
