package com.meshcentral.agent

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsFragment = this
        visibleScreen = 5
    }

    override fun onResume() {
        super.onResume()
        // Register listener when fragment becomes visible
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        // Unregister listener when fragment is hidden
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        // This is called automatically whenever ANY preference changes
        when (key) {
            "pref_autoconnect",
            "pref_autoconsent",
            "pref_annotation_auto" -> {
                // These settings need full settingsChanged() processing
                g_mainActivity?.settingsChanged()
            }
            "pref_autoconsentnotifcation" -> {
                // This one just needs the global variable updated
                g_autoConsentNotification = sharedPreferences?.getBoolean(key, true) ?: true
            }

        }
    }

    override fun onDestroy() {
        g_mainActivity?.settingsChanged()
        super.onDestroy()
    }

    fun exit() {
        g_mainActivity?.settingsChanged()
        findNavController().navigate(R.id.action_settingsFragment_to_FirstFragment)
    }
}