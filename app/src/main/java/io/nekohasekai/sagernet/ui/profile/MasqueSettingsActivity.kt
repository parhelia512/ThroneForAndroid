package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.types.Masque

/** MASQUE endpoint: the primary fields on the screen, everything else through the Raw JSON preference. */
class MasqueSettingsActivity : BindingSettingsActivity<Masque>() {

    override fun createEntity() = Masque()
    override val preferencesResource = R.xml.masque_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("private_key")
        pbm.text("peer_public_key")
        pbm.text("address")
        pbm.int("mtu")
        pbm.int("http_version")
        pbm.bool("disable_version_fallback")
        pbm.text("tls.server_name")
        pbm.bool("tls.insecure")
        pbm.text("tls.certificate")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("mtu")
        passwordSummary("private_key")
        multilineInput("address", "tls.certificate")
    }

}
