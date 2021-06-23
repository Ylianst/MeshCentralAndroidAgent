package com.meshcentral.agent

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceFragmentCompat

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsFragment = this;
        visibleScreen = 5;
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