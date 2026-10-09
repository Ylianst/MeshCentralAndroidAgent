package com.meshcentral.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Relaunches the persistent relay service after a reboot. Registered for BOOT_COMPLETED
 * (and OEM quickboot variants). As Device Owner the app is exempt from background-start
 * limits, so startForegroundService() from here is allowed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i("MeshBoot", "Boot received: ${intent.action}")
                // Re-arm owner policies (accessibility can be cleared across reboots) then start.
                DeviceOwnerHelper.applyOwnerPolicies(context)
                MeshRelayForegroundService.start(context)
            }
        }
    }
}
