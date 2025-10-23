package com.meshcentral.agent.annotation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopAnnotationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.stopService(Intent(context, AnnotationOverlayService::class.java))
    }
}
