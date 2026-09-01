package cn.tinyhai.auto_oral_calculation.ui.fragment

import android.os.Bundle
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.Preference.OnPreferenceClickListener
import android.preference.PreferenceFragment
import android.preference.PreferenceManager
import android.preference.SwitchPreference
import cn.tinyhai.auto_oral_calculation.BuildConfig
import cn.tinyhai.auto_oral_calculation.MODULE_PREFS_NAME
import cn.tinyhai.auto_oral_calculation.R
import cn.tinyhai.auto_oral_calculation.entities.AutoAnswerMode
import cn.tinyhai.auto_oral_calculation.util.StringRes
import cn.tinyhai.auto_oral_calculation.util.openGithub

class SettingsFragment : PreferenceFragment(), OnPreferenceClickListener {

    private class Holder(manager: PreferenceManager, stringRes: StringRes) {
        val alwaysTrue: SwitchPreference =
            manager.findPreference(stringRes.KEY_ALWAYS_TRUE_ANSWER) as SwitchPreference
        val doubleNicknameLength: SwitchPreference = manager.findPreference(stringRes.KEY_DOUBLE_NICKNAME_LENGTH) as SwitchPreference
        val autoHonor: SwitchPreference = manager.findPreference(stringRes.KEY_AUTO_HONOR) as SwitchPreference
        val autoAnswerConfig: ListPreference =
            manager.findPreference(stringRes.KEY_AUTO_ANSWER_CONFIG) as ListPreference
        val customAnswerConfig: EditTextPreference =
            manager.findPreference(stringRes.KEY_CUSTOM_ANSWER_CONFIG) as EditTextPreference
        val quickModeMustWin: SwitchPreference =
            manager.findPreference(stringRes.KEY_QUICK_MODE_MUST_WIN) as SwitchPreference
        val quickModeInterval: EditTextPreference =
            manager.findPreference(stringRes.KEY_QUICK_MODE_INTERVAL) as EditTextPreference
        val pkCyclic: SwitchPreference = manager.findPreference(stringRes.KEY_PK_CYCLIC) as SwitchPreference
        val pkCyclicInterval: EditTextPreference =
            manager.findPreference(stringRes.KEY_PK_CYCLIC_INTERVAL) as EditTextPreference
        val debug: SwitchPreference = manager.findPreference(stringRes.KEY_DEBUG) as SwitchPreference
        val github: Preference = manager.findPreference(stringRes.KEY_GITHUB)!!
        val version: Preference = manager.findPreference(stringRes.KEY_VERSION)!!
    }

    private lateinit var holder: Holder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferencesName = MODULE_PREFS_NAME
        addPreferencesFromResource(R.xml.host_settings)
        holder = Holder(preferenceManager, StringRes(resources))
        initPreference()
    }

    private fun initPreference() {
        holder.version.setSummary(BuildConfig.VERSION_NAME)
        holder.autoAnswerConfig.let {
            var index = kotlin.runCatching { Integer.parseInt(it.value) }.getOrElse { 0 }
            it.summary = "当前选择: ${AutoAnswerMode.entries[index].value}"
            var mode = AutoAnswerMode.entries[index]
            holder.customAnswerConfig.isEnabled = mode == AutoAnswerMode.CUSTOM
            holder.quickModeMustWin.isEnabled = mode == AutoAnswerMode.QUICK
            holder.quickModeInterval.isEnabled = mode == AutoAnswerMode.QUICK
            holder.pkCyclic.isEnabled =
                mode == AutoAnswerMode.QUICK || mode == AutoAnswerMode.STANDARD

            it.setOnPreferenceChangeListener { _, newValue ->
                index = kotlin.runCatching { Integer.parseInt(newValue.toString()) }.getOrElse { 0 }
                mode = AutoAnswerMode.entries[index]
                holder.customAnswerConfig.isEnabled = mode == AutoAnswerMode.CUSTOM
                holder.quickModeMustWin.isEnabled = mode == AutoAnswerMode.QUICK
                holder.quickModeInterval.isEnabled = mode == AutoAnswerMode.QUICK
                holder.autoAnswerConfig.summary = "当前选择: ${mode.value}"
                holder.pkCyclic.isEnabled =
                    mode == AutoAnswerMode.QUICK || mode == AutoAnswerMode.STANDARD
                true
            }
        }
        holder.quickModeInterval.let {
            var interval = kotlin.runCatching { Integer.parseInt(it.text) }.getOrElse { 200 }
            it.summary = "当前间隔: $interval 毫秒"
            it.setOnPreferenceChangeListener { _, newValue ->
                interval =
                    kotlin.runCatching { Integer.parseInt(newValue.toString()) }.getOrElse { 200 }
                it.summary = "当前间隔: $interval 毫秒"
                true
            }
        }
        holder.pkCyclicInterval.let {
            var interval = kotlin.runCatching { Integer.parseInt(it.text) }.getOrElse { 1500 }
            it.summary = "当前间隔: $interval 毫秒"
            it.setOnPreferenceChangeListener { _, newValue ->
                interval =
                    kotlin.runCatching { Integer.parseInt(newValue.toString()) }.getOrElse { 1500 }
                it.summary = "当前间隔: $interval 毫秒"
                true
            }
        }

        holder.github.onPreferenceClickListener = this
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when (preference) {
            holder.github -> {
                context.openGithub()
            }
        }
        return true
    }
}