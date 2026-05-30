package com.meshcentral.agent

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        if (AgentController.enterpriseEnforced) {
            findPreference<SwitchPreferenceCompat>("pref_autoconnect")?.isEnabled = false
            findPreference<SwitchPreferenceCompat>("pref_autoconsent")?.isEnabled = false
            findPreference<SwitchPreferenceCompat>("pref_autoconnect")?.summary = getString(R.string.enterprise_enforced)
            findPreference<SwitchPreferenceCompat>("pref_autoconsent")?.summary = getString(R.string.enterprise_enforced)
        }
        findPreference<Preference>("pref_unattended_accessibility")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            true
        }
        findPreference<Preference>("pref_battery_optimization")?.setOnPreferenceClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !AgentController.isIgnoringBatteryOptimizations()) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:${requireContext().packageName}")
                    startActivity(intent)
                } catch (ex: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            true
        }
        findPreference<Preference>("pref_notification_permission")?.setOnPreferenceClickListener {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${requireContext().packageName}"))
            }
            startActivity(intent)
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsFragment = this;
        visibleScreen = 5;
        refreshStatus()
    }

    override fun onDestroy() {
        g_mainActivity?.settingsChanged()
        super.onDestroy()
    }

    fun exit() {
        g_mainActivity?.settingsChanged()
        findNavController().navigate(R.id.action_settingsFragment_to_FirstFragment)
    }

    private fun refreshStatus() {
        findPreference<Preference>("pref_unattended_accessibility")?.summary =
            if (AgentController.isAccessibilityServiceEnabled()) {
                getString(R.string.ready)
            } else {
                getString(R.string.unattended_accessibility_summary)
            }
        findPreference<Preference>("pref_battery_optimization")?.summary =
            if (AgentController.isIgnoringBatteryOptimizations()) {
                getString(R.string.ready)
            } else {
                getString(R.string.battery_optimization_summary)
            }
        findPreference<Preference>("pref_notification_permission")?.summary =
            if (AgentController.areNotificationsEnabled()) {
                getString(R.string.ready)
            } else {
                getString(R.string.notification_permission_summary)
            }
        findPreference<Preference>("pref_boot_start")?.summary =
            if (AgentController.shouldAutoStart()) {
                getString(R.string.ready)
            } else {
                getString(R.string.needs_setup)
            }
    }
}
