package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.types.Socks

class SocksSettingsActivity : BindingSettingsActivity<Socks>() {

    override fun createEntity() = Socks()
    override val preferencesResource = R.xml.socks_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.int("version")
        pbm.text("username")
        pbm.text("password")
        pbm.bool("uot")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
        onMenu("version") { setVisible(it != "4", "password") }
    }

}
