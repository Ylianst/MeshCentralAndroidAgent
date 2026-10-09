package com.meshcentral.agent

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        // Settings set by device management can not be changed here
        val managed = g_managedConfig
        if (managed?.autoConnect != null) lockPreference("pref_autoconnect")
        if (managed?.autoConsent != null) lockPreference("pref_autoconsent")
    }

    private fun lockPreference(key: String) {
        val pref = findPreference<SwitchPreferenceCompat>(key) ?: return
        pref.isEnabled = false
        pref.summaryOn = getString(R.string.managed_by_organization)
        pref.summaryOff = getString(R.string.managed_by_organization)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsFragment = this;
        visibleScreen = 5;
    }

    override fun onDestroy() {
        if (settingsFragment === this) settingsFragment = null
        g_mainActivity?.settingsChanged()
        super.onDestroy()
    }

    fun exit() {
        g_mainActivity?.settingsChanged()
        findNavController().navigate(R.id.action_settingsFragment_to_FirstFragment)
    }
}