package com.meshcentral.agent.annotation

import android.content.Context
import androidx.preference.PreferenceManager
import com.meshcentral.agent.meshAgent

object AnnotationPrefs {
    private const val KEY_AUTO = "pref_annotation_auto"

    fun isAutoEnabled(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(KEY_AUTO, false)

    fun setAutoEnabled(ctx: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(ctx)
            .edit()
            .putBoolean(KEY_AUTO, enabled)
            .apply()
        try { meshAgent?.sendAnnotationCaps() } catch (_: Exception) {}
    }
}

