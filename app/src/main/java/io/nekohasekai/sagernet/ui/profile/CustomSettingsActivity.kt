package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.types.Custom
import moe.matsuri.nb4a.ui.EditConfigPreference

/** A raw JSON profile in one of the four custom subtypes (sing-box / Xray, outbound / full config). */
class CustomSettingsActivity : BindingSettingsActivity<Custom>() {

    override fun createEntity() = Custom().apply { subtype = Custom.CUSTOM_OUTBOUND }
    override val preferencesResource = R.xml.custom_preferences
    override val supportsRawJson = false

    init {
        pbm.text("name")
        pbm.text("subtype")
        pbm.text("config", Key.SERVER_CONFIG)
    }

    private var editConfigPreference: EditConfigPreference? = null

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        editConfigPreference = findPreference(Key.SERVER_CONFIG)
    }

    override fun onResume() {
        super.onResume()
        editConfigPreference?.notifyChanged()
    }

}
