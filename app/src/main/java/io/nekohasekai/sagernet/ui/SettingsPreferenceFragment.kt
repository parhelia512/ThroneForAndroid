package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.triggerFullRestart
import java.io.File

/**
 * The settings root: one entry per sub-screen (io.nekohasekai.sagernet.ui.settings, opened by [SettingsFragment]),
 * the backup screen and the reset / cache actions.
 */
class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        addPreferencesFromResource(R.xml.global_preferences)

        findPreference<Preference>(KEY_BACKUP)!!.setOnPreferenceClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_tools)
            true
        }

        findPreference<Preference>(KEY_RESET_SETTINGS)!!.setOnPreferenceClickListener {
            requireContext().confirmAction(
                getString(R.string.confirm_reset_settings),
                getString(R.string.reset_settings_message),
                R.string.confirm_restore,
            ) {
                DataStore.configurationStore.reset()
                triggerFullRestart(requireContext())
            }
            true
        }

        findPreference<Preference>(Key.CLEAR_CACHE)!!.setOnPreferenceClickListener {
            requireContext().confirmAction(getString(R.string.clear_cache_confirm), null, R.string.confirm_clear) {
                clearAppCache()
            }
            true
        }
    }

    private fun clearAppCache() {
        try {
            val cacheDir = SagerNet.application.cacheDir
            clearDirFiles(cacheDir, skipFiles = setOf("neko.log"))

            val parentDir = cacheDir.parentFile
            val relativeCache = File(parentDir, "cache")
            if (relativeCache.exists() && relativeCache.isDirectory) {
                clearDirFiles(relativeCache)
            }

            Toast.makeText(requireContext(), R.string.clear_cache_success, Toast.LENGTH_SHORT).show()

            Handler(Looper.getMainLooper()).postDelayed({
                needReload()
            }, 500)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.clear_cache_failed, e.message), Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun clearDirFiles(dir: File, skipFiles: Set<String> = emptySet()): Boolean {
        if (dir.isDirectory) {
            val children = dir.list() ?: return true

            for (child in children) {
                val childFile = File(dir, child)

                if (child == "neko.log") {
                    try {
                        childFile.writeText("")
                        continue
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (child in skipFiles) {
                    continue
                }

                if (childFile.isDirectory) {
                    clearDirFiles(childFile, skipFiles)
                } else {
                    childFile.delete()
                }
            }

            return true
        }
        return false
    }

    private companion object {
        const val KEY_BACKUP = "settingsBackup"
        const val KEY_RESET_SETTINGS = "resetSettings"
    }
}
