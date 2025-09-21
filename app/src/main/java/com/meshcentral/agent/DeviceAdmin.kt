package com.meshcentral.agent

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class DeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context,"Device Admin activated", Toast.LENGTH_SHORT).show()
        //super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context,"Device Admin disactivated", Toast.LENGTH_SHORT).show()
        //super.onDisabled(context, intent)
    }
}