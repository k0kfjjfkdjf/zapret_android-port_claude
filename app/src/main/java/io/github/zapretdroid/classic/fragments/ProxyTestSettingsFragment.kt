package io.github.zapretdroid.classic.fragments

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.*
import io.github.zapretdroid.R
import io.github.zapretdroid.classic.utility.*

class ProxyTestSettingsFragment : PreferenceFragmentCompat() {

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updatePreferences()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.bb_proxy_test_settings, rootKey)

        setupNumberSummary("byedpi_proxytest_delay", R.string.bb_proxytest_delay_desc)
        setupNumberSummary("byedpi_proxytest_requests", R.string.bb_proxytest_requests_desc)
        setupNumberSummary("byedpi_proxytest_limit", R.string.bb_proxytest_limit_desc)
        setupNumberSummary("byedpi_proxytest_timeout", R.string.bb_proxytest_timeout_desc)

        setEditTestPreferenceListenerInt("byedpi_proxytest_delay", 0, 10)
        setEditTestPreferenceListenerInt("byedpi_proxytest_requests", 1, 20)
        setEditTestPreferenceListenerInt("byedpi_proxytest_timeout", 1, 15)
        setEditTestPreferenceListenerInt("byedpi_proxytest_limit", 1, 50)

        setEditTestPreferenceListenerDomain("byedpi_proxytest_sni")

        updatePreferences()
    }

    override fun onResume() {
        super.onResume()
        updatePreferences()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    override fun onPause() {
        super.onPause()
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    private fun updatePreferences() {
        val switchUserCommands = findPreferenceNotNull<SwitchPreference>("byedpi_proxytest_usercommands")
        val textUserCommands = findPreferenceNotNull<EditTextPreference>("byedpi_proxytest_commands")
        val manageDomainLists = findPreferenceNotNull<Preference>("manage_domain_lists")
        val activeLists = DomainListUtils.getLists(requireContext()).filter { it.isActive }

        manageDomainLists.summary = if (activeLists.isEmpty()) {
            getString(R.string.bb_domain_lists_summary)
        } else {
            activeLists.joinToString(", ") { it.name }
        }

        textUserCommands.isEnabled = switchUserCommands.isChecked
    }

    private fun setupNumberSummary(key: String, descriptionResId: Int) {
        val pref = findPreference<EditTextPreference>(key) ?: return

        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
            val value = p.text ?: "—"
            val description = getString(descriptionResId)
            "$value - $description"
        }
    }
}