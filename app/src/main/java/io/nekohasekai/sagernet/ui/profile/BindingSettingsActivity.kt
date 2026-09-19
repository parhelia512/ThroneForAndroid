package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.annotation.XmlRes
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.toStringPretty
import io.nekohasekai.sagernet.outbound.Outbound
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.ui.EditConfigPreference
import org.json.JSONObject

/**
 * A profile editor whose whole state is a list of [PreferenceBindingManager] bindings over one preference XML: the
 * bindings copy the outbound into the cache on init and back on serialize, and every preference key is the bound
 * (dotted) field path. A screen may also carry a "Raw JSON" [EditConfigPreference] keyed [ProfileSettingsActivity.KEY_RAW_JSON]
 * that opens the whole ExportToJson in the JSON editor.
 */
abstract class BindingSettingsActivity<T : Outbound>(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ProfileSettingsActivity<T>(resId) {

    protected val pbm = PreferenceBindingManager()

    @get:XmlRes
    protected abstract val preferencesResource: Int

    override fun T.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(KEY_RAW_JSON, prettyJson(this))
    }

    override fun T.serialize() {
        pbm.fromCacheAll(this)
    }

    private var rawJsonPreference: EditConfigPreference? = null

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(preferencesResource)
        pbm.setPreferenceFragment(this)
        rawJsonPreference = findPreference<EditConfigPreference>(KEY_RAW_JSON)?.apply {
            setOnPreferenceClickListener {
                openRawJsonEditor()
                true
            }
        }
        onPreferencesCreated()
    }

    override fun onResume() {
        super.onResume()
        rawJsonPreference?.notifyChanged()
    }

    /** Called with the screen inflated and the bindings attached; wire visibility and input modifiers here. */
    protected open fun PreferenceFragmentCompat.onPreferencesCreated() {
    }

    companion object {
        fun prettyJson(outbound: Outbound): String = try {
            JSONObject(outbound.exportToJson().toCompact()).toStringPretty()
        } catch (e: Exception) {
            Logs.w(e)
            outbound.exportToJson().toCompact()
        }
    }

}
